package com.jme3.environment.gi;

import com.jme3.asset.AssetKey;
import com.jme3.asset.AssetManager;
import com.jme3.export.JmeExporter;
import com.jme3.export.JmeImporter;
import com.jme3.export.binary.BinaryImporter;
import com.jme3.math.Transform;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Caps;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.Renderer;
import com.jme3.renderer.ViewPort;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mobility;
import com.jme3.scene.Spatial;
import com.jme3.scene.control.AbstractControl;
import com.jme3.texture.Texture.MinFilter;
import com.jme3.texture.Texture2D;
import com.jme3.util.clone.Cloner;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Persistent reference to an external static diffuse irradiance texture.
 * Automatic scene services request it near the main camera, with hysteresis
 * for unloading. All LODs reuse a resident lightmap while the receiver remains
 * static at its baked transform. A probe atlas is not needed for playback.
 * The automatic service may be disabled, or this control disabled/removed, to
 * release the map. Without a resident map, a static receiver
 * receives no diffuse probe-volume contribution. Dynamic and locally dynamic
 * receivers use the live volume when available.
 * <pre>{@code
 * // 第一步：使用 ProbeLightmapBaker 烘焙，将 ProbeLightmapAsset 另存为独立 J3O。
 * // 第二步：在场景线程标记静态，记录资产路径和烘焙时的世界变换，再保存场景。
 * geometry.setMobility(Mobility.Static);
 * geometry.addControl(new ProbeLightmapControl("GI/Lightmaps/floor.j3o",
 *         geometry.getWorldTransform(), 100f));
 * // 第三步：有 LOD 数据时切到 LOD 1，仍使用这张 UV2 光照贴图。
 * geometry.setLodLevel(1);
 * // 第四步：需要移动时改为 Dynamic，由实时漫反射探针体积接管。
 * geometry.setMobility(Mobility.Dynamic);
 * }</pre>
 */
public final class ProbeLightmapControl extends AbstractControl {
    private static final Logger LOG = Logger.getLogger(ProbeLightmapControl.class.getName());
    private String asset;
    private Transform bakedTransform;
    private float preloadDistance;
    private transient CompletableFuture<ProbeLightmapAsset> load;
    private transient Texture2D texture;
    private transient Texture2D directTexture;
    private transient String[] directShadowIds;
    private transient boolean directionalDirectBands;
    private transient boolean missing;

    public ProbeLightmapControl() {
    }

    public ProbeLightmapControl(String asset, Transform bakedTransform, float preloadDistance) {
        if (!Float.isFinite(preloadDistance) || preloadDistance <= 0f) {
            throw new IllegalArgumentException("Positive lightmap preload distance required");
        }
        this.asset = Objects.requireNonNull(asset);
        this.bakedTransform = bakedTransform.clone();
        this.preloadDistance = preloadDistance;
    }

    /**
     * Checks whether the renderer can clamp a texture to its stored mip range.
     * See {@link #updateResidency(AssetManager, Vector3f, boolean, boolean)}
     * for using this capability with a precomputed lightmap.
     *
     * @param renderer the active renderer
     * @return true if the partial mip chain can be sampled
     */
    public static boolean supportsMipSampling(Renderer renderer) {
        var caps = renderer.getCaps();
        return caps.contains(Caps.OpenGL20) || caps.contains(Caps.OpenGLES30);
    }

    /**
     * Updates regional residency on the scene's rendering thread with base-level filtering.
     * @param assets the asset manager for the external map
     * @param camera the world-space camera location
     * @param giAvailable whether baked GI playback is enabled, independently of live probe residency
     */
    public void updateResidency(AssetManager assets, Vector3f camera, boolean giAvailable) {
        updateResidency(assets, camera, giAvailable, false);
    }

    /**
     * Updates residency and the sampler on the scene's rendering thread.
     * When enabled, existing baked mip levels use trilinear filtering. An old
     * asset without stored mip levels retains its base-level filtering.
     * Manual callers enable mip playback only with renderers that can clamp
     * sampling to the stored levels (desktop OpenGL 2+ or OpenGL ES 3+).
     * Automatic scene services use
     * {@link com.jme3.post.WorldSettings#setLightmapMipEnabled(boolean)}.
     * <pre>{@code
     * // 第一步：自定义流送在渲染线程根据设置和 GPU 能力选择采样，每帧调用。
     * boolean useMip = settings.isLightmapMipEnabled()
     *         && ProbeLightmapControl.supportsMipSampling(renderer);
     * control.updateResidency(assetManager, camera.getLocation(), true, useMip);
     * // 第二步：下一帧传 false 即可恢复基级采样，不必卸载已有贴图。
     * control.updateResidency(assetManager, camera.getLocation(), true, false);
     * }</pre>
     *
     * @param assets the asset manager for the external map
     * @param camera the world-space camera location
     * @param giAvailable whether baked GI playback is enabled, independently of live probe residency
     * @param mipEnabled whether to sample mip levels already baked into the texture
     */
    public void updateResidency(AssetManager assets, Vector3f camera, boolean giAvailable, boolean mipEnabled) {
        if (!(spatial instanceof Geometry)) {
            return;
        }
        Geometry geometry = (Geometry) spatial;
        boolean compatible = giAvailable && isEnabled() && geometry.getMobility() == Mobility.Static
                && geometry.getWorldBound() != null && bakedTransform != null
                && geometry.getWorldTransform().equals(bakedTransform);
        float limit = texture == null && load == null ? preloadDistance : preloadDistance * 1.3f;
        if (!compatible || geometry.getWorldBound().distanceToEdge(camera) > limit) {
            clearResidency();
            missing = false;
            return;
        }
        if (load != null && load.isDone()) {
            ProbeLightmapAsset decoded = load.join();
            load = null;
            missing = decoded == null;
            if (decoded != null) {
                texture = decoded.getTexture();
                directTexture = decoded.getDirectTexture();
                directShadowIds = decoded.getDirectShadowIds();
                directionalDirectBands = decoded.usesDirectionalDirectBands();
                if (texture.getImage().hasMipmaps()) {
                    // The chart padding only guarantees the four bilinear taps at each level.
                    texture.setAnisotropicFilter(1);
                }
                geometry.setResidentProbeLightmap(texture, decoded.hasSkyBand());
            }
        } else if (texture == null && load == null && !missing && asset != null) {
            String key = asset;
            load = CompletableFuture.supplyAsync(() -> readTexture(assets, key));
        }
        if (texture != null && texture.getImage().hasMipmaps()) {
            MinFilter filter = mipEnabled ? MinFilter.Trilinear : MinFilter.BilinearNoMipMaps;
            if (texture.getMinFilter() != filter) {
                texture.setMinFilter(filter);
            }
        }
    }

    private static ProbeLightmapAsset readTexture(AssetManager assets, String key) {
        try {
            var info = assets.locateAsset(new AssetKey<>(key));
            if (info == null) {
                throw new IOException("Asset not found");
            }
            try (var input = info.openStream()) {
                return ProbeLightmapAsset.fromSaved(new BinaryImporter().load(input));
            }
        } catch (IOException | RuntimeException failure) {
            LOG.log(Level.WARNING, "Could not stream lightmap " + key, failure);
            return null;
        }
    }

    public void clearResidency() {
        if (load != null) {
            load.cancel(false);
            load = null;
        }
        if (texture != null) {
            if (spatial instanceof Geometry) {
                ((Geometry) spatial).setResidentProbeLightmap(null);
            }
            texture.getImage().dispose();
            texture = null;
        }
        if (directTexture != null) {
            directTexture.getImage().dispose();
            directTexture = null;
            directShadowIds = null;
            directionalDirectBands = false;
        }
    }

    /** @return the resident direct atlas, or null when this asset has no direct bands */
    public Texture2D getDirectTexture() {
        return directTexture;
    }

    /** @return the matching shadow snapshot identifier for the given direct band */
    public String getDirectShadowId(int band) {
        return directShadowIds == null || band < 0 || band >= directShadowIds.length ? null : directShadowIds[band];
    }

    /** @return number of resident direct bands */
    public int getDirectBandCount() {
        return directShadowIds == null ? 0 : directShadowIds.length;
    }

    /** @return whether the live shading normal must be applied to the direct bands */
    public boolean usesDirectionalDirectBands() {
        return directionalDirectBands;
    }

    @Override
    public void setSpatial(Spatial attached) {
        if (attached != spatial) {
            clearResidency();
            super.setSpatial(attached);
        }
    }

    @Override
    protected void controlUpdate(float tpf) {
    }

    @Override
    protected void controlRender(RenderManager manager, ViewPort viewport) {
    }

    @Override
    public void cloneFields(Cloner cloner, Object original) {
        super.cloneFields(cloner, original);
        bakedTransform = cloner.clone(bakedTransform);
        texture = null;
        directTexture = null;
        directShadowIds = null;
        directionalDirectBands = false;
        load = null;
        missing = false;
    }

    @Override
    public void write(JmeExporter exporter) throws IOException {
        super.write(exporter);
        var out = exporter.getCapsule(this);
        out.write(asset, "asset", null);
        out.write(bakedTransform, "bakedTransform", null);
        out.write(preloadDistance, "preloadDistance", 100f);
    }

    @Override
    public void read(JmeImporter importer) throws IOException {
        super.read(importer);
        var in = importer.getCapsule(this);
        asset = in.readString("asset", null);
        bakedTransform = (Transform) in.readSavable("bakedTransform", null);
        preloadDistance = in.readFloat("preloadDistance", 100f);
    }
}
