package com.jme3.scene.plugins.sdf;

import com.jme3.bounding.BoundingBox;
import com.jme3.bounding.BoundingSphere;
import com.jme3.bounding.BoundingVolume;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;
import com.jme3.scene.SceneChangeListener;
import com.jme3.scene.Spatial;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Event-maintained spatial hash of baked surface roots. Initial construction scans
 * the subtree once; subsequent synchronize calls consume only changed branches.
 * Camera queries visit overlapping cells and deduplicate candidates.
 * Rigid changes update entries in place and preserve memberships while cell ranges
 * stay unchanged; topology/data changes still rediscover affected branches. Very large
 * bounds use an overflow list instead of allocating unbounded cell memberships.
 * A baked ancestor owns its subtree even if explicitly invalidated; query excludes
 * invalidated data until replaced. Geometry/material validity remains the asset
 * pipeline's responsibility, not a per-frame hash operation.
 *
 * <pre>{@code
 * // 第一步：场景世界变换已更新后创建索引；cellSize 是世界单位，不是纹理尺寸。
 * SurfaceSceneIndex index = new SurfaceSceneIndex(rootNode, 32f);
 * // 第二步：正常使用 attach/detach、setLocalTransform、setUserData；通知只记录脏分支。
 * // 第三步：每帧几何状态更新后同步；静态帧不遍历场景，相机移动也不重建索引。
 * rootNode.updateGeometricState();
 * index.synchronize();
 * // 刚体移动不重新创建条目；覆盖格子不变时保留桶成员，半径查询仍使用最新包围盒。
 * // attach/detach 或替换烘焙数据仍按结构变化重建对应分支。
 * List<Spatial> nearby = index.query(cam.getLocation(), 50f);
 * // 第四步：SurfaceAttributeAtlas 已自动持有这个索引，原有 update(root,cam,renderer) 不变。
 * // 修改 getLocalTranslation() 返回的可变对象后须 forceRefresh(true,true,false)。
 * // 绕过场景 API 的外部批量编辑，可调用 index.rebuild() 显式全量重建。
 * // 第五步：卸载服务时 index.close() 解除根监听，不克隆/序列化运行时索引。
 * }</pre>
 *
 * All operations belong to the scene/render thread. Call after geometric updates.
 * Cost of moving a whole parent branch is proportional to that branch. Dense cells,
 * oversized objects or a query covering over 4096 cells may require a broader scan.
 */
public final class SurfaceSceneIndex implements AutoCloseable {
    private final Spatial root;
    private final String dataKey;
    private final float cellSize;
    private final Map<Spatial, Entry> entries = new IdentityHashMap<>();
    private final Map<Spatial, Set<Spatial>> indexedChildren = new IdentityHashMap<>();
    private final Map<Cell, Set<Entry>> cells = new HashMap<>();
    private final Set<Entry> overflow = new LinkedHashSet<>();
    private final Set<Spatial> dirty = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Spatial> moved = Collections.newSetFromMap(new IdentityHashMap<>());
    private final SceneChangeListener listener = this::changed;
    private long sequence;
    private long queryStamp;
    private int visited;
    private int candidates;
    private boolean closed;

    /** @param root watched subtree @param cellSize finite positive spatial-hash cell size */
    public SurfaceSceneIndex(Spatial root, float cellSize) {
        this(root, cellSize, SurfaceBakeData.USER_DATA_KEY);
    }

    // The global-distance-field compositor indexes SDF-only roots independently.
    SurfaceSceneIndex(Spatial root, float cellSize, String dataKey) {
        this.dataKey = dataKey;
        if (!Float.isFinite(cellSize) || cellSize <= 0) {
            throw new IllegalArgumentException("Invalid spatial cell size");
        }
        this.root = java.util.Objects.requireNonNull(root, "root");
        this.cellSize = cellSize;
        root.addSceneChangeListener(listener);
        rebuild();
    }

    private void changed(Spatial spatial, SceneChangeListener.Change change) {
        if ((change == SceneChangeListener.Change.ATTACHED || change == SceneChangeListener.Change.DETACHING)
                && indexedChildren.containsKey(spatial.getParent())) {
            // Node emits both events after modifying its child list, while the old
            // parent link is still available for DETACHING. The child itself is queued.
            Set<Spatial> siblings = indexedChildren.get(spatial.getParent());
            if (change == SceneChangeListener.Change.ATTACHED) {
                siblings.add(spatial);
            } else {
                siblings.remove(spatial);
            }
        }
        if (change == SceneChangeListener.Change.BOUNDS) {
            // Aggregate ancestor bounds do not imply all its children moved.
            for (Spatial cursor = spatial; cursor != null; cursor = cursor.getParent()) {
                if (entries.containsKey(cursor)) {
                    moved.add(cursor);
                    break;
                }
                if (cursor == root) {
                    break;
                }
            }
        } else if (change == SceneChangeListener.Change.TRANSFORM && entries.containsKey(spatial)) {
            moved.add(spatial);
        } else {
            dirty.add(spatial);
        }
    }

    /** Full rescan escape hatch after external mutations that bypass scene setters. */
    public void rebuild() {
        checkOpen();
        entries.clear();
        indexedChildren.clear();
        cells.clear();
        overflow.clear();
        dirty.clear();
        moved.clear();
        visited = 0;
        discover(root);
    }

    /** Consume queued mutations after updateGeometricState(); no scene scan on unchanged frames. */
    public void synchronize() {
        checkOpen();
        visited = 0;
        if (dirty.isEmpty()) {
            refreshMoved();
            return;
        }
        Set<Spatial> scopes = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Spatial spatial : dirty) {
            Spatial scope = spatial;
            for (Spatial p = spatial == root ? null : spatial.getParent(); p != null; p = p.getParent()) {
                if (p.getUserData(dataKey) != null) {
                    scope = p;
                }
                if (p == root) {
                    break;
                }
            }
            scopes.add(scope);
        }
        dirty.clear();
        List<Spatial> refresh = new ArrayList<>();
        for (Spatial scope : scopes) {
            boolean covered = false;
            for (Spatial p = scope.getParent(); p != null; p = p.getParent()) {
                if (scopes.contains(p)) {
                    covered = true;
                    break;
                }
            }
            if (!covered) {
                refresh.add(scope);
            }
        }
        // Remove all old memberships before inserting any new ones. A reparented
        // child may occur in one branch's OLD tree and another branch's NEW tree.
        for (Spatial scope : refresh) {
            removeBranch(scope);
        }
        for (Spatial scope : refresh) {
            if (belongs(scope)) {
                discover(scope);
            }
        }
        refreshMoved();
    }

    private void refreshMoved() {
        for (Spatial spatial : moved) {
            Entry entry = entries.get(spatial);
            if (entry != null) {
                visited++;
                indexBounds(entry);
            }
        }
        moved.clear();
    }

    private boolean belongs(Spatial spatial) {
        for (Spatial p = spatial; p != null; p = p.getParent()) {
            if (p == root) {
                return true;
            }
        }
        return false;
    }

    private void removeBranch(Spatial spatial) {
        visited++;
        Entry entry = entries.remove(spatial);
        if (entry != null) {
            overflow.remove(entry);
            for (Cell cell : entry.memberships) {
                Set<Entry> bucket = cells.get(cell);
                bucket.remove(entry);
                if (bucket.isEmpty()) {
                    cells.remove(cell);
                }
            }
        }
        Set<Spatial> previous = indexedChildren.remove(spatial);
        if (previous != null) {
            for (Spatial child : previous) {
                removeBranch(child);
            }
        }
    }

    private void discover(Spatial spatial) {
        visited++;
        Object data = spatial.getUserData(dataKey);
        if (data != null) {
            Entry entry = new Entry(spatial, data, sequence++);
            entries.put(spatial, entry);
            indexBounds(entry);
            return;
        }
        if (spatial instanceof Node) {
            Set<Spatial> children = Collections.newSetFromMap(new IdentityHashMap<>());
            children.addAll(((Node) spatial).getChildren());
            indexedChildren.put(spatial, children);
            for (Spatial child : ((Node) spatial).getChildren()) {
                discover(child);
            }
        }
    }

    private void indexBounds(Entry entry) {
        Spatial spatial = entry.spatial;
        Vector3f center = spatial.getWorldTranslation();
        Vector3f extent = new Vector3f();
        BoundingVolume bound = spatial.getWorldBound();
        if (bound != null) {
            center = bound.getCenter();
            if (bound instanceof BoundingBox) {
                BoundingBox box = (BoundingBox) bound;
                extent.set(box.getXExtent(), box.getYExtent(), box.getZExtent());
            } else if (bound instanceof BoundingSphere) {
                float radius = ((BoundingSphere) bound).getRadius();
                extent.set(radius, radius, radius);
            } else {
                replaceMemberships(entry, null);
                return;
            }
        }
        int[] range = range(center.subtract(extent), center.add(extent));
        replaceMemberships(entry, range == null || count(range) > 64 ? null : range);
    }

    private void replaceMemberships(Entry entry, int[] range) {
        if (entry.indexed && java.util.Arrays.equals(entry.range, range)) {
            return;
        }
        overflow.remove(entry);
        for (Cell cell : entry.memberships) {
            Set<Entry> bucket = cells.get(cell);
            bucket.remove(entry);
            if (bucket.isEmpty()) {
                cells.remove(cell);
            }
        }
        entry.memberships.clear();
        entry.range = range;
        entry.indexed = true;
        if (range == null) {
            overflow.add(entry);
        } else {
            forCells(range, cell -> {
                cells.computeIfAbsent(cell, unused -> new LinkedHashSet<>()).add(entry);
                entry.memberships.add(cell);
            });
        }
    }

    /**
     * Exact camera-to-world-bound radius filtering of spatial-hash candidates.
     * Does not synchronize implicitly. Returned instances are borrowed, list is independent.
     * @param camera finite world position
     * @param radius finite nonnegative query radius
     * @return nearby non-invalidated baked roots
     */
    public List<Spatial> query(Vector3f camera, float radius) {
        checkOpen();
        if (!Vector3f.isValidVector(camera) || !Float.isFinite(radius) || radius < 0) {
            throw new IllegalArgumentException("Invalid spatial query");
        }
        if (++queryStamp == 0) {
            for (Entry entry : entries.values()) {
                entry.queryStamp = 0;
            }
            queryStamp = 1;
        }
        List<Entry> found = new ArrayList<>();
        appendCandidates(overflow, found);
        int[] range = range(camera.subtract(radius, radius, radius), camera.add(radius, radius, radius));
        if (range == null || count(range) > 4096) {
            appendCandidates(entries.values(), found);
        } else {
            forCells(range, cell -> {
                Set<Entry> bucket = cells.get(cell);
                if (bucket != null) {
                    appendCandidates(bucket, found);
                }
            });
        }
        candidates = found.size();
        List<Entry> selected = new ArrayList<>();
        for (Entry entry : found) {
            Spatial spatial = entry.spatial;
            if ((!(entry.data instanceof SurfaceBakeData) || !((SurfaceBakeData) entry.data).isInvalidated()) && spatial.getUserData(dataKey) == entry.data
                    && distance(spatial, camera) <= radius) {
                selected.add(entry);
            }
        }
        selected.sort(java.util.Comparator.comparingLong(entry -> entry.order));
        List<Spatial> result = new ArrayList<>(selected.size());
        for (Entry entry : selected) {
            result.add(entry.spatial);
        }
        return result;
    }

    private void appendCandidates(java.util.Collection<Entry> source, List<Entry> result) {
        for (Entry entry : source) {
            if (entry.queryStamp != queryStamp) {
                entry.queryStamp = queryStamp;
                result.add(entry);
            }
        }
    }

    static float distance(Spatial spatial, Vector3f camera) {
        return spatial.getWorldBound() == null ? spatial.getWorldTranslation().distance(camera)
                : Math.max(0, spatial.getWorldBound().distanceToEdge(camera));
    }

    private int[] range(Vector3f min, Vector3f max) {
        int[] result = new int[6];
        for (int axis = 0; axis < 3; axis++) {
            double lo = Math.floor(min.get(axis) / (double) cellSize);
            double hi = Math.floor(max.get(axis) / (double) cellSize);
            if (!Double.isFinite(lo) || !Double.isFinite(hi) || lo < Integer.MIN_VALUE
                    || hi >= Integer.MAX_VALUE) {
                return null;
            }
            result[axis] = (int) lo;
            result[axis + 3] = (int) hi;
        }
        return result;
    }

    private double count(int[] r) {
        return ((double) r[3] - r[0] + 1) * ((double) r[4] - r[1] + 1) * ((double) r[5] - r[2] + 1);
    }

    private void forCells(int[] r, java.util.function.Consumer<Cell> action) {
        for (int z = r[2]; z <= r[5]; z++) {
            for (int y = r[1]; y <= r[4]; y++) {
                for (int x = r[0]; x <= r[3]; x++) {
                    action.accept(new Cell(x, y, z));
                }
            }
        }
    }

    /** @return nodes visited by latest synchronize/rebuild, zero for static scenes */
    public int getVisitedNodeCount() {
        return visited;
    }

    /** @return candidate entries examined by latest query, before exact radius filtering */
    public int getQueryCandidateCount() {
        return candidates;
    }

    /** @return number of indexed baked roots */
    public int getInstanceCount() {
        return entries.size();
    }

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("Surface scene index closed");
        }
    }

    @Override
    public void close() {
        if (!closed) {
            root.removeSceneChangeListener(listener);
            entries.clear();
            indexedChildren.clear();
            cells.clear();
            overflow.clear();
            dirty.clear();
            moved.clear();
            closed = true;
        }
    }

    private static final class Entry {
        final Spatial spatial;
        final Object data;
        final long order;
        int[] range;
        boolean indexed;
        long queryStamp;
        final List<Cell> memberships = new ArrayList<>();

        Entry(Spatial spatial, Object data, long order) {
            this.spatial = spatial;
            this.data = data;
            this.order = order;
        }
    }

    private static final class Cell {
        final int x;
        final int y;
        final int z;

        Cell(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public int hashCode() {
            return (x * 73856093) ^ (y * 19349663) ^ (z * 83492791);
        }

        @Override
        public boolean equals(Object object) {
            if (!(object instanceof Cell)) {
                return false;
            }
            Cell other = (Cell) object;
            return x == other.x && y == other.y && z == other.z;
        }
    }
}
