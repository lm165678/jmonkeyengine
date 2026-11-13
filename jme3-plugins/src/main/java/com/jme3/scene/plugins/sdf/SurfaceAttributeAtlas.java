package com.jme3.scene.plugins.sdf;

import com.jme3.math.FastMath;
import com.jme3.math.Matrix4f;
import com.jme3.math.Vector3f;
import com.jme3.math.Vector4f;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import com.jme3.texture.Texture2D;
import com.jme3.texture.image.ColorSpace;
import com.jme3.util.BufferUtils;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bounded camera-distance-prioritized residency for saved surface attribute cards.
 * Four shared 2D atlases: diffuse/normal/emission RGBA16F, normalized depth R32F.
 * Source dimensions may differ between assets; exact-size rectangular blocks contain
 * original-resolution pixels plus a one-texel replicated gutter. No resampling,
 * mipmaps, baking, framebuffer readback or per-instance attribute texture sets.
 * HDR values outside half-float range saturate at +/-65504; CPU saved data is unchanged.
 *
 * <p>This is whole-card streaming, not virtual texturing. The binary rectangle allocator
 * coalesces free siblings; fragmentation may waste space. Nearer assets have
 * priority; a large asset may be only partially resident. Stable fully resident
 * request order skips allocation/eviction work even when instance distances change. Missing/oversized cards
 * return null, never an unrelated stale region. Event-maintained spatial queries and allocation are
 * CPU-side; use the renderer overload for dirty-rectangle uploads. No GPU feedback,
 * automatic resolution LOD or GPU instance table is implemented here.</p>
 *
 * <pre>{@code
 * // 第一步：加载时验证 SurfaceBakeData 与模型/材质版本一致；不在每帧计算哈希。
 * SurfaceBakeData data = model.getUserData(SurfaceBakeData.USER_DATA_KEY);
 * // 相同资产的克隆显式共享同一 data 对象；独立加载的相同内容不会自动哈希去重。
 * // 刚体移动仍调用 update；卡片全部驻留且优先级顺序未变时自动跳过分配/淘汰。
 * clone.setUserData(SurfaceBakeData.USER_DATA_KEY, data);
 * // 第二步：创建固定容量图集。1024 边长合计 28 MiB GPU + 28 MiB CPU 暂存。
 * // 半径 50 世界单位，每次 update 最多迁入 4 张卡片；不分配无限层纹理数组。
 * SurfaceAttributeAtlas atlas = new SurfaceAttributeAtlas(1024, 50f, 4);
 * // 第三步：每帧世界变换更新后、绘制前在场景/渲染线程调用，不可并发。
 * // 首次扫描场景并注册根监听；后续只维护变更分支，通过空间哈希查询相机附近实例。
 * // 使用正常场景 setter/attach/detach/setUserData 自动通知；关闭图集时自动解除监听。
 * rootNode.updateGeometricState();
 * atlas.update(rootNode, cam.getLocation(), renderer);
 * // 兼容的双参数 update 仍只操作 CPU，后续整图上传；三参数重载才走局部上传。
 * // 可设置 atlas.setUploadByteBudget(256 * 1024) 限制每次迁入像素字节。
 * // 第四步：所有实例使用同一组纹理，只需为命中卡片查询区域和投影矩阵。
 * SurfaceAttributeAtlas.Region region = atlas.getRegion(model, 4);
 * if (region != null) {
 *     material.setTexture("AttributeAtlas", atlas.getTexture(SurfaceAttributeCache.Channel.DIFFUSE));
 *     material.setTexture("DepthAtlas", atlas.getDepthTexture());
 *     material.setVector4("AtlasRect", region.getUvTransform());
 *     material.setMatrix4("WorldToCard", atlas.getWorldToCard(model, 4));
 * }
 * // shader: atlasUV = cardUV * AtlasRect.zw + AtlasRect.xy。
 * // 必须先校验卡片 UV 范围，再用映射后的 depth 校验覆盖，最后读取属性。
 * // 法线是 Geometry 局部法线，使用 getGeometryToWorld(model, card) 的逆转置转换。
 * // 第五步：region==null 就明确回退/跳过，不能沿用上一帧的槽位。
 * // 每次 update 后重新查询；旧 Region.isResident() 可检测淘汰，槽位会被复用。
 * // 第六步：材质改变后使旧 data.invalidate() 并重新烘焙/挂载；移动只更新变换。
 * // 第七步：移除所有消费者后 atlas.close()，模型内原始 CPU 属性仍然保留。
 * }</pre>
 */
public final class SurfaceAttributeAtlas implements AutoCloseable {
    private SurfaceSceneIndex sceneIndex;
    private Spatial indexedRoot;
    private final int size;
    private final float radius;
    private final int uploadBudget;
    private final Block allocator;
    private final Texture2D[] textures = new Texture2D[4];
    private final ByteBuffer[] pixels = new ByteBuffer[4];
    private final Map<Key, Region> residents = new LinkedHashMap<>();
    private Map<Spatial, SurfaceBakeData> instances = new IdentityHashMap<>();
    private int uploaded;
    private int evicted;
    private int missing;
    private boolean closed;
    private boolean partialUpload;
    private float retentionMargin;
    private List<Asset> previousAssets = new ArrayList<>();
    private Map<Key, Integer> cachedPriorities = new LinkedHashMap<>();
    private long byteBudget = Long.MAX_VALUE;
    private long uploadedBytes;
    private long lastTransferBytes;
    private final List<Block> dirty = new ArrayList<>();

    /**
     * @param size power-of-two atlas edge, 32..2048; largest supported card is size-2
     * @param radius finite positive camera-to-world-bound residency radius
     * @param uploadBudget maximum new cards copied per update, positive
     */
    public SurfaceAttributeAtlas(int size, float radius, int uploadBudget) {
        if (size < 32 || size > 2048 || (size & (size - 1)) != 0
                || !Float.isFinite(radius) || radius <= 0 || uploadBudget < 1) {
            throw new IllegalArgumentException("Invalid surface atlas budget");
        }
        this.size = size;
        this.radius = radius;
        this.uploadBudget = uploadBudget;
        allocator = new Block(0, 0, size, size);
        for (int channel = 0; channel < 4; channel++) {
            boolean depth = channel == 3;
            pixels[channel] = BufferUtils.createByteBuffer(size * size * (depth ? 4 : 8));
            if (depth) {
                for (int i = 0; i < size * size; i++) {
                    pixels[channel].putFloat(i * 4, 1);
                }
            }
            textures[channel] = new Texture2D(new Image(depth ? Image.Format.R32F : Image.Format.RGBA16F,
                    size, size, pixels[channel], ColorSpace.Linear));
            textures[channel].setMinFilter(Texture.MinFilter.NearestNoMipMaps);
            textures[channel].setMagFilter(Texture.MagFilter.Nearest);
            textures[channel].setWrap(Texture.WrapMode.EdgeClamp);
        }
    }

    /**
     * Reconciles camera-local residency after scene transforms have been updated.
     * Static frames copy zero cards. Root nodes with surface data own their subtree;
     * SDF presence is irrelevant. Explicitly invalidated assets are skipped.
     * @param root scene subtree
     * @param camera finite world position
     */
    public void update(Spatial root, Vector3f camera) {
        checkOpen();
        if (!Vector3f.isValidVector(camera)) {
            throw new IllegalArgumentException("Invalid camera position");
        }
        uploaded = 0;
        lastTransferBytes = 0;
        uploadedBytes = 0;
        dirty.clear();
        evicted = 0;
        Map<Spatial, SurfaceBakeData> next = new IdentityHashMap<>();
        List<Asset> assets = new ArrayList<>();
        if (sceneIndex == null || indexedRoot != root) {
            if (sceneIndex != null) {
                sceneIndex.close();
            }
            sceneIndex = new SurfaceSceneIndex(root, radius);
            indexedRoot = root;
        } else {
            sceneIndex.synchronize();
        }
        Map<SurfaceBakeData, Asset> unique = new IdentityHashMap<>();
        for (Spatial nearby : sceneIndex.query(camera, radius + retentionMargin)) {
            collect(nearby, camera, next, unique, assets);
        }
        instances = next;
        assets.sort(Comparator.comparingDouble(asset -> asset.distance));
        boolean unchanged = assets.size() == previousAssets.size();
        for (int i = 0; unchanged && i < assets.size(); i++) {
            unchanged = assets.get(i).data == previousAssets.get(i).data;
        }
        if (!unchanged) {
            previousAssets = new ArrayList<>(assets);
            cachedPriorities = new LinkedHashMap<>();
            for (Asset asset : assets) {
                for (int card = 0; card < asset.data.getAttributes().getCardCount(); card++) {
                    cachedPriorities.put(new Key(asset.data, card), cachedPriorities.size());
                }
            }
        }
        // Distances may change every frame without changing card selection/order.
        // Fully resident, unchanged requests need no allocator, eviction or key work.
        if (unchanged && missing == 0) {
            return;
        }
        Map<Key, Integer> priorities = cachedPriorities;
        for (Region region : new ArrayList<>(residents.values())) {
            if (!priorities.containsKey(region.key)) {
                release(region);
            }
        }
        for (Map.Entry<Key, Integer> request : priorities.entrySet()) {
            Key key = request.getKey();
            int resolution = key.data.getAttributes().getResolution();
            long bytes = (long) (resolution + 2) * (resolution + 2) * 28;
            if (residents.containsKey(key) || uploaded >= uploadBudget || resolution + 2 > size
                    || bytes > byteBudget - uploadedBytes) {
                continue;
            }
            Block block = allocator.allocate(resolution + 2);
            while (block == null) {
                Region victim = null;
                int worst = request.getValue();
                for (Region candidate : residents.values()) {
                    int priority = priorities.get(candidate.key);
                    if (priority > worst) {
                        victim = candidate;
                        worst = priority;
                    }
                }
                if (victim == null) {
                    break;
                }
                release(victim);
                block = allocator.allocate(resolution + 2);
            }
            if (block != null) {
                Region region = new Region(key, block, resolution);
                copy(region);
                dirty.add(block);
                uploadedBytes += bytes;
                residents.put(key, region);
                uploaded++;
            }
        }
        missing = priorities.size() - residents.size();
        if (!partialUpload && (uploaded != 0 || evicted != 0)) {
            for (Texture2D texture : textures) {
                texture.getImage().setUpdateNeeded();
            }
        }
    }

    /**
     * Update residency and upload only dirty rectangles before drawing, on the render thread.
     * First allocation/context recovery uploads the complete staging image. Unsupported
     * backends fall back to full uploads. Stable frames perform no texture operations.
     * CPU staging remains authoritative, so context recreation retains all resident pixels.
     * @param root scene root
     * @param camera world camera position
     * @param renderer current renderer, not null
     */
    public void update(Spatial root, Vector3f camera, com.jme3.renderer.Renderer renderer) {
        java.util.Objects.requireNonNull(renderer, "renderer");
        partialUpload = true;
        try {
            update(root, camera);
        } finally {
            partialUpload = false;
        }
        lastTransferBytes = 0;
        try {
            for (int channel = 0; channel < 4; channel++) {
                Texture2D texture = textures[channel];
                Image image = texture.getImage();
                int stride = channel == 3 ? 4 : 8;
                if (image.getId() < 0 || image.isUpdateNeeded()) {
                    try {
                        renderer.setTexture(0, texture);
                    } catch (com.jme3.renderer.TextureUnitException exception) {
                        throw new com.jme3.renderer.RendererException(exception);
                    }
                    lastTransferBytes += (long) size * size * stride;
                } else {
                    try {
                        for (Block rect : dirty) {
                            renderer.modifyTexture(texture, image, rect.x, rect.y, rect.x, rect.y,
                                    rect.width, rect.height);
                            lastTransferBytes += (long) rect.width * rect.height * stride;
                        }
                    } catch (UnsupportedOperationException unsupported) {
                        image.setUpdateNeeded();
                        try {
                            renderer.setTexture(0, texture);
                        } catch (com.jme3.renderer.TextureUnitException exception) {
                            throw new com.jme3.renderer.RendererException(exception);
                        }
                        lastTransferBytes += (long) size * size * stride;
                    }
                }
            }
        } catch (RuntimeException | Error failure) {
            // A failed partial upload must not leave a silently stale GPU copy.
            for (Texture2D texture : textures) {
                texture.getImage().setUpdateNeeded();
            }
            throw failure;
        }
        dirty.clear();
    }

    /**
     * Limit new card pixels copied per update, including gutter and all channels.
     * Default is unlimited in bytes (the constructor's card-count limit still applies).
     * A card larger than this budget remains missing; increase budget to admit it.
     * Eviction clearing and initial full GPU allocation are not charged to this limit.
     * @param bytes positive byte budget
     */
    public void setUploadByteBudget(long bytes) {
        if (bytes < 1) {
            throw new IllegalArgumentException("Positive byte budget required");
        }
        byteBudget = bytes;
    }

    /** @return bytes submitted by the latest renderer-overload update, excluding driver overhead */
    public long getLastTransferBytes() {
        return lastTransferBytes;
    }

    /**
     * Keep already tracked instances until radius+margin; new instances still enter at radius.
     * Default zero preserves strict radius behavior. Invalidated/removed assets are never retained.
     * <pre>{@code
     * // 相机边缘小幅往返时保留两米缓冲，避免同一卡片反复迁入迁出。
     * atlas.setRetentionMargin(2f);
     * }</pre>
     * @param margin finite nonnegative world-space exit margin
     */
    public void setRetentionMargin(float margin) {
        if (!Float.isFinite(margin) || margin < 0) {
            throw new IllegalArgumentException("Invalid retention margin");
        }
        retentionMargin = margin;
    }

    private void collect(Spatial spatial, Vector3f camera, Map<Spatial, SurfaceBakeData> next,
            Map<SurfaceBakeData, Asset> unique, List<Asset> assets) {
        SurfaceBakeData data = spatial.getUserData(SurfaceBakeData.USER_DATA_KEY);
        if (data != null) {
            float distance = spatial.getWorldBound() == null ? spatial.getWorldTranslation().distance(camera)
                    : Math.max(0, spatial.getWorldBound().distanceToEdge(camera));
            if (!data.isInvalidated() && distance <= radius + (instances.containsKey(spatial) ? retentionMargin : 0)) {
                next.put(spatial, data);
                Asset asset = unique.get(data);
                if (asset == null) {
                    asset = new Asset(data, distance);
                    unique.put(data, asset);
                    assets.add(asset);
                } else {
                    asset.distance = Math.min(asset.distance, distance);
                }
            }
            return;
        }
        if (spatial instanceof Node) {
            for (Spatial child : ((Node) spatial).getChildren()) {
                collect(child, camera, next, unique, assets);
            }
        }
    }

    private void copy(Region region) {
        int resolution = region.resolution;
        int count = resolution * resolution;
        float[] source = region.key.data.getAttributes().copyCardData(region.key.card);
        for (int y = -1; y <= resolution; y++) {
            for (int x = -1; x <= resolution; x++) {
                int sx = Math.max(0, Math.min(resolution - 1, x));
                int sy = Math.max(0, Math.min(resolution - 1, y));
                int from = sx + sy * resolution;
                int to = region.block.x + 1 + x + size * (region.block.y + 1 + y);
                for (int channel = 0; channel < 3; channel++) {
                    for (int c = 0; c < 4; c++) {
                        float value = source[channel * count * 4 + from * 4 + c];
                        value = Math.max(-65504, Math.min(65504, value));
                        pixels[channel].putShort(to * 8 + c * 2, FastMath.convertFloatToHalf(value));
                    }
                }
                pixels[3].putFloat(to * 4, source[count * 12 + from]);
            }
        }
    }

    private void release(Region region) {
        residents.remove(region.key);
        region.resident = false;
        Block block = region.block;
        dirty.add(block);
        for (int y = block.y; y < block.y + block.height; y++) {
            for (int x = block.x; x < block.x + block.width; x++) {
                int index = x + size * y;
                for (int channel = 0; channel < 3; channel++) {
                    pixels[channel].putLong(index * 8, 0);
                }
                pixels[3].putFloat(index * 4, 1);
            }
        }
        block.used = false;
        allocator.coalesce();
        evicted++;
    }

    /** @return current region or null if absent, out of range, invalidated, oversized or budget-limited */
    public Region getRegion(Spatial model, int card) {
        checkOpen();
        SurfaceBakeData data = instances.get(model);
        if (data == null || data.isInvalidated() || data != model.getUserData(SurfaceBakeData.USER_DATA_KEY)) {
            return null;
        }
        return residents.get(new Key(data, card));
    }

    /**
     * World hit to this card's clip space; call only for a current resident region.
     * @param model tracked model instance
     * @param card layout card index
     * @return independent projection matrix; divide xyz by w before UV/depth mapping
     */
    public Matrix4f getWorldToCard(Spatial model, int card) {
        Region region = requireRegion(model, card);
        return region.key.data.getLayout().createCamera(card, region.resolution).getViewProjectionMatrix()
                .mult(getGeometryToWorld(model, card).invert());
    }

    /** @return Geometry-to-world matrix; transform stored normals with its inverse transpose */
    public Matrix4f getGeometryToWorld(Spatial model, int card) {
        Region region = requireRegion(model, card);
        return model.getLocalToWorldMatrix(new Matrix4f())
                .mult(region.key.data.getLayout().getGeometryToRoot(card / 6));
    }

    private Region requireRegion(Spatial model, int card) {
        Region region = getRegion(model, card);
        if (region == null) {
            throw new IllegalStateException("Surface card is not resident");
        }
        return region;
    }

    /** @return borrowed shared attribute atlas; same texture for every instance */
    public Texture2D getTexture(SurfaceAttributeCache.Channel channel) {
        checkOpen();
        return textures[channel.ordinal()];
    }

    /** @return borrowed R32F normalized-depth atlas, not a depth attachment */
    public Texture2D getDepthTexture() {
        checkOpen();
        return textures[3];
    }

    /** @return fixed GPU payload size; CPU staging has the same size, excluding saved assets */
    public long getTextureBytes() {
        return (long) size * size * 28;
    }

    /** @return number of shared resident cards, not instances */
    public int getResidentCardCount() {
        return residents.size();
    }

    /** @return cards copied this update (zero on stable fully resident frames) */
    public int getUploadedCardCount() {
        return uploaded;
    }

    /** @return cards evicted this update */
    public int getEvictedCardCount() {
        return evicted;
    }

    /** @return requested cards not resident; consumers must provide a missing-data fallback */
    public int getMissingCardCount() {
        return missing;
    }

    /** @return scene nodes visited during the latest index maintenance, zero on static frames */
    public int getIndexVisitedNodeCount() {
        return sceneIndex == null ? 0 : sceneIndex.getVisitedNodeCount();
    }

    /** @return candidates tested by the latest camera query, not the entire scene size */
    public int getIndexQueryCandidateCount() {
        return sceneIndex == null ? 0 : sceneIndex.getQueryCandidateCount();
    }

    /** @return total indexed baked instances, including those outside the residency radius */
    public int getIndexedInstanceCount() {
        return sceneIndex == null ? 0 : sceneIndex.getInstanceCount();
    }

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("Surface attribute atlas is closed");
        }
    }

    /** Release owned textures after removing consumers, on the render thread. Idempotent. */
    @Override
    public void close() {
        if (!closed) {
            for (Region region : residents.values()) {
                region.resident = false;
            }
            residents.clear();
            instances.clear();
            previousAssets.clear();
            cachedPriorities.clear();
            dirty.clear();
            for (Texture2D texture : textures) {
                texture.getImage().dispose();
            }
            if (sceneIndex != null) {
                sceneIndex.close();
                sceneIndex = null;
                indexedRoot = null;
            }
            closed = true;
        }
    }

    /** A residency token, invalid after eviction/close; re-query after every update. */
    public final class Region {
        private final Key key;
        private final Block block;
        private final int resolution;
        private boolean resident = true;

        private Region(Key key, Block block, int resolution) {
            this.key = key;
            this.block = block;
            this.resolution = resolution;
        }

        /** @return whether this token still owns its allocated region */
        public boolean isResident() {
            return resident && !closed && !key.data.isInvalidated();
        }

        /** @return original card edge size, without gutter or block rounding */
        public int getResolution() {
            return resolution;
        }

        /** @return (offsetU,offsetV,scaleU,scaleV); atlasUV=cardUV*zw+xy */
        public Vector4f getUvTransform() {
            if (!isResident()) {
                throw new IllegalStateException("Evicted surface atlas region");
            }
            return new Vector4f((block.x + 1f) / size, (block.y + 1f) / size,
                    (float) resolution / size, (float) resolution / size);
        }
    }

    private static final class Asset {
        final SurfaceBakeData data;
        float distance;

        Asset(SurfaceBakeData data, float distance) {
            this.data = data;
            this.distance = distance;
        }
    }

    private static final class Key {
        final SurfaceBakeData data;
        final int card;

        Key(SurfaceBakeData data, int card) {
            this.data = data;
            this.card = card;
        }

        @Override
        public int hashCode() {
            return 31 * System.identityHashCode(data) + card;
        }

        @Override
        public boolean equals(Object object) {
            if (!(object instanceof Key)) {
                return false;
            }
            Key other = (Key) object;
            return data == other.data && card == other.card;
        }
    }

    private static final class Block {
        final int x;
        final int y;
        final int width;
        final int height;
        boolean used;
        Block[] children;

        Block(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        Block allocate(int requested) {
            if (used || requested > width || requested > height) {
                return null;
            }
            if (children == null && requested == width && requested == height) {
                used = true;
                return this;
            }
            if (children == null) {
                if (width - requested > height - requested) {
                    children = new Block[]{new Block(x, y, requested, height),
                        new Block(x + requested, y, width - requested, height)};
                } else {
                    children = new Block[]{new Block(x, y, width, requested),
                        new Block(x, y + requested, width, height - requested)};
                }
            }
            for (Block child : children) {
                Block result = child.allocate(requested);
                if (result != null) {
                    return result;
                }
            }
            return null;
        }

        boolean coalesce() {
            if (used) {
                return false;
            }
            boolean empty = true;
            if (children != null) {
                for (Block child : children) {
                    empty &= child.coalesce();
                }
                if (empty) {
                    children = null;
                }
            }
            return empty;
        }
    }
}
