package com.jme3.shadow;

import com.jme3.asset.AssetManager;
import com.jme3.environment.gi.BakedDirectionalShadow;
import com.jme3.environment.gi.GiProbeVolume;
import com.jme3.light.*;
import com.jme3.material.RenderState;
import com.jme3.material.MatParamOverride;
import com.jme3.math.*;
import com.jme3.post.*;
import com.jme3.renderer.*;
import com.jme3.renderer.framegraph.FrameGraph;
import com.jme3.renderer.framegraph.ResourceHandle;
import com.jme3.renderer.gpuscene.ShadowCasterBatch;
import com.jme3.renderer.queue.GeometryList;
import com.jme3.renderer.queue.OpaqueComparator;
import com.jme3.scene.*;
import com.jme3.scene.shape.Quad;
import com.jme3.shader.*;
import com.jme3.texture.*;
import com.jme3.texture.image.ColorSpace;
import com.jme3.util.BufferUtils;
import java.nio.ByteBuffer;
import java.util.*;

/**
 * Viewport-owned direct-light shadows, enabled by default. Shadow maps are packed
 * into a GPU atlas and sampled inside each light's BRDF evaluation. Deferred
 * receivers are never redrawn in a PostShadow pass. Two texture units serve all
 * shadowed lights plus one unit when a baked directional atlas is active.
 * Geometry ShadowMode still controls casting and receiving.
 *
 * <pre>{@code
 * // 第一步：获取视口管理器；添加方向光、点光源或聚光灯后自动生成阴影。
 * rootNode.setShadowMode(RenderQueue.ShadowMode.CastAndReceive);
 * DirectionalLight sun = new DirectionalLight(new Vector3f(-1, -1, -1).normalizeLocal());
 * rootNode.addLight(sun);
 * ShadowManager shadows = renderManager.getShadowManager(viewPort);
 * // 第二步：按光源实例获取稳定的配置对象，不依赖光源名称。
 * LightShadowSettings settings = shadows.getSettings(sun);
 * settings.setMapSize(2048);
 * settings.setCascadeCount(4);
 * settings.setShadowDistance(300);
 * settings.setFilterRadius(2);
 * // 第三步：只关闭此视口中该灯的投影，保留照明及场景序列化设置。
 * settings.setEnabled(false);
 * settings.setEnabled(true);
 * // 第四步：全局停用当前视口阴影，再开启时保留每盏灯的配置。
 * shadows.setEnabled(false);
 * shadows.setEnabled(true);
 * // 场景级开关使用 sun.setCastShadow(false)，该值随场景保存。
 * // 临时视口不再使用时调用 renderManager.releaseShadowManager(temporaryView)。
 * }</pre>
 * All calls run on the render thread, outside drawing. Viewport preferences are
 * runtime state; Light.castShadow and Light.getShadowSettings() are serialized. Legacy built-in shadow renderers
 * and filters retain ownership of their lights and suppress automatic handling
 * of those lights, preventing double application. Custom processors must disable
 * automatic shadowing for their lights explicitly. Budget/texture overflow fails
 * explicitly; no light silently loses its requested shadow. Default budget is
 * 128 MiB for shadow maps and the packed atlas, with at most 64 active lights.
 * Hardware without float textures/render targets retains lighting without managed
 * shadows; getFallbackReason() explains this fallback. Legacy processors remain
 * usable. VertexLighting materials temporarily use their pixel-lighting variant
 * while managed shadows are active, then restore their original preference.
 * To switch a legacy directional renderer to this path, copy getShadowMapSize(),
 * getNumShadowMaps() and getLambda() into its light's settings, then remove that
 * processor from the viewport before drawing. For legacy FPP filters, remove the
 * filter from its FPP. Keeping a legacy owner installed intentionally suppresses
 * automatic management even when that filter is disabled. Reinstalling it restores
 * the legacy path without changing Light.castShadow. Spatial's default ShadowMode
 * is unchanged: enable Cast/Receive on the scene or appropriate geometries.
 * Scenes without any casting geometry skip managed shadow allocation and passes.
 * Normal attachment and ShadowMode setters invalidate this cached eligibility;
 * transform changes alone do not require rediscovering shadow casters.
 */
public final class ShadowManager implements AutoCloseable {
    private static final int RECORD_WIDTH = 36;
    private final RenderManager manager;
    private final ViewPort viewport;
    private final Map<Light, LightShadowSettings> settings = new IdentityHashMap<>();
    private final Map<Light, Slot> slots = new IdentityHashMap<>();
    private final Map<Light, Integer> indices = new IdentityHashMap<>();
    private final Set<Light> catalog = Collections.newSetFromMap(new IdentityHashMap<Light, Boolean>());
    private List<Spatial> catalogScenes = Collections.emptyList();
    private List<Spatial> observedRoots = Collections.emptyList();
    private AssetManager assets;
    private boolean catalogDirty = true;
    private boolean hasCasters;
    private final List<Geometry> casters = new ArrayList<>();
    private final Set<Geometry> casterSet = Collections.newSetFromMap(new IdentityHashMap<>());
    private final List<BakedDirectionalShadow> bakedCandidates = new ArrayList<>();
    private final List<BakedDirectionalShadow> bakedAtlasSources = new ArrayList<>();
    private final Map<Light, BakedDirectionalShadow> bakedLights = new IdentityHashMap<>();
    private final Map<AbstractShadowRenderer, BakedDirectionalShadow> bakedRenderers = new IdentityHashMap<>();
    private final Map<BakedDirectionalShadow, Vector4f> bakedRects = new IdentityHashMap<>();
    private final Map<String, Integer> bakedIndices = new HashMap<>();
    private final Map<String, BakedDirectionalShadow> directSnapshots = new HashMap<>();
    private final GeometryList unbakedCasters = new GeometryList(new OpaqueComparator());
    private Texture2D bakedAtlas;
    private final GeometryList fallbackCasters = new GeometryList(new OpaqueComparator());
    private ShadowCasterBatch indirectCasters;
    private boolean indirectDrawing = true;
    private int indirectCasterCount;
    private final SceneChangeListener catalogListener = (spatial, change) -> {
        if (change == SceneChangeListener.Change.ATTACHED || change == SceneChangeListener.Change.DETACHING
                || change == SceneChangeListener.Change.RENDERING) {
            catalogDirty = true;
        }
    };
    private final List<Slot> active = new ArrayList<>();
    private final Quad quad = new Quad(1, 1);
    private final RenderState copyState = new RenderState();
    private boolean enabled = true;
    private boolean bakedShadowsEnabled = true;
    private long byteBudget = 128L * 1024 * 1024;
    private Texture2D atlas;
    private Texture2D records;
    private FrameBuffer target;
    private Shader copyShader;
    private int width;
    private int height;
    private String fallbackReason;
    private final MatParamOverride pixelLighting = new MatParamOverride(VarType.Boolean, "VertexLighting", false);
    private boolean pixelOverride;

    private static final class Slot {
        Light light;
        LightShadowSettings settings;
        AbstractShadowRenderer renderer;
        int size;
        int maps;
        int[] x;
        int[] y;
    }

    /** Keeps cascade fitting distances in world units for both camera projections. */
    private static final class DirectionalMaps extends DirectionalLightShadowRenderer {
        DirectionalMaps(AssetManager assets, int size, int cascades) {
            super(assets, size, cascades);
        }

        @Override
        protected void updateShadowCams(Camera camera) {
            super.updateShadowCams(camera);
            if (camera.isParallelProjection()) {
                // Legacy post-shadow code normalizes these values before fitting.
                // Managed sampling stores clip-depth thresholds separately.
                PssmShadowUtil.updateFrustumSplits(splitsArray, Math.max(camera.getFrustumNear(), 0.001f),
                        getShadowZExtend() > 0 ? getShadowZExtend() : camera.getFrustumFar(), getLambda());
            }
        }
    }

    public ShadowManager(RenderManager manager, ViewPort viewport) {
        this.manager = Objects.requireNonNull(manager);
        this.viewport = Objects.requireNonNull(viewport);
        copyState.setDepthTest(false);
        copyState.setDepthWrite(false);
        copyState.setFaceCullMode(RenderState.FaceCullMode.Off);
    }

    /** Copies the light's settings for this viewport without allocating GPU resources. Clear the override to use the scene settings again. */
    public LightShadowSettings getSettings(Light light) {
        return settings.computeIfAbsent(Objects.requireNonNull(light), key -> key.getShadowSettings().clone());
    }

    /** Removes a viewport override, restoring serialized light settings.
     * Call on the render thread before drawing; see Light.getShadowSettings().
     */
    public void clearSettings(Light light) {
        settings.remove(light);
    }

    /**
     * Identifies this manager's active pixel-lighting override by identity.
     * Rendering pipelines may accept it without treating it as an application
     * override; same-named user overrides retain their fallback behavior.
     * Query on the render thread while the viewport is being rendered.
     * <pre>{@code
     * // 第一步：只识别当前阴影管理器实际拥有的覆盖项。
     * boolean internal = shadows.isManagedOverride(override);
     * // 第二步：其他用户覆盖项仍按管线原有规则回退。
     * }</pre>
     */
    public boolean isManagedOverride(MatParamOverride override) {
        return pixelOverride && override == pixelLighting;
    }

    private LightShadowSettings preferences(Light light) {
        LightShadowSettings preference = settings.get(light);
        return preference == null ? light.getShadowSettings() : preference;
    }

    private void refreshCatalog() {
        List<Spatial> roots = new ArrayList<>();
        for (Spatial scene : viewport.getScenes()) {
            Spatial root = scene;
            while (root.getParent() != null) {
                root = root.getParent();
            }
            if (!roots.contains(root)) {
                roots.add(root);
            }
        }
        if (!roots.equals(observedRoots) || !viewport.getScenes().equals(catalogScenes)) {
            for (Spatial root : observedRoots) {
                root.removeSceneChangeListener(catalogListener);
            }
            observedRoots = roots;
            catalogScenes = new ArrayList<>(viewport.getScenes());
            for (Spatial root : observedRoots) {
                root.addSceneChangeListener(catalogListener);
            }
            catalogDirty = true;
        }
        if (!catalogDirty) {
            return;
        }
        catalog.clear();
        assets = null;
        hasCasters = false;
        casters.clear();
        casterSet.clear();
        bakedCandidates.clear();
        releaseIndirectCasters();
        for (Spatial scene : catalogScenes) {
            for (Light light : scene.getWorldLightList()) {
                catalog.add(light);
            }
            scene.depthFirstTraversal(spatial -> {
                if (spatial instanceof GiProbeVolume) {
                    Collections.addAll(bakedCandidates, ((GiProbeVolume) spatial).getBakedDirectionalShadows());
                }
                if (spatial instanceof Geometry) {
                    com.jme3.renderer.queue.RenderQueue.ShadowMode mode = spatial.getShadowMode();
                    if (mode == com.jme3.renderer.queue.RenderQueue.ShadowMode.Cast
                            || mode == com.jme3.renderer.queue.RenderQueue.ShadowMode.CastAndReceive) {
                        hasCasters = true;
                        casters.add((Geometry) spatial);
                        casterSet.add((Geometry) spatial);
                    }
                }
                for (Light light : spatial.getLocalLightList()) {
                    catalog.add(light);
                }
                if (assets == null && spatial instanceof Geometry && ((Geometry) spatial).getMaterial() != null) {
                    assets = ((Geometry) spatial).getMaterial().getMaterialDef().getAssetManager();
                }
            });
        }
        catalogDirty = false;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Enables batched indirect draws for compatible managed shadow casters,
     * by default. Each shadow camera still fits and culls its own caster list.
     * Requires GPU Scene enabled and OpenGL 4.3. Compatible alpha-tested
     * casters batch by texture and cutoff. Animated, custom and otherwise
     * incompatible casters use their original technique.
     * Set on the render thread before drawing. The setting is viewport-local.
     * <pre>{@code
     * // 第一步：用正常方式挂载物体并让光源投影；默认自动使用间接绘制。
     * ShadowManager shadows = renderManager.getShadowManager(viewPort);
     * // 第二步：对照测试时切回逐物体绘制，灯光和阴影配置继续生效。
     * shadows.setIndirectDrawingEnabled(false);
     * // 第三步：恢复自动间接绘制；移除视口会释放批次资源。
     * shadows.setIndirectDrawingEnabled(true);
     * }</pre>
     * @param enabled whether to use indirect drawing where supported
     */
    public void setIndirectDrawingEnabled(boolean enabled) {
        indirectDrawing = enabled;
        if (!enabled) {
            releaseIndirectCasters();
        }
    }

    /**
     * Returns the number of indirect caster submissions in the latest
     * viewport render, including repeated geometry across cascades or faces.
     * Query on the render thread after rendering.
     * @return number of indirect caster commands
     */
    public int getIndirectCasterCount() {
        return indirectCasterCount;
    }

    private void releaseIndirectCasters() {
        if (indirectCasters != null) {
            indirectCasters.close();
            indirectCasters = null;
        }
    }

    void renderCasters(AbstractShadowRenderer source, GeometryList visible, Camera camera) {
        GeometryList eligible = visible;
        var baked = bakedRenderers.get(source);
        if (baked != null) {
            for (int i = 0; i < visible.size(); i++) {
                Geometry caster = visible.get(i);
                if (!baked.contains(caster)) {
                    unbakedCasters.add(caster);
                }
            }
            eligible = unbakedCasters;
        }
        try {
            if (indirectCasters != null && manager.getRenderFilter() == null
                    && !ShadowCasterBatch.hasShadowOverrides(manager.getForcedMatParams())) {
                int drawn = indirectCasters.draw(manager, eligible, fallbackCasters);
                if (drawn > 0) {
                    indirectCasterCount += drawn;
                    if (fallbackCasters.size() != 0) {
                        viewport.getQueue().renderShadowQueue(fallbackCasters, manager, camera, true);
                    }
                    return;
                }
            }
            viewport.getQueue().renderShadowQueue(eligible, manager, camera, true);
        } finally {
            fallbackCasters.clear();
            unbakedCasters.clear();
            visible.clear();
        }
    }

    /** Returns why this viewport fell back, or null when it did not. */
    public String getFallbackReason() {
        return fallbackReason;
    }

    public long getByteBudget() {
        return byteBudget;
    }

    public void setByteBudget(long bytes) {
        if (bytes < 1) {
            throw new IllegalArgumentException("Positive shadow budget required");
        }
        byteBudget = bytes;
    }

    /** Internal shader record index; -1 means this light has no managed shadow. */
    public int getLightIndex(Light light) {
        Integer index = indices.get(light);
        return enabled && index != null ? index : -1;
    }

    /** Adds only shadow-caster and atlas-copy passes to the viewport's FrameGraph. */
    public void appendPasses(FrameGraph graph) {
        indices.clear();
        bakedIndices.clear();
        directSnapshots.clear();
        bakedRenderers.clear();
        active.clear();
        indirectCasterCount = 0;
        fallbackReason = null;
        refreshCatalog();
        if (bakedShadowsEnabled && manager.getRenderFilter() == null) {
            for (BakedDirectionalShadow candidate : bakedCandidates) {
                // The volume serializes the light reference. Removing it from the
                // live light list does not remove the saved lighting recipe.
                if (candidate != null && candidate.matchesBake(casterSet)) {
                    directSnapshots.putIfAbsent(candidate.getId(), candidate);
                }
            }
        }
        if (!enabled) {
            releaseIndirectCasters();
            setBakedAtlas(Collections.emptyList());
            return;
        }
        if (!manager.getRenderer().getCaps().contains(Caps.FloatTexture)
                || !manager.getRenderer().getCaps().contains(Caps.FloatColorBufferRGBA)) {
            fallbackReason = "Managed shadows require float textures and RGBA float targets";
            return;
        }
        Set<Light> found = Collections.newSetFromMap(new IdentityHashMap<Light, Boolean>());
        for (Light light : catalog) {
            if (hasCasters && light.isEnabled() && light.isCastShadow()
                    && (light instanceof DirectionalLight || light instanceof PointLight || light instanceof SpotLight)) {
                found.add(light);
            }
        }
        for (SceneProcessor processor : viewport.getProcessors()) {
            if (processor instanceof AbstractShadowRenderer) {
                found.remove(legacyLight((AbstractShadowRenderer) processor));
            } else if (processor instanceof FilterPostProcessor) {
                for (Filter filter : ((FilterPostProcessor) processor).getFilterList()) {
                    if (filter instanceof AbstractShadowFilter) {
                        found.remove(legacyLight(((AbstractShadowFilter<?>) filter).shadowRenderer));
                    }
                }
            }
        }
        found.removeIf(light -> !preferences(light).isEnabled());
        com.jme3.util.TempVars vars = com.jme3.util.TempVars.get();
        try {
            found.removeIf(light -> !light.intersectsFrustum(viewport.getCamera(), vars));
        } finally {
            vars.release();
        }
        Iterator<Map.Entry<Light, Slot>> old = slots.entrySet().iterator();
        while (old.hasNext()) {
            Map.Entry<Light, Slot> entry = old.next();
            if (!found.contains(entry.getKey())) {
                dispose(entry.getValue());
                old.remove();
            }
        }
        if (found.isEmpty() || assets == null) {
            releaseIndirectCasters();
            setBakedAtlas(Collections.emptyList());
            return;
        }
        if (indirectDrawing && manager.isGpuSceneEnabled() && manager.getClass() == RenderManager.class
                && manager.getRenderer().getCaps().contains(Caps.OpenGL43)) {
            if (indirectCasters != null && indirectCasters.isStale()) {
                releaseIndirectCasters();
            }
            if (indirectCasters == null) {
                indirectCasters = new ShadowCasterBatch(assets, casters);
            }
        } else {
            releaseIndirectCasters();
        }
        if (found.size() > 64) {
            throw new IllegalStateException("Managed shadow budget exceeds 64 active lights");
        }
        int limit = manager.getRenderer().getLimits().get(Limits.TextureSize);
        long mapBytes = 0;
        for (Light light : found) {
            LightShadowSettings preference = preferences(light);
            int maps = light instanceof PointLight ? 6 : light instanceof DirectionalLight ? preference.getCascadeCount() : 1;
            mapBytes += (long) maps * preference.getMapSize() * preference.getMapSize() * 4;
        }
        if (mapBytes * 5 > byteBudget) {
            throw new IllegalStateException("Managed shadows exceed the configured byte budget");
        }
        for (Light light : found) {
            LightShadowSettings preference = preferences(light);
            int maps = light instanceof PointLight ? 6 : light instanceof DirectionalLight ? preference.getCascadeCount() : 1;
            Slot slot = slots.get(light);
            if (slot == null || slot.size != preference.getMapSize() || slot.maps != maps) {
                if (slot != null) {
                    dispose(slot);
                }
                slot = create(light, preference, maps, assets);
                slots.put(light, slot);
            }
            slot.settings = preference;
            if (slot.renderer instanceof DirectionalLightShadowRenderer) {
                DirectionalLightShadowRenderer directional = (DirectionalLightShadowRenderer) slot.renderer;
                directional.setLambda(preference.getSplitLambda());
                directional.setShadowZExtend(preference.getShadowDistance());
            }
            active.add(slot);
        }
        // Larger tiles first keeps shelf padding bounded and the layout deterministic.
        active.sort(Comparator.comparingInt((Slot slot) -> slot.size).reversed()
                .thenComparingInt(slot -> System.identityHashCode(slot.light)));
        int packedWidth = Math.min(limit, Math.max(active.get(0).size,
                (int) Math.ceil(Math.sqrt(mapBytes / 4.0))));
        int x = 0;
        int y = 0;
        int shelfHeight = 0;
        for (Slot slot : active) {
            if (slot.size > limit) {
                throw new IllegalStateException("Shadow resolution exceeds TextureSize");
            }
            for (int map = 0; map < slot.maps; map++) {
                if (x + slot.size > packedWidth) {
                    x = 0;
                    y += shelfHeight;
                    shelfHeight = 0;
                }
                slot.x[map] = x;
                slot.y[map] = y;
                x += slot.size;
                shelfHeight = Math.max(shelfHeight, slot.size);
            }
        }
        int packedHeight = y + shelfHeight;
        if (packedHeight > limit || mapBytes + (long) packedWidth * packedHeight * 16 > byteBudget) {
            throw new IllegalStateException("Packed shadow atlas exceeds texture size or byte budget");
        }
        selectBakedShadows(found);
        ensureAtlas(packedWidth, packedHeight, active.size());
        for (int i = 0; i < active.size(); i++) {
            Slot slot = active.get(i);
            graph.addExpansionPass("Light[" + i + "]",
                    targetGraph -> slot.renderer.appendPostQueuePasses(targetGraph, viewport.getQueue()));
        }
        ResourceHandle<FrameBuffer> packed = graph.importResource("PackedShadowAtlas", FrameBuffer.class, () -> target);
        FrameGraph.PassBuilder copy = graph.addPass("ManagedShadowAtlas", context -> {
            context.get(packed);
            copyAtlas();
        }).write(packed).sideEffect();
        for (int light = 0; light < active.size(); light++) {
            Slot slot = active.get(light);
            for (int map = 0; map < slot.maps; map++) {
                final int face = map;
                copy.read(graph.importResource("ShadowSource[" + light + "," + map + "]",
                        FrameBuffer.class, () -> slot.renderer.shadowFB[face]));
            }
        }
    }

    /** @return active managed light index for this particular baked snapshot, or -1 */
    public int getBakedLightIndex(String id) {
        return enabled && id != null ? bakedIndices.getOrDefault(id, -1) : -1;
    }

    /** @return whether any baked direct bands are valid, even with no realtime lights */
    public boolean hasBakedDirectLighting() {
        return !directSnapshots.isEmpty();
    }

    /** @return the scene light associated with an independently playable band */
    public Light findBakedDirectLight(String id) {
        BakedDirectionalShadow snapshot = directSnapshots.get(id);
        return snapshot == null ? null : snapshot.getLight();
    }

    /** @return whether this frame's shadow records contain the baked atlas extension */
    public boolean hasBakedShadowRecords() {
        return enabled && bakedAtlas != null && !bakedIndices.isEmpty();
    }

    /**
     * Selects saved direct Lightmap and world-space shadow playback for this viewport.
     * Default on. Capture views can pass false before drawing to recompute original
     * lighting and shadows, then set true on the render thread to restore playback.
     * <pre>{@code
     * // 第一步：离屏烘焙视口设置 false，按原场景配置重新捕获阴影。
     * manager.getShadowManager(capture).setBakedShadowsEnabled(false);
     * // 第二步：普通游戏视口使用默认值 true。
     * manager.getShadowManager(gameView).setBakedShadowsEnabled(true);
     * }</pre>
     */
    public void setBakedShadowsEnabled(boolean enabled) {
        bakedShadowsEnabled = enabled;
    }

    private void selectBakedShadows(Set<Light> found) {
        bakedLights.clear();
        if (!bakedShadowsEnabled || bakedCandidates.isEmpty()
                || !manager.getRenderer().getCaps().contains(Caps.OpenGL30) || manager.getRenderFilter() != null) {
            setBakedAtlas(Collections.emptyList());
            return;
        }
        var selected = new ArrayList<BakedDirectionalShadow>();
        for (var candidate : bakedCandidates) {
            if (candidate != null && directSnapshots.containsKey(candidate.getId())
                    && found.contains(candidate.getLight()) && !bakedLights.containsKey(candidate.getLight())) {
                bakedLights.put(candidate.getLight(), candidate);
                selected.add(candidate);
                if (selected.size() == BakedDirectionalShadow.MAX_LIGHTS) {
                    break;
                }
            }
        }
        setBakedAtlas(selected);
        for (Slot slot : active) {
            var baked = bakedLights.get(slot.light);
            if (bakedRects.containsKey(baked)) {
                bakedRenderers.put(slot.renderer, baked);
            }
        }
    }

    private void setBakedAtlas(List<BakedDirectionalShadow> selected) {
        if (selected.equals(bakedAtlasSources)) {
            return;
        }
        disposeBakedAtlas();
        if (selected.isEmpty()) {
            return;
        }
        int tile = 0;
        for (var snapshot : selected) {
            tile = Math.max(tile, snapshot.getDepth().getImage().getWidth());
        }
        int columns = Math.min(2, selected.size());
        int rows = (selected.size() + columns - 1) / columns;
        int w = columns * tile;
        int h = rows * tile;
        int limit = manager.getRenderer().getLimits().getOrDefault(Limits.TextureSize, 0);
        if (w > limit || h > limit || (long) w * h * Float.BYTES > 64L * 1024 * 1024) {
            return;
        }
        ByteBuffer pixels = BufferUtils.createByteBuffer(w * h * Float.BYTES);
        for (int i = 0; i < selected.size(); i++) {
            var snapshot = selected.get(i);
            Image image = snapshot.getDepth().getImage();
            int size = image.getWidth();
            int x = (i % columns) * tile;
            int y = (i / columns) * tile;
            ByteBuffer source = image.getData(0).duplicate();
            for (int row = 0; row < size; row++) {
                source.limit((row + 1) * size * Float.BYTES);
                source.position(row * size * Float.BYTES);
                pixels.position(((y + row) * w + x) * Float.BYTES);
                pixels.put(source);
            }
            bakedRects.put(snapshot, new Vector4f(x / (float) w, y / (float) h,
                    size / (float) w, size / (float) h));
        }
        pixels.clear();
        bakedAtlas = new Texture2D(new Image(Image.Format.R32F, w, h, pixels, ColorSpace.Linear));
        bakedAtlas.setMinFilter(Texture.MinFilter.NearestNoMipMaps);
        bakedAtlas.setMagFilter(Texture.MagFilter.Nearest);
        bakedAtlasSources.addAll(selected);
    }

    private void disposeBakedAtlas() {
        if (bakedAtlas != null) {
            bakedAtlas.getImage().dispose();
            bakedAtlas = null;
        }
        bakedAtlasSources.clear();
        bakedRects.clear();
    }

    private Light legacyLight(AbstractShadowRenderer renderer) {
        if (renderer instanceof DirectionalLightShadowRenderer) {
            return ((DirectionalLightShadowRenderer) renderer).getLight();
        }
        if (renderer instanceof PointLightShadowRenderer) {
            return ((PointLightShadowRenderer) renderer).getLight();
        }
        if (renderer instanceof SpotLightShadowRenderer) {
            return ((SpotLightShadowRenderer) renderer).getLight();
        }
        if (renderer instanceof SdsmDirectionalLightShadowRenderer) {
            return ((SdsmDirectionalLightShadowRenderer) renderer).getLight();
        }
        return null;
    }

    private Slot create(Light light, LightShadowSettings preference, int maps, AssetManager assets) {
        Slot slot = new Slot();
        slot.light = light;
        slot.settings = preference;
        slot.size = preference.getMapSize();
        slot.maps = maps;
        slot.x = new int[maps];
        slot.y = new int[maps];
        if (light instanceof DirectionalLight) {
            DirectionalLightShadowRenderer renderer = new DirectionalMaps(assets, slot.size, maps);
            renderer.setLight((DirectionalLight) light);
            slot.renderer = renderer;
        } else if (light instanceof PointLight) {
            PointLightShadowRenderer renderer = new PointLightShadowRenderer(assets, slot.size);
            renderer.setLight((PointLight) light);
            slot.renderer = renderer;
        } else {
            SpotLightShadowRenderer renderer = new SpotLightShadowRenderer(assets, slot.size);
            renderer.setLight((SpotLight) light);
            slot.renderer = renderer;
        }
        slot.renderer.setShadowCompareMode(CompareMode.Software);
        slot.renderer.initialize(manager, viewport);
        // Material PreShadow techniques use a legacy slope offset of 5. At
        // grazing angles that moves blockers across wall/ceiling junctions.
        // Managed shadows already apply explicit receiver depth/normal bias.
        slot.renderer.managedRasterState = true;
        slot.renderer.managedOwner = this;
        slot.renderer.getPreShadowForcedRenderState().setFaceCullMode(RenderState.FaceCullMode.Off);
        slot.renderer.getPreShadowForcedRenderState().setPolyOffset(0f, 0f);
        return slot;
    }

    private void ensureAtlas(int w, int h, int count) {
        if (atlas == null || width != w || height != h) {
            disposeAtlas();
            width = w;
            height = h;
            atlas = new Texture2D(w, h, Image.Format.RGBA32F);
            atlas.setMinFilter(Texture.MinFilter.NearestNoMipMaps);
            atlas.setMagFilter(Texture.MagFilter.Nearest);
            target = new FrameBuffer(w, h, 1);
            target.addColorTarget(FrameBuffer.FrameBufferTarget.newTarget(atlas));
        }
        // The optional last row stores the projective matrices for all baked
        // lights. The existing rows and their lookup widths are unchanged.
        int recordCount = count + (bakedRenderers.isEmpty() ? 0 : 1);
        if (records == null || records.getImage().getHeight() != recordCount) {
            if (records != null) {
                records.getImage().dispose();
            }
            records = new Texture2D(new Image(Image.Format.RGBA32F, RECORD_WIDTH, recordCount,
                    BufferUtils.createByteBuffer(RECORD_WIDTH * recordCount * 16), ColorSpace.Linear));
            records.setMinFilter(Texture.MinFilter.NearestNoMipMaps);
            records.setMagFilter(Texture.MagFilter.Nearest);
        }
        if (copyShader == null) {
            copyShader = new Shader();
            copyShader.addSource(Shader.ShaderType.Vertex, "ManagedShadowCopy.vert",
                    "attribute vec3 inPosition; varying vec2 uv; void main(){uv=inPosition.xy;gl_Position=vec4(uv*2.0-1.0,0.0,1.0);}", "", "GLSL100");
            copyShader.addSource(Shader.ShaderType.Fragment, "ManagedShadowCopy.frag",
                    "uniform sampler2D m_Depth; varying vec2 uv; void main(){gl_FragColor=vec4(texture2D(m_Depth,uv).r);}", "", "GLSL100");
        }
    }

    private void copyAtlas() {
        Renderer renderer = manager.getRenderer();
        FrameBuffer previous = renderer.getCurrentFrameBuffer();
        ByteBuffer bytes = records.getImage().getData(0);
        int bakedRow = active.size() * RECORD_WIDTH * 16;
        int bakedOffset = 0;
        bytes.clear();
        try {
            renderer.setFrameBuffer(target);
            renderer.clearClipRect();
            renderer.applyRenderState(copyState);
            for (int index = 0; index < active.size(); index++) {
                Slot slot = active.get(index);
                int row = index * RECORD_WIDTH * 16;
                for (int component = 0; component < RECORD_WIDTH * 4; component++) {
                    bytes.putFloat(row + component * 4, 0);
                }
                var baked = bakedRenderers.get(slot.renderer);
                if (baked != null) {
                    Matrix4f matrix = baked.getProjection();
                    Vector4f rect = bakedRects.get(baked);
                    indices.put(slot.light, index);
                    bakedIndices.put(baked.getId(), index);
                    for (int column = 0; column < 4; column++) {
                        put(bytes, bakedRow, bakedOffset + column, matrix.get(0, column), matrix.get(1, column),
                                matrix.get(2, column), matrix.get(3, column));
                    }
                    put(bytes, row, 34, rect.x, rect.y, rect.z, rect.w);
                    put(bytes, row, 35, bakedOffset, 0, 0, 0);
                    bakedOffset += 4;
                }
                if (slot.renderer.skipPostPass) {
                    continue;
                }
                indices.put(slot.light, index);
                put(bytes, row, 0, slot.light.getType().getId(), slot.maps,
                        slot.settings.getNormalBias(), slot.settings.getDepthBias());
                Vector3f position = slot.light instanceof PointLight ? ((PointLight) slot.light).getPosition()
                        : slot.light instanceof SpotLight ? ((SpotLight) slot.light).getPosition() : Vector3f.ZERO;
                put(bytes, row, 1, position.x, position.y, position.z, slot.settings.getShadowDistance());
                put(bytes, row, 33, slot.settings.getFilterRadius(), slot.settings.getStrength(), 0, 0);
                if (slot.renderer instanceof DirectionalLightShadowRenderer) {
                    float[] worldSplits = ((DirectionalLightShadowRenderer) slot.renderer).splitsArray;
                    Matrix4f projection = viewport.getCamera().getProjectionMatrix();
                    float[] clipSplits = new float[4];
                    for (int cascade = 0; cascade < slot.maps; cascade++) {
                        clipSplits[cascade] = -projection.m22 * worldSplits[cascade + 1] + projection.m23;
                    }
                    put(bytes, row, 2, clipSplits[0], clipSplits[1], clipSplits[2], clipSplits[3]);
                }
                for (int map = 0; map < slot.maps; map++) {
                    Matrix4f matrix = slot.renderer.lightViewProjectionsMatrices[map];
                    for (int column = 0; column < 4; column++) {
                        put(bytes, row, 3 + map * 4 + column, matrix.get(0, column), matrix.get(1, column),
                                matrix.get(2, column), matrix.get(3, column));
                    }
                    put(bytes, row, 27 + map, slot.x[map] / (float) width, slot.y[map] / (float) height,
                            slot.size / (float) width, slot.size / (float) height);
                    renderer.setViewPort(slot.x[map], slot.y[map], slot.size, slot.size);
                    bindTexture(renderer, 0, slot.renderer.shadowMaps[map]);
                    // Raw shaders must also follow Shader's m_/g_ uniform naming contract.
                    copyShader.getUniform("m_Depth").setValue(VarType.Int, 0);
                    renderer.setShader(copyShader);
                    renderer.renderMesh(quad, 0, 1, null);
                }
            }
            records.getImage().setUpdateNeeded();
            if (!indices.isEmpty()) {
                // Vertex-interpolated sums cannot apply different pixel visibility
                // per light. Select the material's existing pixel-lighting variant.
                manager.addForcedMatParam(pixelLighting);
                pixelOverride = true;
            }
        } finally {
            renderer.setFrameBuffer(previous);
            // Atlas tiles change the renderer viewport directly. Invalidate the
            // manager's cached viewport before restoring the main camera.
            viewport.getCamera().onViewPortChange();
            manager.setCamera(viewport.getCamera(), false,
                    viewport.getRenderTargetWidth(), viewport.getRenderTargetHeight());
        }
    }

    private static void put(ByteBuffer bytes, int row, int texel, float x, float y, float z, float w) {
        int offset = row + texel * 16;
        bytes.putFloat(offset, x);
        bytes.putFloat(offset + 4, y);
        bytes.putFloat(offset + 8, z);
        bytes.putFloat(offset + 12, w);
    }

    /** Binds shared resources after the material's texture units; returns the next free unit. */
    public int bind(Shader shader, int unit) {
        boolean use = enabled && !indices.isEmpty();
        shader.getUniform("g_ManagedShadows").setValue(VarType.Boolean, use);
        boolean useBaked = hasBakedShadowRecords();
        if (!use) {
            return unit;
        }
        bindTexture(manager.getRenderer(), unit, atlas);
        bindTexture(manager.getRenderer(), unit + 1, records);
        shader.getUniform("g_ShadowAtlas").setValue(VarType.Int, unit);
        shader.getUniform("g_ShadowRecords").setValue(VarType.Int, unit + 1);
        shader.getUniform("g_ShadowAtlasSize").setValue(VarType.Vector2, new Vector2f(width, height));
        shader.getUniform("g_ShadowRecordCount").setValue(VarType.Float, (float) records.getImage().getHeight());
        Matrix4f projection = viewport.getCamera().getViewProjectionMatrix();
        shader.getUniform("g_ShadowViewRow").setValue(VarType.Vector4,
                new Vector4f(projection.m20, projection.m21, projection.m22, projection.m23));
        if (useBaked) {
            bindTexture(manager.getRenderer(), unit + 2, bakedAtlas);
            shader.getUniform("g_BakedShadowAtlas").setValue(VarType.Int, unit + 2);
            Image image = bakedAtlas.getImage();
            shader.getUniform("g_BakedShadowAtlasSize").setValue(VarType.Vector2,
                    new Vector2f(image.getWidth(), image.getHeight()));
            return unit + 3;
        }
        return unit + 2;
    }

    private static void bindTexture(Renderer renderer, int unit, Texture texture) {
        try {
            renderer.setTexture(unit, texture);
        } catch (TextureUnitException exception) {
            throw new RendererException(exception);
        }
    }

    /** Restores the material overrides even if drawing failed. */
    public void finishFrame() {
        if (pixelOverride) {
            manager.removeForcedMatParam(pixelLighting);
            pixelOverride = false;
        }
    }

    private void dispose(Slot slot) {
        slot.renderer.cleanup();
        for (FrameBuffer buffer : slot.renderer.shadowFB) {
            manager.getRenderer().deleteFrameBuffer(buffer);
        }
        for (Texture2D map : slot.renderer.shadowMaps) {
            map.getImage().dispose();
        }
    }

    private void disposeAtlas() {
        if (target != null) {
            manager.getRenderer().deleteFrameBuffer(target);
            target = null;
        }
        if (atlas != null) {
            atlas.getImage().dispose();
            atlas = null;
        }
    }

    /** Releases viewport resources. RenderManager calls this when removing a viewport. */
    @Override
    public void close() {
        disposeBakedAtlas();
        finishFrame();
        for (Spatial root : observedRoots) {
            root.removeSceneChangeListener(catalogListener);
        }
        observedRoots = Collections.emptyList();
        catalogScenes = Collections.emptyList();
        catalog.clear();
        casters.clear();
        releaseIndirectCasters();
        assets = null;
        catalogDirty = true;
        for (Slot slot : slots.values()) {
            dispose(slot);
        }
        slots.clear();
        settings.clear();
        indices.clear();
        active.clear();
        disposeAtlas();
        if (records != null) {
            records.getImage().dispose();
            records = null;
        }
        if (copyShader != null) {
            manager.getRenderer().deleteShader(copyShader);
            copyShader = null;
        }
    }
}
