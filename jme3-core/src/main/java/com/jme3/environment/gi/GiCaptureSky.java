package com.jme3.environment.gi;

import com.jme3.bounding.BoundingBox;
import com.jme3.bounding.BoundingSphere;
import com.jme3.bounding.BoundingVolume;
import com.jme3.light.Light;
import com.jme3.light.LightList;
import com.jme3.light.SkyLight;
import com.jme3.material.MatParamOverride;
import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.math.FastMath;
import com.jme3.math.Matrix4f;
import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.jme3.math.Vector4f;
import com.jme3.renderer.Camera;
import com.jme3.renderer.Caps;
import com.jme3.renderer.Limits;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.Renderer;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Spatial;
import com.jme3.shader.VarType;
import com.jme3.texture.FrameBuffer;
import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import com.jme3.texture.Texture2D;
import com.jme3.texture.image.ColorSpace;
import com.jme3.util.BufferUtils;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Bake-owned directional depth samples for surface-to-sky visibility. The
 * orthographic captures are independent of probe position and reused across
 * all probe faces. No scene light or material is permanently modified.
 */
final class GiCaptureSky implements AutoCloseable {
    private static final int GRID = 4;
    private static final int COUNT = GRID * GRID;
    private static final int MAX_TILE = 512;
    private static final int MIN_TILE = 64;
    private static final String PRE_SHADOW = "PreShadow";

    private final RenderManager manager;
    private final Renderer renderer;
    private final List<Geometry> casters;
    private final BoundingBox bounds;
    private final int tileSize;
    private final int atlasSize;
    private final Camera camera;
    private final LightList noLights = new LightList(null);
    private final Material fallback;
    private final RenderState depthState = new RenderState();
    private final Vector4f[] rays = new Vector4f[COUNT];
    private final Matrix4f[] projections = new Matrix4f[COUNT];
    private final MatParamOverride depthOverride;
    private final MatParamOverride matricesOverride;
    private final MatParamOverride raysOverride;
    private final MatParamOverride scaleOverride;
    private final ByteBuffer atlasPixels;
    private FrameBuffer target;
    private ByteBuffer tilePixels;
    private Texture2D depth;
    private int nextTile;
    private boolean bound;
    private boolean closed;

    /**
     * Allocates capture resources only when an enabled diffuse sky and supported
     * depth casters are present. The working budget covers both atlas copies
     * and the one temporary tile. Call on the bake render thread.
     */
    static GiCaptureSky create(RenderManager manager, Spatial scene, long memoryBudget) {
        Renderer renderer = manager.getRenderer();
        if (!renderer.getCaps().contains(Caps.OpenGL30)
                || !renderer.getCaps().contains(Caps.FloatTexture)) {
            return null;
        }
        int tileSize = Math.min(MAX_TILE, renderer.getLimits().getOrDefault(Limits.TextureSize, 0) / GRID);
        tileSize = Integer.highestOneBit(tileSize);
        // CPU and GPU atlas, readback, Depth32F attachment, and RGBA8 attachment.
        while (tileSize >= MIN_TILE
                && (2L * COUNT + 3L) * tileSize * tileSize * Float.BYTES > memoryBudget) {
            tileSize >>= 1;
        }
        if (tileSize < MIN_TILE || !hasDiffuseSky(scene)) {
            return null;
        }
        List<Geometry> casters = new ArrayList<>();
        scene.depthFirstTraversal(spatial -> {
            if (spatial instanceof Geometry && isCaster((Geometry) spatial)) {
                casters.add((Geometry) spatial);
            }
        });
        if (casters.isEmpty()) {
            return null;
        }
        BoundingBox bounds = casterBounds(casters.get(0).getWorldBound());
        for (int i = 1; i < casters.size(); i++) {
            bounds.mergeLocal(casters.get(i).getWorldBound());
        }
        return new GiCaptureSky(manager, casters, bounds, tileSize);
    }

    private GiCaptureSky(RenderManager manager, List<Geometry> casters, BoundingBox bounds, int tileSize) {
        this.manager = manager;
        renderer = manager.getRenderer();
        this.casters = casters;
        this.bounds = bounds;
        this.tileSize = tileSize;
        atlasSize = GRID * tileSize;
        camera = new Camera(tileSize, tileSize);
        camera.setParallelProjection(true);
        fallback = new Material(casters.get(0).getMaterial().getMaterialDef().getAssetManager(),
                "Common/MatDefs/Shadow/PreShadow.j3md");
        depthState.setFaceCullMode(RenderState.FaceCullMode.Off);
        depthState.setColorWrite(false);
        depthState.setDepthTest(true);
        depthState.setDepthWrite(true);
        depthState.setPolyOffset(0f, 0f);
        float solidAngle = FastMath.TWO_PI * 2f / COUNT;
        float goldenAngle = FastMath.PI * (3f - FastMath.sqrt(5f));
        for (int i = 0; i < COUNT; i++) {
            float y = 1f - 2f * (i + 0.5f) / COUNT;
            float radius = FastMath.sqrt(1f - y * y);
            float angle = goldenAngle * i;
            rays[i] = new Vector4f(radius * FastMath.cos(angle), y, radius * FastMath.sin(angle), solidAngle);
        }
        depthOverride = new MatParamOverride(VarType.Texture2D, "GiCaptureSkyDepth", null);
        matricesOverride = new MatParamOverride(VarType.Matrix4Array, "GiCaptureSkyMatrices", projections);
        raysOverride = new MatParamOverride(VarType.Vector4Array, "GiCaptureSkyRays", rays);
        scaleOverride = new MatParamOverride(VarType.Vector2, "GiCaptureSkyTexelScale",
                new Vector2f(1f / atlasSize, 1f / atlasSize));
        target = new FrameBuffer(tileSize, tileSize, 1);
        target.setDepthTarget(FrameBuffer.FrameBufferTarget.newTarget(Image.Format.Depth32F));
        target.addColorTarget(FrameBuffer.FrameBufferTarget.newTarget(Image.Format.RGBA8));
        tilePixels = BufferUtils.createByteBuffer(tileSize * tileSize * Float.BYTES);
        atlasPixels = BufferUtils.createByteBuffer(atlasSize * atlasSize * Float.BYTES);
    }

    private static boolean hasDiffuseSky(Spatial scene) {
        Spatial root = scene;
        while (root.getParent() != null) {
            root = root.getParent();
        }
        boolean[] found = {false};
        root.depthFirstTraversal(spatial -> {
            if (!found[0]) {
                for (Light light : spatial.getLocalLightList()) {
                    if (light instanceof SkyLight) {
                        SkyLight sky = (SkyLight) light;
                        if (sky.isEnabled() && sky.isReady() && sky.isDiffuseEnabled() && sky.getIntensity() > 0f) {
                            found[0] = true;
                            break;
                        }
                    }
                }
            }
        });
        return found[0];
    }

    private static boolean isCaster(Geometry geometry) {
        Material material = geometry.getMaterial();
        Mesh mesh = geometry.getMesh();
        if (material == null || mesh == null || geometry.isGrouped() || material.isTransparent()
                || material.getAdditionalRenderState().getBlendMode() != RenderState.BlendMode.Off
                || geometry.getQueueBucket() != RenderQueue.Bucket.Opaque) {
            return false;
        }
        Mesh.Mode mode = mesh.getMode();
        if (mode != Mesh.Mode.Triangles && mode != Mesh.Mode.TriangleStrip && mode != Mesh.Mode.TriangleFan) {
            return false;
        }
        for (Spatial ancestor = geometry; ancestor != null; ancestor = ancestor.getParent()) {
            if (ancestor.getCullHint() == Spatial.CullHint.Always) {
                return false;
            }
        }
        BoundingVolume bound = geometry.getWorldBound();
        return (bound instanceof BoundingBox || bound instanceof BoundingSphere)
                && Vector3f.isValidVector(bound.getCenter());
    }

    private static BoundingBox casterBounds(BoundingVolume volume) {
        if (volume instanceof BoundingBox) {
            return (BoundingBox) volume.clone(null);
        }
        float radius = ((BoundingSphere) volume).getRadius();
        return new BoundingBox(volume.getCenter(), radius, radius, radius);
    }

    int getTileSize() {
        return tileSize;
    }

    /** Capture one tile. Returns true after all sixteen directions are ready. */
    boolean step() {
        if (closed) {
            throw new IllegalStateException("Sky capture is closed");
        }
        if (depth != null) {
            return true;
        }
        fitCamera(rays[nextTile]);
        projections[nextTile] = camera.getViewProjectionMatrix().clone();
        renderTile();
        copyTile(nextTile);
        if (++nextTile < COUNT) {
            return false;
        }
        atlasPixels.clear();
        depth = new Texture2D(new Image(Image.Format.R32F, atlasSize, atlasSize, atlasPixels, ColorSpace.Linear));
        depth.setMinFilter(Texture.MinFilter.NearestNoMipMaps);
        depth.setMagFilter(Texture.MagFilter.Nearest);
        depth.setWrap(Texture.WrapMode.EdgeClamp);
        depthOverride.setValue(depth);
        releaseTile();
        return true;
    }

    private void fitCamera(Vector4f ray) {
        Vector3f toSky = new Vector3f(ray.x, ray.y, ray.z);
        Vector3f up = Math.abs(toSky.y) < 0.99f ? Vector3f.UNIT_Y : Vector3f.UNIT_X;
        camera.lookAtDirection(toSky.negate(), up);
        float x = Math.max(0.01f, projectedExtent(camera.getLeft()));
        float y = Math.max(0.01f, projectedExtent(camera.getUp()));
        float distance = projectedExtent(toSky);
        // Leave a projected texel border to keep finite comparisons at edges.
        float border = 2f * Math.max(x, y) / tileSize;
        camera.setLocation(bounds.getCenter().add(toSky.mult(distance + 1f)));
        camera.setFrustum(0.01f, 2f * distance + 2f,
                -x - border, x + border, y + border, -y - border);
        camera.update();
    }

    private float projectedExtent(Vector3f axis) {
        return Math.abs(axis.x) * bounds.getXExtent()
                + Math.abs(axis.y) * bounds.getYExtent()
                + Math.abs(axis.z) * bounds.getZExtent();
    }

    private void renderTile() {
        FrameBuffer previousTarget = renderer.getCurrentFrameBuffer();
        Camera previousCamera = manager.getCurrentCamera();
        RenderState previousState = manager.getForcedRenderState();
        boolean previousPriority = manager.isForcedRenderStateOverridesTechnique();
        String previousTechnique = manager.getForcedTechnique();
        Material previousMaterial = manager.getForcedMaterial();
        try {
            manager.setForcedTechnique(PRE_SHADOW);
            manager.setForcedMaterial(fallback);
            manager.setForcedRenderState(depthState);
            manager.setForcedRenderStateOverridesTechnique(true);
            renderer.setFrameBuffer(target);
            manager.setCamera(camera, false);
            renderer.clearBuffers(false, true, false);
            for (Geometry caster : casters) {
                // A material's own PreShadow technique retains alpha cutout
                // and receives the original geometry's skinning overrides.
                manager.renderGeometry(caster, noLights);
            }
            tilePixels.clear();
            renderer.readFrameBufferWithFormat(target, tilePixels, Image.Format.Depth32F);
        } finally {
            manager.setForcedTechnique(previousTechnique);
            manager.setForcedMaterial(previousMaterial);
            manager.setForcedRenderState(previousState);
            manager.setForcedRenderStateOverridesTechnique(previousPriority);
            renderer.setFrameBuffer(previousTarget);
            if (previousCamera != null) {
                previousCamera.onViewPortChange();
                manager.setCamera(previousCamera, false);
            }
        }
    }

    private void copyTile(int tile) {
        int x = (tile % GRID) * tileSize;
        int y = (tile / GRID) * tileSize;
        int rowBytes = tileSize * Float.BYTES;
        for (int row = 0; row < tileSize; row++) {
            tilePixels.limit((row + 1) * rowBytes);
            tilePixels.position(row * rowBytes);
            atlasPixels.position(((y + row) * atlasSize + x) * Float.BYTES);
            atlasPixels.put(tilePixels);
        }
    }

    /** Bind only while the radiance capture viewport is drawing. */
    void bind() {
        if (closed || depth == null || bound) {
            throw new IllegalStateException("Sky capture must be ready and unbound");
        }
        manager.addForcedMatParam(depthOverride);
        manager.addForcedMatParam(matricesOverride);
        manager.addForcedMatParam(raysOverride);
        manager.addForcedMatParam(scaleOverride);
        bound = true;
    }

    void unbind() {
        if (bound) {
            manager.removeForcedMatParam(scaleOverride);
            manager.removeForcedMatParam(raysOverride);
            manager.removeForcedMatParam(matricesOverride);
            manager.removeForcedMatParam(depthOverride);
            bound = false;
        }
    }

    private void releaseTile() {
        if (target != null) {
            renderer.deleteFrameBuffer(target);
            target = null;
            BufferUtils.destroyDirectBuffer(tilePixels);
            tilePixels = null;
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        unbind();
        releaseTile();
        if (depth != null) {
            depth.getImage().dispose();
            depth = null;
            depthOverride.setValue(null);
        }
        BufferUtils.destroyDirectBuffer(atlasPixels);
        closed = true;
    }
}
