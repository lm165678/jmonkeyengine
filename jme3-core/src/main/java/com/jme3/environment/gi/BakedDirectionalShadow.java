package com.jme3.environment.gi;

import com.jme3.bounding.BoundingBox;
import com.jme3.bounding.BoundingVolume;
import com.jme3.export.JmeExporter;
import com.jme3.export.JmeImporter;
import com.jme3.export.Savable;
import com.jme3.light.DirectionalLight;
import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Matrix4f;
import com.jme3.math.Transform;
import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.renderer.Caps;
import com.jme3.renderer.Limits;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.queue.RenderQueue.ShadowMode;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Mobility;
import com.jme3.scene.Spatial;
import com.jme3.texture.FrameBuffer;
import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import com.jme3.texture.Texture2D;
import com.jme3.texture.image.ColorSpace;
import com.jme3.util.BufferUtils;
import com.jme3.util.clone.Cloner;
import com.jme3.util.clone.JmeCloneable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A world-space depth snapshot for one static directional light, shared by
 * surface Lightmaps and moving shadow receivers. The volume owns the snapshot.
 * Disabling or detaching the source light leaves saved surface diffuse intact.
 * Invalid snapshots leave managed shadows on their regular caster path.
 * <pre>{@code
 * // 第一步：在渲染线程、视口绘制之外，用已更新的场景和静态方向光捕获固定投影者。
 * BakedDirectionalShadow shadow = BakedDirectionalShadow.capture(manager, scene, sun, 2048);
 * // 第二步：通过 bakeDirect 用同一份遮挡烘焙表面直射，再发布光照贴图和快照。
 * Texture2D direct = ProbeLightmapBaker.bakeDirect(manager, assets, geometry, shadow, 256, 256);
 * ProbeLightmapAsset map = ProbeLightmapAsset.withDirect(indirectAndSky, true,
 *         java.util.List.of(direct), java.util.List.of(shadow));
 * volume.setBakedDirectionalShadows(new BakedDirectionalShadow[]{shadow});
 * // 第三步：保存独立贴图和场景 J3O。关灯只停止实时高光和动态投影。
 * // 清除快照或修改光源方向、颜色、强度后，启用的灯会恢复实时着色。
 * }</pre>
 */
public final class BakedDirectionalShadow implements Savable, JmeCloneable {
    /** Maximum number of baked directional lights per managed viewport and receiver. */
    public static final int MAX_LIGHTS = 4;
    private String id;
    private DirectionalLight light;
    private Vector3f direction;
    private ColorRGBA color;
    private float intensity = 1f;
    private Matrix4f projection;
    private Texture2D depth;
    private Caster[] casters = new Caster[0];
    private transient Set<Geometry> captured;

    public BakedDirectionalShadow() {
    }

    /** @return stable identifier linking the direct Lightmap to this shadow snapshot */
    public String getId() {
        return id;
    }

    /** @return the original scene light, matched by identity within the loaded scene */
    public DirectionalLight getLight() {
        return light;
    }

    /** @return shared nearest-filtered linear depth texture */
    public Texture2D getDepth() {
        return depth;
    }

    /** @return baked world-to-light clip transform, read-only */
    public Matrix4f getProjection() {
        return projection;
    }

    /** @return whether realtime shadow submission can substitute saved static depth */
    public boolean isValid(Set<Geometry> sceneCasters) {
        return light != null && light.isEnabled() && light.isCastShadow() && matchesBake(sceneCasters);
    }

    /**
     * Checks the saved lighting recipe and caster state, independent of the
     * realtime light's Enabled and Cast Shadow switches.
     * @param sceneCasters geometries that still belong to the render scene
     * @return whether the corresponding surface maps can be played back
     */
    public boolean matchesBake(Set<Geometry> sceneCasters) {
        if (depth == null || light == null
                || light.getMobility() != Mobility.Static || !direction.equals(light.getDirection())
                || !color.equals(light.getColor()) || intensity != light.getIntensity()) {
            return false;
        }
        for (Caster caster : casters) {
            if (!sceneCasters.contains(caster.geometry) || !caster.matches()) {
                return false;
            }
        }
        return true;
    }

    /** @return whether this geometry was included in the static projection */
    public boolean contains(Geometry geometry) {
        if (captured == null) {
            captured = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Caster caster : casters) {
                captured.add(caster.geometry);
            }
        }
        return captured.contains(geometry);
    }

    /**
     * Checks whether a subsequent volume bake can share this snapshot and its
     * identifier. Call on the render thread with updated scene transforms.
     */
    public boolean canReuse(Spatial root, int resolution) {
        if (depth == null || depth.getImage().getWidth() != resolution) {
            return false;
        }
        Set<Geometry> eligible = Collections.newSetFromMap(new IdentityHashMap<>());
        root.depthFirstTraversal(spatial -> {
            if (spatial instanceof Geometry && isStaticCaster((Geometry) spatial)) {
                eligible.add((Geometry) spatial);
            }
        });
        return eligible.size() == casters.length && isValid(eligible);
    }

    /**
     * Records UV2-only replacements installed by the bake transaction before publication.
     * Call on the render thread with an identity map of approved source-to-output meshes
     * keyed by the affected geometry. Other mesh replacements invalidate playback.
     */
    public void acceptUvRemaps(Map<Geometry, Mesh> sources) {
        for (Caster caster : casters) {
            if (sources.get(caster.geometry) == caster.mesh) {
                caster.mesh = caster.geometry.getMesh();
            }
        }
    }

    void bindBake(Material material) {
        material.setTexture("BakedShadowDepth", depth);
        material.setMatrix4("BakedShadowProjection", projection);
        int size = depth.getImage().getWidth();
        material.setVector2("BakedShadowSize", new Vector2f(size, size));
        material.setColor("BakedLightColor", color.mult(intensity));
    }

    /** Captures a fixed directional map on the render thread. See the class example. */
    public static BakedDirectionalShadow capture(RenderManager manager, Spatial root,
            DirectionalLight light, int size) {
        var renderer = manager.getRenderer();
        if (size < 64 || size > Math.min(4096, renderer.getLimits().getOrDefault(Limits.TextureSize, 0))
                || !renderer.getCaps().contains(Caps.OpenGL30)
                || !renderer.getCaps().contains(Caps.FloatTexture)
                || light.getMobility() != Mobility.Static || !light.isEnabled() || !light.isCastShadow()) {
            throw new IllegalArgumentException("Baked directional shadows require desktop OpenGL and a static casting light");
        }
        BakedDirectionalShadow result = new BakedDirectionalShadow();
        result.id = UUID.randomUUID().toString();
        result.light = light;
        result.direction = light.getDirection().clone();
        result.color = light.getColor().clone();
        result.intensity = light.getIntensity();
        var entries = new ArrayList<Caster>();
        root.depthFirstTraversal(spatial -> {
            if (spatial instanceof Geometry && isStaticCaster((Geometry) spatial)) {
                entries.add(new Caster((Geometry) spatial));
            }
        });
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("No fixed shadow casters were found");
        }
        result.casters = entries.toArray(new Caster[0]);
        Camera camera = fit(entries, result.direction, size);
        result.projection = camera.getViewProjectionMatrix().clone();
        FrameBuffer target = new FrameBuffer(size, size, 1);
        target.setDepthTarget(FrameBuffer.FrameBufferTarget.newTarget(Image.Format.Depth32F));
        target.addColorTarget(FrameBuffer.FrameBufferTarget.newTarget(Image.Format.RGBA8));
        ByteBuffer pixels = BufferUtils.createByteBuffer(size * size * Float.BYTES);
        FrameBuffer previousTarget = renderer.getCurrentFrameBuffer();
        Camera previousCamera = manager.getCurrentCamera();
        RenderState previousState = manager.getForcedRenderState();
        boolean previousPriority = manager.isForcedRenderStateOverridesTechnique();
        String previousTechnique = manager.getForcedTechnique();
        Material previousMaterial = manager.getForcedMaterial();
        try {
            Material fallback = new Material(entries.get(0).geometry.getMaterial().getMaterialDef().getAssetManager(),
                    "Common/MatDefs/Shadow/PreShadow.j3md");
            RenderState state = new RenderState();
            state.setFaceCullMode(RenderState.FaceCullMode.Off);
            state.setPolyOffset(0f, 0f);
            state.setColorWrite(false);
            manager.setForcedTechnique("PreShadow");
            manager.setForcedMaterial(fallback);
            manager.setForcedRenderState(state);
            manager.setForcedRenderStateOverridesTechnique(true);
            renderer.setFrameBuffer(target);
            manager.setCamera(camera, false);
            renderer.clearBuffers(false, true, false);
            for (Caster caster : entries) {
                Geometry proxy = new Geometry(caster.geometry.getName(), caster.mesh);
                proxy.setMaterial(caster.geometry.getMaterial());
                proxy.setLocalTransform(caster.transform);
                proxy.setIgnoreTransform(caster.ignoreTransform);
                for (var override : caster.geometry.getWorldMatParamOverrides()) {
                    proxy.addMatParamOverride(override);
                }
                proxy.updateGeometricState();
                manager.renderGeometry(proxy);
            }
            renderer.readFrameBufferWithFormat(target, pixels, Image.Format.Depth32F);
            result.depth = new Texture2D(new Image(Image.Format.R32F, size, size, pixels, ColorSpace.Linear));
            result.depth.setMinFilter(Texture.MinFilter.NearestNoMipMaps);
            result.depth.setMagFilter(Texture.MagFilter.Nearest);
            result.depth.setWrap(Texture.WrapMode.EdgeClamp);
            return result;
        } finally {
            if (result.depth == null) {
                BufferUtils.destroyDirectBuffer(pixels);
            }
            manager.setForcedTechnique(previousTechnique);
            manager.setForcedMaterial(previousMaterial);
            manager.setForcedRenderState(previousState);
            manager.setForcedRenderStateOverridesTechnique(previousPriority);
            renderer.setFrameBuffer(previousTarget);
            if (previousCamera != null) {
                previousCamera.onViewPortChange();
                manager.setCamera(previousCamera, false);
            }
            renderer.deleteFrameBuffer(target);
        }
    }

    private static boolean isStaticCaster(Geometry geometry) {
        ShadowMode mode = geometry.getShadowMode();
        if (geometry.getMobility() != Mobility.Static || geometry.getMaterial() == null
                || geometry.getMesh() == null || geometry.getMesh().isAnimated()
                || geometry.getMesh().getMode() != Mesh.Mode.Triangles
                || geometry.getMesh().getMorphTargets().length != 0
                || geometry.isGrouped() || geometry.getMaterial().isTransparent()
                || geometry.getMaterial().getAdditionalRenderState().getBlendMode() != RenderState.BlendMode.Off
                || geometry.getMaterial().getParam("AlphaDiscardThreshold") != null
                || !(geometry.getWorldBound() instanceof BoundingBox)
                || mode != ShadowMode.Cast && mode != ShadowMode.CastAndReceive) {
            return false;
        }
        for (Spatial ancestor = geometry; ancestor != null; ancestor = ancestor.getParent()) {
            if (ancestor.getCullHint() == Spatial.CullHint.Always
                    || ancestor.getControl(com.jme3.anim.SkinningControl.class) != null
                    || ancestor.getControl(com.jme3.anim.MorphControl.class) != null) {
                return false;
            }
        }
        return true;
    }

    private static Camera fit(ArrayList<Caster> entries, Vector3f direction, int size) {
        BoundingVolume bounds = entries.get(0).geometry.getWorldBound().clone(null);
        for (Caster caster : entries) {
            bounds.mergeLocal(caster.geometry.getWorldBound());
        }
        BoundingBox box = (BoundingBox) bounds;
        float radius = Math.max(1f, new Vector3f(box.getXExtent(), box.getYExtent(), box.getZExtent()).length());
        Camera camera = new Camera(size, size);
        camera.setParallelProjection(true);
        Vector3f up = Math.abs(direction.dot(Vector3f.UNIT_Y)) < 0.99f ? Vector3f.UNIT_Y : Vector3f.UNIT_X;
        camera.lookAtDirection(direction, up);
        camera.setLocation(box.getCenter().subtract(direction.mult(radius + 1f)));
        float extent = radius * 1.01f;
        camera.setFrustum(0.1f, 2f * radius + 2f, -extent, extent, extent, -extent);
        camera.update();
        return camera;
    }

    @Override
    public void write(JmeExporter exporter) throws IOException {
        var out = exporter.getCapsule(this);
        out.write(id, "id", null);
        out.write(light, "light", null);
        out.write(direction, "direction", null);
        out.write(color, "color", null);
        out.write(intensity, "intensity", 1f);
        out.write(projection, "projection", null);
        out.write(depth, "depth", null);
        out.write(casters, "casters", null);
    }

    @Override
    public void read(JmeImporter importer) throws IOException {
        var in = importer.getCapsule(this);
        id = in.readString("id", null);
        light = (DirectionalLight) in.readSavable("light", null);
        direction = (Vector3f) in.readSavable("direction", null);
        color = (ColorRGBA) in.readSavable("color", null);
        intensity = in.readFloat("intensity", 1f);
        projection = (Matrix4f) in.readSavable("projection", null);
        depth = (Texture2D) in.readSavable("depth", null);
        Savable[] stored = in.readSavableArray("casters", null);
        if (id == null || direction == null || color == null || projection == null || stored == null
                || depth == null || depth.getImage() == null || depth.getImage().getFormat() != Image.Format.R32F) {
            throw new IOException("Invalid baked directional shadow snapshot");
        }
        casters = new Caster[stored.length];
        for (int i = 0; i < stored.length; i++) {
            casters[i] = (Caster) stored[i];
        }
    }

    @Override
    public Object jmeClone() {
        BakedDirectionalShadow copy = new BakedDirectionalShadow();
        copy.id = id;
        copy.light = light;
        copy.direction = direction;
        copy.color = color;
        copy.intensity = intensity;
        copy.projection = projection;
        copy.depth = depth;
        copy.casters = casters;
        return copy;
    }

    @Override
    public void cloneFields(Cloner cloner, Object original) {
        light = cloner.clone(light);
        casters = cloner.clone(casters);
    }

    /** Scene-local identity and validity inputs for a captured caster. */
    public static final class Caster implements Savable, JmeCloneable {
        private Geometry geometry;
        private Mesh mesh;
        private Transform transform;
        private boolean ignoreTransform;

        public Caster() {
        }

        private Caster(Geometry geometry) {
            this.geometry = geometry;
            mesh = geometry.getMesh();
            transform = geometry.getWorldTransform().clone();
            ignoreTransform = geometry.isIgnoreTransform();
        }

        private boolean matches() {
            return geometry != null && geometry.getMesh() == mesh && isStaticCaster(geometry)
                    && ignoreTransform == geometry.isIgnoreTransform() && transform.equals(geometry.getWorldTransform());
        }

        @Override
        public void write(JmeExporter exporter) throws IOException {
            var out = exporter.getCapsule(this);
            out.write(geometry, "geometry", null);
            out.write(mesh, "mesh", null);
            out.write(transform, "transform", null);
            out.write(ignoreTransform, "ignoreTransform", false);
        }

        @Override
        public void read(JmeImporter importer) throws IOException {
            var in = importer.getCapsule(this);
            geometry = (Geometry) in.readSavable("geometry", null);
            mesh = (Mesh) in.readSavable("mesh", null);
            transform = (Transform) in.readSavable("transform", null);
            ignoreTransform = in.readBoolean("ignoreTransform", false);
        }

        @Override
        public Object jmeClone() {
            Caster copy = new Caster();
            copy.geometry = geometry;
            copy.mesh = mesh;
            copy.transform = transform;
            copy.ignoreTransform = ignoreTransform;
            return copy;
        }

        @Override
        public void cloneFields(Cloner cloner, Object original) {
            geometry = cloner.clone(geometry);
            mesh = cloner.clone(mesh);
        }
    }
}
