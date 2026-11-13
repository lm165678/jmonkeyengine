package com.jme3.scene.plugins.sdf;

import com.jme3.math.Matrix4f;
import com.jme3.math.Transform;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import com.jme3.texture.Texture3D;
import com.jme3.texture.image.ColorSpace;
import com.jme3.util.BufferUtils;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.BitSet;
import com.jme3.renderer.Renderer;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Camera-centered, power-of-two clipmaps composed from baked model distance fields.
 * This baseline uses synchronous CPU composition and renderer-managed R32F uploads;
 * it is not a GPU compositor or a certified DDGI intersection implementation.
 * Static frames do not resample/upload. Scrolling reuses overlapping grid samples;
 * moved/removed instances invalidate both old and new narrow-band bounds.
 * Static union samples are cached separately. An instance observed changing is
 * promoted to the dynamic union for its tracked lifetime. Promotion/removal repairs
 * the old static region; scrolling preserves both caches. This adds two CPU float
 * grids per level (no additional GPU texture) and avoids resampling static assets
 * every time a nearby dynamic object moves.
 * Renderer-aware updates upload changed bricks; scrolling/allocation uses full uploads.
 *
 * <p>Each level stores a truncated union estimate in world units. Source interpolation
 * uncertainty is not subtracted from stored samples: a marching safety margin must
 * never change the represented surface. Unsigned sources remain unsigned, not a
 * closed solid. Nonuniform/mirrored TRS scales use the smallest absolute scale
 * as a distance scale, not an exact Euclidean distance. Global interpolation
 * adds further uncertainty. Consumers must refine hits against object data; zero is
 * not proof of a triangle intersection. No material or surface pixels are uploaded.</p>
 *
 * <pre>{@code
 * // 第一步：创建固定预算的三级缓存；64 是每轴格点数，0.25 是最近级格距（世界单位）。
 * GlobalDistanceField global = new GlobalDistanceField(64, 3, 0.25f);
 * // 第二步：正常加载/克隆带 SdfVolume userdata 的模型，设置各自平移、旋转、缩放。
 * // 不需要手动注册；没有 SDF 的节点跳过。带 SDF 的根覆盖其子树，避免重复加入。
 * // 自动空间索引仅查询覆盖 clipmap 的实例，体素按 8x8x8 分块筛选模型；仍是 CPU 合并。
 * rootNode.updateGeometricState();
 * // 第三步：每帧在场景变换更新之后、绘制之前调用；同一场景/渲染线程，不支持并发。
 * global.update(rootNode, cam.getLocation());
 * // 第四步：将各级纹理、min、cellSize 交给材质，选择包含查询点的最精细级。
 * Texture3D nearField = global.getTexture(0);
 * Vector3f min = global.getMin(0);
 * float cellSize = global.getCellSize(0);
 * // 格点 i 的纹理坐标是 (i+0.5)/resolution，不是 i/(resolution-1)。
 * // 第五步：删除/移动模型或替换 userdata 后再 update，旧区域会重算，不会留下残影。
 * // 模型内部几何改变必须先重烘焙；本类不逐帧计算几何哈希。CullHint 不控制是否入场。
 * // 第六步：移除所有纹理消费者，再在渲染线程 global.close()；资产内 SDF 仍保留。
 * }</pre>
 *
 * <p>Memory is bounded by resolution/cascade count, not world size. Instances are
 * indexed from scene notifications; changed voxels use 8-cubed spatial bins. Large production
 * scenes still need sparse GPU composition and budgeted streaming.
 * Coordinates must remain in a float-precision-safe range (use origin rebasing).
 * Distances are clamped at six cells per level; space outside the last level is unknown.</p>
 */
public final class GlobalDistanceField implements AutoCloseable {
    private SurfaceSceneIndex sceneIndex;
    private Spatial indexedRoot;
    private final int resolution;
    private final Level[] levels;
    private Map<Spatial, Instance> instances = new IdentityHashMap<>();
    private int updatedVoxelCount;
    private long uploadedBytes;
    private long indexNanos;
    private long compositionNanos;
    private long uploadNanos;
    private final ByteBuffer uploadBlock = BufferUtils.createByteBuffer(8 * 8 * 8 * 4);
    private boolean closed;

    /**
     * Creates CPU/GPU staging storage. GPU allocation occurs on first texture use.
     * @param resolution grid points per axis, 8..128
     * @param cascades number of levels, 1..6; each doubles cell size
     * @param finestCellSize finite positive world-space spacing
     */
    public GlobalDistanceField(int resolution, int cascades, float finestCellSize) {
        if (resolution < 8 || resolution > 128 || cascades < 1 || cascades > 6
                || !Float.isFinite(finestCellSize) || finestCellSize <= 0
                || !Float.isFinite(finestCellSize * (1 << cascades) * resolution)) {
            throw new IllegalArgumentException("Invalid clipmap dimensions/cell size");
        }
        this.resolution = resolution;
        levels = new Level[cascades];
        for (int i = 0; i < cascades; i++) {
            levels[i] = new Level(finestCellSize * (1 << i), resolution);
        }
    }

    /**
     * Discovers baked roots, snapshots world transforms and updates dirty grid regions.
     * Call after root.updateGeometricState(), before rendering. No baking is triggered.
     * A baked ancestor is authoritative; descendants are not registered a second time.
     * @param root scene subtree to scan, including objects hidden by preview CullHint
     * @param camera world-space clipmap center
     */
    public void update(Spatial root, Vector3f camera) {
        update(root, camera, null);
    }

    /**
     * Updates dirty samples and uploads only changed 8-cubed bricks when the
     * camera grid is stationary. Initial allocation, scrolling, context restoration
     * and unsupported backends use full uploads. The two-argument overload retains
     * deferred full uploads for compatibility. Call on the render thread after
     * geometric state updates, before any consumer draws.
     * <pre>{@code
     * // 第一步：创建缓存一次，加载带 SDF 的模型；无需手动注册动态物体。
     * GlobalDistanceField field = new GlobalDistanceField(64, 3, 0.5f);
     * // 第二步：每帧修改少量实例，然后先更新世界变换。
     * movingModel.move(0.01f, 0, 0);
     * rootNode.updateGeometricState();
     * // 模型首次移动自动转入动态集合；删除/替换也会修复静态缓存，无需声明静态标志。
     * // 每级额外两个 CPU float 网格用于静态结果和滚动暂存；GPU 显存预算不变。
     * // 第三步：传入 renderer 启用局部上传；旧 update(root, camera) 保持兼容。
     * field.update(rootNode, cam.getLocation(), renderer);
     * // 第四步：统计本次上传字节。静态帧为零；首次分配和相机格点移动可为整级。
     * long bytes = field.getUploadedBytes();
     * double indexMs = field.getIndexNanos() / 1e6;
     * double composeMs = field.getCompositionNanos() / 1e6;
     * double submitMs = field.getUploadNanos() / 1e6; // CPU 提交时间，不是 GPU 执行时间。
     * // 第五步：卸载前移除材质中的纹理引用，再在渲染线程 field.close()。
     * }</pre>
     * @param root scene subtree
     * @param camera world-space camera position
     * @param renderer renderer for immediate region uploads, or null for deferred uploads
     */
    public void update(Spatial root, Vector3f camera, Renderer renderer) {
        checkOpen();
        if (!Vector3f.isValidVector(camera)) {
            throw new IllegalArgumentException("Invalid camera position");
        }
        long indexStart = System.nanoTime();
        Map<Spatial, Instance> next = new IdentityHashMap<>();
        List<Instance> dirty = new ArrayList<>();
        if (sceneIndex == null || indexedRoot != root) {
            if (sceneIndex != null) {
                sceneIndex.close();
            }
            sceneIndex = new SurfaceSceneIndex(root, levels[levels.length - 1].cell * resolution / 4,
                    SdfVolume.USER_DATA_KEY);
            indexedRoot = root;
        } else {
            sceneIndex.synchronize();
        }
        float radius = (float) Math.sqrt(3) * (resolution / 2f + 8) * levels[levels.length - 1].cell;
        for (Spatial candidate : sceneIndex.query(camera, radius)) {
            discover(candidate, next, dirty);
        }
        for (Map.Entry<Spatial, Instance> entry : instances.entrySet()) {
            if (!next.containsKey(entry.getKey())) {
                dirty.add(entry.getValue());
            }
        }
        instances = next;
        updatedVoxelCount = 0;
        uploadedBytes = 0;
        uploadNanos = 0;
        indexNanos = System.nanoTime() - indexStart;
        long compositionStart = System.nanoTime();
        for (Level level : levels) {
            updateLevel(level, camera, dirty, renderer);
            if (renderer != null && (level.texture.getImage().getId() < 0
                    || level.texture.getImage().isUpdateNeeded())) {
                long uploadStart = System.nanoTime();
                try {
                    renderer.setTexture(0, level.texture);
                } catch (com.jme3.renderer.TextureUnitException exception) {
                    throw new com.jme3.renderer.RendererException(exception);
                }
                uploadNanos += System.nanoTime() - uploadStart;
                uploadedBytes += (long) level.values.length * 4;
            }
        }
        compositionNanos = System.nanoTime() - compositionStart - uploadNanos;
    }

    private void discover(Spatial spatial, Map<Spatial, Instance> next, List<Instance> dirty) {
        SdfVolume volume = spatial.getUserData(SdfVolume.USER_DATA_KEY);
        if (volume != null) {
            Instance old = instances.get(spatial);
            if (old != null && old.volume == volume && old.transform.equals(spatial.getWorldTransform())) {
                next.put(spatial, old);
            } else {
                Instance replacement = new Instance(volume, spatial.getWorldTransform());
                // Once observed changing, keep this tracked instance in the dynamic union.
                replacement.dynamic = old != null;
                next.put(spatial, replacement);
                dirty.add(replacement);
                if (old != null) {
                    dirty.add(old);
                }
            }
            return;
        }
        if (spatial instanceof Node) {
            for (Spatial child : ((Node) spatial).getChildren()) {
                discover(child, next, dirty);
            }
        }
    }

    private void updateLevel(Level level, Vector3f camera, List<Instance> dirty, Renderer renderer) {
        Vector3f min = new Vector3f();
        for (int axis = 0; axis < 3; axis++) {
            min.set(axis, ((float) Math.floor(camera.get(axis) / level.cell) - resolution / 2) * level.cell);
        }
        boolean moved = !min.equals(level.min);
        if (level.initialized && !moved && dirty.isEmpty()) {
            return;
        }
        int ox = Math.round((min.x - level.min.x) / level.cell);
        int oy = Math.round((min.y - level.min.y) / level.cell);
        int oz = Math.round((min.z - level.min.z) / level.cell);
        float band = level.cell * 6;
        List<Instance> candidates = new ArrayList<>();
        Vector3f max = min.add(new Vector3f(1, 1, 1).multLocal((resolution - 1) * level.cell));
        List<Instance> localDirty = new ArrayList<>();
        for (Instance instance : dirty) {
            if (instance.overlaps(min, max, band)) {
                localDirty.add(instance);
            }
        }
        if (level.initialized && !moved && localDirty.isEmpty()) {
            return;
        }
        for (Instance instance : instances.values()) {
            if (instance.overlaps(min, max, band)) {
                candidates.add(instance);
            }
        }
        int tiles = (resolution + 7) / 8;
        @SuppressWarnings("unchecked")
        List<Instance>[] bins = (List<Instance>[]) new List<?>[tiles * tiles * tiles];
        for (Instance instance : candidates) {
            int x0 = Math.max(0, (int) Math.floor((instance.worldMin.x - band - min.x) / (level.cell * 8)));
            int y0 = Math.max(0, (int) Math.floor((instance.worldMin.y - band - min.y) / (level.cell * 8)));
            int z0 = Math.max(0, (int) Math.floor((instance.worldMin.z - band - min.z) / (level.cell * 8)));
            int x1 = Math.min(tiles - 1, (int) Math.floor((instance.worldMax.x + band - min.x) / (level.cell * 8)));
            int y1 = Math.min(tiles - 1, (int) Math.floor((instance.worldMax.y + band - min.y) / (level.cell * 8)));
            int z1 = Math.min(tiles - 1, (int) Math.floor((instance.worldMax.z + band - min.z) / (level.cell * 8)));
            for (int z = z0; z <= z1; z++) {
                for (int y = y0; y <= y1; y++) {
                    for (int x = x0; x <= x1; x++) {
                        int bucket = x + tiles * (y + tiles * z);
                        if (bins[bucket] == null) {
                            bins[bucket] = new ArrayList<>();
                        }
                        bins[bucket].add(instance);
                    }
                }
            }
        }
        if (level.initialized && !moved) {
            updateDirtyBricks(level, min, localDirty, bins, tiles, band, renderer);
            return;
        }
        Vector3f point = new Vector3f();
        Vector3f local = new Vector3f();
        int changed = 0;
        for (int z = 0; z < resolution; z++) {
            for (int y = 0; y < resolution; y++) {
                for (int x = 0; x < resolution; x++) {
                    point.set(min.x + x * level.cell, min.y + y * level.cell, min.z + z * level.cell);
                    int oldX = x + ox;
                    int oldY = y + oy;
                    int oldZ = z + oz;
                    boolean invalid = !level.initialized || oldX < 0 || oldY < 0 || oldZ < 0
                            || oldX >= resolution || oldY >= resolution || oldZ >= resolution;
                    if (!invalid) {
                        for (Instance instance : localDirty) {
                            if (instance.contains(point, band)) {
                                invalid = true;
                                break;
                            }
                        }
                    }
                    int index = x + resolution * (y + resolution * z);
                    if (invalid) {
                        float distance = band;
                        float staticDistance = band;
                        List<Instance> bucket = bins[x / 8 + tiles * (y / 8 + tiles * (z / 8))];
                        if (bucket != null) {
                            for (Instance instance : bucket) {
                                if (instance.contains(point, band)) {
                                    float sample = instance.sample(point, local);
                                    distance = Math.min(distance, sample);
                                    if (!instance.dynamic) {
                                        staticDistance = Math.min(staticDistance, sample);
                                    }
                                }
                            }
                        }
                        level.scratch[index] = Math.max(-band, distance);
                        level.staticScratch[index] = Math.max(-band, staticDistance);
                        changed++;
                    } else {
                        int oldIndex = oldX + resolution * (oldY + resolution * oldZ);
                        level.scratch[index] = level.values[oldIndex];
                        level.staticScratch[index] = level.staticValues[oldIndex];
                    }
                }
            }
        }
        if (changed != 0 || moved) {
            float[] swap = level.values;
            level.values = level.scratch;
            level.scratch = swap;
            swap = level.staticValues;
            level.staticValues = level.staticScratch;
            level.staticScratch = swap;
            level.bytes.asFloatBuffer().put(level.values);
            level.texture.getImage().setUpdateNeeded();
        }
        level.min.set(min);
        level.initialized = true;
        updatedVoxelCount += changed;
    }

    private void updateDirtyBricks(Level level, Vector3f min, List<Instance> dirty,
            List<Instance>[] bins, int tiles, float band, Renderer renderer) {
        // Mark exact affected rows directly. No full-grid scan, no per-voxel dirty-object loop.
        BitSet voxels = level.dirtyVoxels;
        BitSet bricks = level.dirtyBricks;
        voxels.clear();
        bricks.clear();
        BitSet staticDirty = level.staticDirty;
        staticDirty.clear();
        for (Instance instance : dirty) {
            int x0 = Math.max(0, (int) Math.ceil((instance.worldMin.x - band - min.x) / level.cell));
            int y0 = Math.max(0, (int) Math.ceil((instance.worldMin.y - band - min.y) / level.cell));
            int z0 = Math.max(0, (int) Math.ceil((instance.worldMin.z - band - min.z) / level.cell));
            int x1 = Math.min(resolution - 1, (int) Math.floor((instance.worldMax.x + band - min.x) / level.cell));
            int y1 = Math.min(resolution - 1, (int) Math.floor((instance.worldMax.y + band - min.y) / level.cell));
            int z1 = Math.min(resolution - 1, (int) Math.floor((instance.worldMax.z + band - min.z) / level.cell));
            if (x0 > x1) {
                continue;
            }
            for (int z = z0; z <= z1; z++) {
                for (int y = y0; y <= y1; y++) {
                    int row = resolution * (y + resolution * z);
                    voxels.set(row + x0, row + x1 + 1);
                    if (!instance.dynamic) {
                        staticDirty.set(row + x0, row + x1 + 1);
                    }
                }
            }
        }
        Vector3f point = new Vector3f();
        Vector3f local = new Vector3f();
        for (int index = voxels.nextSetBit(0); index >= 0; index = voxels.nextSetBit(index + 1)) {
            int x = index % resolution;
            int y = index / resolution % resolution;
            int z = index / (resolution * resolution);
            point.set(min.x + x * level.cell, min.y + y * level.cell, min.z + z * level.cell);
            int brick = x / 8 + tiles * (y / 8 + tiles * (z / 8));
            float staticDistance = level.staticValues[index];
            boolean rebuildStatic = staticDirty.get(index);
            if (rebuildStatic) {
                staticDistance = band;
            }
            float dynamicDistance = band;
            List<Instance> candidates = bins[brick];
            if (candidates != null) {
                for (Instance instance : candidates) {
                    if ((instance.dynamic || rebuildStatic) && instance.contains(point, band)) {
                        float sample = instance.sample(point, local);
                        if (instance.dynamic) {
                            dynamicDistance = Math.min(dynamicDistance, sample);
                        } else {
                            staticDistance = Math.min(staticDistance, sample);
                        }
                    }
                }
            }
            level.staticValues[index] = Math.max(-band, staticDistance);
            float distance = Math.min(staticDistance, dynamicDistance);
            level.values[index] = Math.max(-band, distance);
            level.bytes.putFloat(index * 4, level.values[index]);
            bricks.set(brick);
        }
        updatedVoxelCount += voxels.cardinality();
        if (voxels.isEmpty()) {
            return;
        }
        Image image = level.texture.getImage();
        if (renderer == null || image.getId() < 0 || image.isUpdateNeeded()) {
            image.setUpdateNeeded();
            return;
        }
        try {
            for (int brick = bricks.nextSetBit(0); brick >= 0; brick = bricks.nextSetBit(brick + 1)) {
                int x0 = brick % tiles * 8;
                int y0 = brick / tiles % tiles * 8;
                int z0 = brick / (tiles * tiles) * 8;
                int width = Math.min(8, resolution - x0);
                int height = Math.min(8, resolution - y0);
                int depth = Math.min(8, resolution - z0);
                uploadBlock.clear();
                for (int z = z0; z < z0 + depth; z++) {
                    for (int y = y0; y < y0 + height; y++) {
                        for (int x = x0; x < x0 + width; x++) {
                            uploadBlock.putFloat(level.values[x + resolution * (y + resolution * z)]);
                        }
                    }
                }
                uploadBlock.flip();
                long uploadStart = System.nanoTime();
                renderer.updateTexture3DRegion(level.texture, x0, y0, z0, width, height, depth, uploadBlock);
                uploadNanos += System.nanoTime() - uploadStart;
                uploadedBytes += (long) width * height * depth * 4;
            }
        } catch (UnsupportedOperationException unsupported) {
            image.setUpdateNeeded();
        } catch (RuntimeException failure) {
            // CPU payload remains authoritative; retry/restoration must not retain a partial GPU update.
            image.setUpdateNeeded();
            throw failure;
        }
    }

    /**
     * @return bytes submitted during the last renderer-aware update, including
     * full allocation/restoration; zero for deferred uploads and unchanged frames
     */
    public long getUploadedBytes() {
        return uploadedBytes;
    }

    /** @return CPU nanoseconds for scene-index synchronization and candidate snapshots in the last update */
    public long getIndexNanos() {
        return indexNanos;
    }

    /** @return CPU nanoseconds for composition/packing, excluding renderer calls, in the last update */
    public long getCompositionNanos() {
        return compositionNanos;
    }

    /** @return CPU nanoseconds inside upload calls, not asynchronous GPU execution time */
    public long getUploadNanos() {
        return uploadNanos;
    }

    /**
     * Returns baked instances selected by the spatial query around the clipmaps.
     * This includes conservative candidates, not every baked instance in the scene.
     *
     * @return nearby candidate count after the last update
     */
    public int getInstanceCount() {
        return instances.size();
    }

    /** @return resampled voxels in the last update; zero on unchanged frames */
    public int getUpdatedVoxelCount() {
        return updatedVoxelCount;
    }

    /** @return fixed axis resolution for all levels */
    public int getResolution() {
        return resolution;
    }

    /** @return number of camera-centered levels */
    public int getCascadeCount() {
        return levels.length;
    }

    /** @return R32F GPU payload bytes, excluding CPU staging and model assets */
    public long getTextureBytes() {
        return (long) levels.length * resolution * resolution * resolution * 4;
    }

    /** @return world spacing for the requested zero-based level */
    public float getCellSize(int level) {
        return levels[level].cell;
    }

    /** @return first sample's world position; call update before use */
    public Vector3f getMin(int level) {
        return levels[level].min.clone();
    }

    /** @return owned texture; consumers must not dispose it or retain it after close */
    public Texture3D getTexture(int level) {
        checkOpen();
        return levels[level].texture;
    }

    /** @return raw grid estimate for diagnostics/tests, not a refined ray hit */
    public float getDistance(int level, int x, int y, int z) {
        checkOpen();
        if (x < 0 || y < 0 || z < 0 || x >= resolution || y >= resolution || z >= resolution) {
            throw new IndexOutOfBoundsException("Clipmap coordinate out of range");
        }
        if (!levels[level].initialized) {
            throw new IllegalStateException("Call update first");
        }
        return levels[level].values[x + resolution * (y + resolution * z)];
    }

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("Global distance field is closed");
        }
    }

    /** Release owned images on the render thread after removing all consumers. Idempotent. */
    @Override
    public void close() {
        if (!closed) {
            for (Level level : levels) {
                level.texture.getImage().dispose();
            }
            instances.clear();
            if (sceneIndex != null) {
                sceneIndex.close();
                sceneIndex = null;
                indexedRoot = null;
            }
            closed = true;
        }
    }

    private static final class Level {
        final float cell;
        final Vector3f min = new Vector3f();
        final ByteBuffer bytes;
        final Texture3D texture;
        float[] values;
        float[] scratch;
        float[] staticValues;
        float[] staticScratch;
        final BitSet staticDirty = new BitSet();
        boolean initialized;
        final BitSet dirtyVoxels = new BitSet();
        final BitSet dirtyBricks = new BitSet();

        Level(float cell, int resolution) {
            this.cell = cell;
            values = new float[resolution * resolution * resolution];
            scratch = new float[values.length];
            staticValues = new float[values.length];
            staticScratch = new float[values.length];
            bytes = BufferUtils.createByteBuffer(values.length * 4);
            ArrayList<ByteBuffer> data = new ArrayList<>();
            data.add(bytes);
            texture = new Texture3D(new Image(Image.Format.R32F, resolution, resolution, resolution, data, ColorSpace.Linear));
            texture.setMinFilter(Texture.MinFilter.BilinearNoMipMaps);
            texture.setMagFilter(Texture.MagFilter.Bilinear);
            texture.setWrap(Texture.WrapMode.EdgeClamp);
        }
    }

    private static final class Instance {
        final SdfVolume volume;
        final Transform transform;
        final Matrix4f inverse;
        final Vector3f min;
        final Vector3f max;
        final Vector3f worldMin = new Vector3f(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY);
        final Vector3f worldMax = new Vector3f(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY);
        final float scale;
        boolean dynamic;

        Instance(SdfVolume volume, Transform transform) {
            this.volume = volume;
            this.transform = transform.clone();
            Vector3f s = transform.getScale();
            scale = Math.min(Math.abs(s.x), Math.min(Math.abs(s.y), Math.abs(s.z)));
            if (!Vector3f.isValidVector(s) || scale < 1e-8f) {
                throw new IllegalArgumentException("SDF instances require finite, nonzero scale");
            }
            Matrix4f matrix = transform.toTransformMatrix();
            inverse = matrix.invert();
            min = volume.getMin();
            max = volume.getMax();
            for (int corner = 0; corner < 8; corner++) {
                Vector3f p = matrix.mult(new Vector3f((corner & 1) == 0 ? min.x : max.x,
                        (corner & 2) == 0 ? min.y : max.y, (corner & 4) == 0 ? min.z : max.z));
                if (!Vector3f.isValidVector(p)) {
                    throw new IllegalArgumentException("Non-finite SDF instance bounds");
                }
                worldMin.minLocal(p);
                worldMax.maxLocal(p);
            }
        }

        boolean overlaps(Vector3f a, Vector3f b, float padding) {
            return worldMin.x - padding <= b.x && worldMax.x + padding >= a.x
                    && worldMin.y - padding <= b.y && worldMax.y + padding >= a.y
                    && worldMin.z - padding <= b.z && worldMax.z + padding >= a.z;
        }

        boolean contains(Vector3f p, float padding) {
            return overlaps(p, p, padding);
        }

        float sample(Vector3f world, Vector3f local) {
            inverse.mult(world, local);
            float x = Math.max(min.x, Math.min(max.x, local.x));
            float y = Math.max(min.y, Math.min(max.y, local.y));
            float z = Math.max(min.z, Math.min(max.z, local.z));
            float dx = local.x - x;
            float dy = local.y - y;
            float dz = local.z - z;
            float outside = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            local.set(x, y, z);
            float sampled = volume.sample(local);
            // Extend continuously with a positive estimate outside the source volume.
            // A distance LOWER BOUND is suitable for cautious stepping, but not for
            // surface reconstruction: it can create false near-zero sheets at bounds.
            // This extension is approximate and must not be used as a safe ray step.
            if (outside > 0) {
                sampled = Math.max(0, sampled) + outside;
            }
            return sampled * scale;
        }
    }
}
