package com.jme3.scene.plugins.sdf;

import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.math.Matrix4f;
import com.jme3.math.Vector3f;
import com.jme3.renderer.*;
import com.jme3.scene.*;
import com.jme3.texture.*;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Render-thread-owned, lazy, bounded LRU cache of static material surface cards.
 * Stores diffuse reflectance, geometry-local shading normal, emission and depth;
 * never stores scene lighting. CPU layout/SDF survives eviction and close().
 * Each card currently has independent textures, not a packed shared atlas.
 * Custom materials must declare Technique SurfaceCapture with this same MRT contract.
 * Unsupported materials fail explicitly, never fall back to an ordinary lit pass.
 *
 * <pre>{@code
 * // 第一步：加载已烘焙 j3o，在加载/SDK 验证阶段检查依赖版本。
 * SdfBakeData data = model.getUserData(SdfBakeData.USER_DATA_KEY);
 * SurfaceAttributeCache cache = new SurfaceAttributeCache(model, data, revision, 128, 24);
 * // 第二步：渲染线程按需捕获，最多常驻 24 张卡片；未请求的卡片不分配显存。
 * // 调用位置应在明确的视口边界；返回后恢复 restoreView 的 framebuffer/camera/background。
 * SurfaceAttributeCache.Card card = cache.capture(0, renderManager, viewPort);
 * Texture2D diffuse = card.getTexture(Channel.DIFFUSE);
 * // 第三步：只借用纹理。后续查询前 getResident(cardId)，因为 LRU 可能已经淘汰。
 * // 卡片 alpha=0 表示没有覆盖；命中还须通过深度验证，不可只按投影 UV 取颜色。
 * // 第四步：材质或外部贴图热更新后，显式失效并重新捕获。
 * cache.invalidateAll();
 * // 纯灯光变化不需要 invalidate；移动根节点也不重捕获局部空间属性。
 * // 第五步：模型卸载/编辑器预览关闭时，在渲染线程释放。
 * cache.close();
 * }</pre>
 *
 * <p>FrameGraph integration: place capture calls in a named sideEffect pass at a
 * viewport boundary; import the borrowed card textures for consumers. Pin all
 * cards needed by those consumers by keeping the residency budget large enough;
 * do not evict, invalidate or close during their execution. This class does not
 * execute or own the main graph. Static meshes only: no skinning, morphing,
 * instancing, transparent blending or inherited material overrides in this version.
 * LightMap/AO, fog, probes and scene lights are deliberately excluded from attributes.
 * Normal/parallax maps and alpha discard follow the capture view of the material.
 * View-dependent parallax is therefore a directional approximation.</p>
 */
public final class SurfaceAttributeCache implements AutoCloseable {
    public enum Channel {
        /** Linear diffuse reflectance RGB, alpha=1 for captured coverage. */
        DIFFUSE,
        /** Signed geometry-local shading normal XYZ, alpha=1 for coverage. */
        NORMAL,
        /** Linear emission RGB, alpha=1 for coverage; Unshaded color is emission. */
        EMISSION
    }

    /** Borrowed card. Valid only while resident and not invalidated/closed. */
    public static final class Card {
        private final Texture2D[] colors = new Texture2D[3];
        private final Texture2D depth;
        private final FrameBuffer buffer;
        private final Camera camera;

        private Card(Camera camera, int resolution) {
            this.camera = camera;
            buffer = new FrameBuffer(resolution, resolution, 1);
            for (int i = 0; i < colors.length; i++) {
                colors[i] = texture(resolution, Image.Format.RGBA16F);
                buffer.addColorTexture(colors[i]);
            }
            depth = texture(resolution, Image.Format.Depth24);
            buffer.setDepthTexture(depth);
            buffer.setMultiTarget(true);
        }

        private static Texture2D texture(int resolution, Image.Format format) {
            Texture2D result = new Texture2D(resolution, resolution, format);
            result.setMinFilter(Texture.MinFilter.NearestNoMipMaps);
            result.setMagFilter(Texture.MagFilter.Nearest);
            result.setWrap(Texture.WrapMode.EdgeClamp);
            return result;
        }

        /** @param channel semantic attribute @return borrowed nearest-filtered texture */
        public Texture2D getTexture(Channel channel) {
            return colors[channel.ordinal()];
        }

        /** @return depth in [0,1], clear=1; compare to projected hit depth */
        public Texture2D getDepthTexture() {
            return depth;
        }

        /** @return geometry-local to clip transform, independent copy */
        public Matrix4f getLocalToClip() {
            return camera.getViewProjectionMatrix().clone();
        }

        private void dispose() {
            buffer.dispose();
            depth.getImage().dispose();
            for (Texture2D color : colors) {
                color.getImage().dispose();
            }
        }
    }

    private final Spatial root;
    private final SurfaceCacheLayout layout;
    private final int resolution;
    private final int capacity;
    private final LinkedHashMap<Integer, Card> resident = new LinkedHashMap<>(16, .75f, true);
    private boolean closed;
    private long captureCount;

    /**
     * Validates CPU bake once but allocates no GPU objects. MATERIAL_CHANGED is
     * accepted because this new cache captures current attributes, not stale pixels.
     * @param root source root, hierarchy must remain stable while cache is alive
     * @param data saved bake
     * @param dependencies current external material content revision
     * @param resolution card edge length 8..2048
     * @param capacity maximum resident cards, positive
     */
    public SurfaceAttributeCache(Spatial root, SdfBakeData data, String dependencies, int resolution, int capacity) {
        if (resolution < 8 || resolution > 2048 || capacity < 1) {
            throw new IllegalArgumentException("Invalid surface cache budget");
        }
        SdfBakeData.Status status = data.validate(root, dependencies);
        if (status == SdfBakeData.Status.GEOMETRY_CHANGED || status == SdfBakeData.Status.INVALIDATED) {
            throw new IllegalArgumentException("Stale SDF bake: " + status);
        }
        this.root = root;
        this.layout = data.getLayout();
        this.resolution = resolution;
        this.capacity = capacity;
    }

    /**
     * Independent surface-only cache binding; no SDF is required or generated.
     * Same lazy capture/lifecycle rules as the legacy SdfBakeData constructor.
     * @param root static source root
     * @param data surface layout/source record
     * @param dependencies current material dependency revision
     * @param resolution card edge length 8..2048
     * @param capacity maximum resident cards
     */
    public SurfaceAttributeCache(Spatial root, SurfaceBakeData data, String dependencies, int resolution, int capacity) {
        if (resolution < 8 || resolution > 2048 || capacity < 1) {
            throw new IllegalArgumentException("Invalid surface cache budget");
        }
        SurfaceBakeData.Status status = data.validate(root, dependencies);
        if (status == SurfaceBakeData.Status.GEOMETRY_CHANGED || status == SurfaceBakeData.Status.INVALIDATED) {
            throw new IllegalArgumentException("Stale surface bake: " + status);
        }
        this.root = root;
        this.layout = data.getLayout();
        this.resolution = resolution;
        this.capacity = capacity;
    }

    /**
     * Captures on first request; repeated resident requests do no drawing.
     * Requires OpenGL 3.3 and floating MRTs. Material must own SurfaceCapture technique.
     * Always restores the supplied viewport, even when shader compilation fails.
     * @param index layout card index
     * @param manager render manager on its owning thread
     * @param restoreView explicitly restored viewport (call outside an active unrelated pass)
     * @return resident borrowed card
     */
    public Card capture(int index, RenderManager manager, ViewPort restoreView) {
        requireOpen();
        if (index < 0 || index >= layout.getCardCount()) {
            throw new IllegalArgumentException("Invalid card index");
        }
        Card cached = resident.get(index);
        if (cached != null) {
            return cached;
        }
        Renderer renderer = manager.getRenderer();
        if (!renderer.getCaps().contains(Caps.OpenGL33) || !renderer.getCaps().contains(Caps.FrameBufferMRT)
                || !renderer.getCaps().contains(Caps.FloatTexture)) {
            throw new UnsupportedOperationException("Surface capture requires GL3.3 floating MRTs");
        }
        // The engine's BoundDrawBuffer parameter is routing state, not an application override.
        int internalOverrides = manager.getPassDrawBufferTargetIdToShaders() ? 1 : 0;
        if (manager.getForcedMaterial() != null || manager.getForcedTechnique() != null
                || manager.getForcedRenderState() != null
                || manager.getForcedMatParams().size() > internalOverrides) {
            throw new IllegalStateException("Capture requires a clean render-manager boundary");
        }
        Geometry source = layout.resolve(root, index / 6);
        Material sourceMaterial = source.getMaterial();
        if (sourceMaterial == null || sourceMaterial.getMaterialDef().getTechniqueDefs("SurfaceCapture") == null) {
            throw new UnsupportedOperationException("Material must declare Technique SurfaceCapture: " + source.getName());
        }
        if (sourceMaterial.isTransparent() || !source.getWorldMatParamOverrides().isEmpty()
                || Boolean.TRUE.equals(sourceMaterial.getParamValue("UseInstancing"))
                || positive(sourceMaterial.getParamValue("NumberOfBones"))
                || positive(sourceMaterial.getParamValue("NumberOfMorphTargets"))) {
            throw new UnsupportedOperationException("Capture currently requires static opaque/cutout geometry without overrides");
        }
        Card card = new Card(layout.createCamera(index, resolution), resolution);
        Material captureMaterial = sourceMaterial.clone();
        captureMaterial.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Off);
        captureMaterial.getAdditionalRenderState().setDepthTest(true);
        captureMaterial.getAdditionalRenderState().setDepthWrite(true);
        Geometry proxy = new Geometry("SurfaceCapture", source.getMesh());
        proxy.setMaterial(captureMaterial);
        proxy.updateGeometricState();
        boolean completed = false;
        try {
            renderer.setFrameBuffer(card.buffer);
            manager.setCamera(card.camera, false);
            renderer.setBackgroundColor(com.jme3.math.ColorRGBA.BlackNoAlpha);
            renderer.clearBuffers(true, true, true);
            manager.setForcedTechnique("SurfaceCapture");
            manager.renderGeometry(proxy);
            completed = true;
        } finally {
            manager.setForcedTechnique(null);
            manager.setCamera(restoreView.getCamera(), false,
                    restoreView.getRenderTargetWidth(), restoreView.getRenderTargetHeight());
            renderer.setFrameBuffer(restoreView.getOutputFrameBuffer());
            renderer.setBackgroundColor(restoreView.getBackgroundColor());
            if (!completed) {
                card.dispose();
            }
        }
        while (resident.size() >= capacity) {
            Iterator<Map.Entry<Integer, Card>> iterator = resident.entrySet().iterator();
            iterator.next().getValue().dispose();
            iterator.remove();
        }
        resident.put(index, card);
        captureCount++;
        return card;
    }

    private static boolean positive(Object value) {
        return value instanceof Number && ((Number) value).intValue() > 0;
    }

    /** @param index card index @return borrowed card or null if absent; touches LRU order */
    public Card getResident(int index) {
        requireOpen();
        return resident.get(index);
    }

    /** @return currently allocated card count */
    public int getResidentCount() {
        return resident.size();
    }

    /** @return successful draws since construction; useful for SDK diagnostics */
    public long getCaptureCount() {
        return captureCount;
    }

    /**
     * Drops every cached attribute on render thread; next capture reads current material.
     * Detach preview consumers before calling; previously borrowed textures become invalid.
     */
    public void invalidateAll() {
        requireOpen();
        for (Card card : resident.values()) {
            card.dispose();
        }
        resident.clear();
    }

    /** Releases owned GPU resources on render thread, idempotent. Does not delete baked CPU data. */
    @Override
    public void close() {
        if (!closed) {
            invalidateAll();
            closed = true;
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Surface cache closed");
        }
    }
}
