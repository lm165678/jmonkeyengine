package com.jme3.environment.gi;

import com.jme3.asset.AssetKey;
import com.jme3.asset.AssetManager;
import com.jme3.export.JmeExporter;
import com.jme3.export.JmeImporter;
import com.jme3.export.binary.BinaryImporter;
import com.jme3.math.Transform;
import com.jme3.math.Vector3f;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mobility;
import com.jme3.scene.Spatial;
import com.jme3.scene.control.AbstractControl;
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
 * for unloading. Missing data, changed world transform, and nonstatic
 * mobility keep the ordinary probe path available.
 * <pre>{@code
 * // 第一步：使用 ProbeLightmapBaker 烘焙，将纹理另存为独立 J3O 资产。
 * // 第二步：在场景线程记录资产路径和烘焙时的世界变换，再保存场景。
 * geometry.addControl(new ProbeLightmapControl("GI/Lightmaps/floor.j3o",
 *         geometry.getWorldTransform(), 100f));
 * // 第三步：运行时自动预取；移除控制器恢复探针逐像素采样。
 * geometry.removeControl(ProbeLightmapControl.class);
 * }</pre>
 */
public final class ProbeLightmapControl extends AbstractControl {
    private static final Logger LOG = Logger.getLogger(ProbeLightmapControl.class.getName());
    private String asset;
    private Transform bakedTransform;
    private float preloadDistance;
    private transient CompletableFuture<Texture2D> load;
    private transient Texture2D texture;
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

    /** Updates this asset's regional residency on the scene's rendering thread. */
    public void updateResidency(AssetManager assets, Vector3f camera, boolean giAvailable) {
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
            texture = load.join();
            load = null;
            missing = texture == null;
            geometry.setResidentProbeLightmap(texture);
        } else if (texture == null && load == null && !missing && asset != null) {
            String key = asset;
            load = CompletableFuture.supplyAsync(() -> readTexture(assets, key));
        }
    }

    private static Texture2D readTexture(AssetManager assets, String key) {
        try {
            var info = assets.locateAsset(new AssetKey<>(key));
            if (info == null) {
                throw new IOException("Asset not found");
            }
            try (var input = info.openStream()) {
                return (Texture2D) new BinaryImporter().load(input);
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
