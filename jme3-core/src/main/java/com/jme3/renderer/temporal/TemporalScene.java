package com.jme3.renderer.temporal;

import com.jme3.asset.AssetManager;
import com.jme3.material.*;
import com.jme3.math.*;
import com.jme3.renderer.*;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.*;
import com.jme3.shader.VarType;
import com.jme3.texture.*;
import com.jme3.util.BufferUtils;
import java.nio.FloatBuffer;
import java.util.*;

/**
 * Viewport-owned motion inputs. Render-thread only, not serialized. Velocity stores
 * current-minus-previous unjittered UV in RG, previous device depth in B, validity
 * in A. Zero validity rejects history, including newly visible/unsupported meshes.
 * Main-pass MRT and separate opaque rendering share identical position snapshots.
 * Translucency uses a nearest alpha-tested motion layer, not blended velocities.
 * CPU position snapshots support built-in skinning/morphing on ordinary geometry.
 * GPU Scene packets keep native GPU-resident previous instance transforms.
 * Applications use TemporalAAFilter, rather than invoking this internal lifecycle.
 */
public final class TemporalScene implements AutoCloseable {
    private final ViewPort view;
    private final RenderManager manager;
    private final Material velocityMaterial;
    private final IdentityHashMap<Geometry, Pose> poses = new IdentityHashMap<>();
    private final List<MatParamOverride> overrides = new ArrayList<>();
    private final Matrix4f currentVP = new Matrix4f();
    private final Matrix4f previousVP = new Matrix4f();
    private final Matrix4f originalProjection = new Matrix4f();
    private Camera jitteredCamera;
    private Camera sceneCamera;
    private Texture2D velocity;
    private Texture2D velocityDepth;
    private FrameBuffer velocityBuffer;
    private boolean reset = true;
    private boolean basePass = true;
    private boolean gbufferPass = true;
    private boolean translucent = true;
    private boolean drawing;
    private int frame;
    private Camera lastCamera;
    private final List<Spatial> lastRoots = new ArrayList<>();
    private RenderPath lastPath;
    private int width;
    private int height;
    private float alphaThreshold = .5f;

    private static final class Pose {
        Mesh mesh;
        Matrix4f world = new Matrix4f();
        Matrix4f oldWorld = new Matrix4f();
        VertexBuffer previous;
        FloatBuffer positions;
        FloatBuffer sparePositions;
        VertexBuffer current;
        int frame;
        boolean valid;
        boolean mainWritten;
    }

    public TemporalScene(AssetManager assets, RenderManager manager, ViewPort view) {
        this.view = view;
        this.manager = manager;
        velocityMaterial = new Material(assets, "Common/MatDefs/Temporal/Velocity.j3md");
    }

    /**
     * Applies the motion output preference on the render thread before begin.
     * A main pass without enough color attachments uses the separate pass.
     * GBuffer eligibility is also checked independently for installed pipelines.
     *
     * @param basePass whether to prefer writing motion in the main geometry pass
     * @param translucent whether transparent draws contribute motion
     * @param alphaThreshold minimum alpha for a transparent motion draw
     */
    public void configure(boolean basePass, boolean translucent, float alphaThreshold) {
        RenderPath path = view.getRenderPath();
        if (path == RenderPath.Inherit) {
            path = manager.getRenderPath();
        }
        int attachments = manager.getRenderer().getLimits().getOrDefault(Limits.FrameBufferMrtAttachments, 1);
        boolean supportedBasePass = basePass && attachments >= (path == RenderPath.Forward ? 2 : 6);
        boolean supportedGBufferPass = supportedBasePass && attachments >= 6;
        if (this.basePass != supportedBasePass || this.gbufferPass != supportedGBufferPass
                || this.translucent != translucent) {
            resetHistory();
        }
        this.basePass = supportedBasePass;
        gbufferPass = supportedGBufferPass;
        this.translucent = translucent;
        this.alphaThreshold = alphaThreshold;
    }

    public boolean isBasePass() {

        return basePass;

    }

    /** @return whether the active preference and renderer support a GBuffer motion attachment */
    public boolean isGBufferPass() {
        return gbufferPass;
    }

    public Texture2D getVelocity() {
        return velocity;
    }
    public Texture2D getVelocityDepth() {
        return velocityDepth;
    }
    public Matrix4f getCurrentViewProjection() {
        return currentVP;
    }
    public Matrix4f getPreviousViewProjection() {
        return previousVP;
    }
    public boolean isReset() {
        return reset;
    }
    public void resetHistory() {
        reset = true;
    }

    public void begin(int width, int height, Vector2f jitter) {
        restoreCamera();
        if (velocity == null || this.width != width || this.height != height) {
            releaseTarget();
            this.width = width;
            this.height = height;
            velocity = new Texture2D(width, height, Image.Format.RGBA16F);
            velocity.setMinFilter(Texture.MinFilter.NearestNoMipMaps);
            velocity.setMagFilter(Texture.MagFilter.Nearest);
            velocityBuffer = new FrameBuffer(width, height, 1);
            velocityBuffer.addColorTarget(FrameBuffer.FrameBufferTarget.newTarget(velocity));
            velocityDepth = new Texture2D(width, height, Image.Format.Depth24);
            velocityBuffer.setDepthTarget(FrameBuffer.FrameBufferTarget.newTarget(velocityDepth));
            resetHistory();
        }
        Camera camera = view.getCamera();
        if (camera != lastCamera || !lastRoots.equals(view.getScenes()) || lastPath != view.getRenderPath()) {
            resetHistory();
        }
        lastCamera = camera;
        lastRoots.clear();
        lastRoots.addAll(view.getScenes());
        lastPath = view.getRenderPath();
        currentVP.set(camera.getViewProjectionMatrix());
        if (reset) {
            previousVP.set(currentVP);
        }
        frame++;
        Renderer renderer = manager.getRenderer();
        FrameBuffer old = renderer.getCurrentFrameBuffer();
        renderer.setFrameBuffer(velocityBuffer);
        renderer.setBackgroundColor(ColorRGBA.BlackNoAlpha);
        renderer.clearBuffers(true, true, false);
        renderer.setFrameBuffer(old);
        renderer.setBackgroundColor(view.getBackgroundColor());
        sceneCamera = camera;
        jitteredCamera = camera.clone();
        originalProjection.set(camera.getProjectionMatrix());
        Matrix4f projection = originalProjection.clone();
        for (int c = 0; c < 4; c++) {
            projection.set(0, c, projection.get(0, c) + 2f * jitter.x / width * projection.get(3, c));
            projection.set(1, c, projection.get(1, c) + 2f * jitter.y / height * projection.get(3, c));
        }
        jitteredCamera.setProjectionMatrix(projection);
        view.setCamera(jitteredCamera);
    }

    /** Capture after scene controlRender has updated skin/morph buffers, before main draws. */
    public void captureScene() {
        for (Spatial root : view.getScenes()) {
            capture(root);
        }
        Iterator<Pose> stale = poses.values().iterator();
        while (stale.hasNext()) {
            Pose pose = stale.next();
            if (pose.frame != frame) {
                releasePose(pose);
                stale.remove();
            }
        }
    }

    /** Registers native indirect packets; source instances never need CPU vertex snapshots. */
    public void registerGpuPackets(Set<Geometry> sources, List<Geometry> packets) {
        Iterator<Map.Entry<Geometry, Pose>> iterator = poses.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Geometry, Pose> entry = iterator.next();
            if (sources.contains(entry.getKey())) {
                releasePose(entry.getValue());
                iterator.remove();
            }
        }
        for (Geometry packet : packets) {
            Pose pose = poses.computeIfAbsent(packet, key -> new Pose());
            pose.frame = frame;
            pose.valid = !reset && packet.getMaterial().getMaterialDef().getMaterialParam("TemporalVelocity") != null;
            pose.mainWritten = false;
        }
    }

    private void capture(Spatial spatial) {
        if (spatial.getCullHint() == Spatial.CullHint.Always) {
            return;
        }
        if (spatial instanceof Node) {
            for (Spatial child : ((Node) spatial).getChildren()) {
                capture(child);
            }
        } else if (spatial instanceof Geometry) {
            Geometry geometry = (Geometry) spatial;
            var pipeline = manager.getPipeline(view);
            if (pipeline instanceof com.jme3.renderer.gpuscene.GpuScenePipeline
                    && ((com.jme3.renderer.gpuscene.GpuScenePipeline) pipeline).ownsTemporalGeometry(geometry)) {
                return;
            }
            if (geometry.getQueueBucket() != RenderQueue.Bucket.Opaque
                    && geometry.getQueueBucket() != RenderQueue.Bucket.Transparent) {
                return;
            }
            if (Boolean.TRUE.equals(parameter(geometry, "UseInstancing"))) {
                throw new IllegalArgumentException("Temporal motion needs per-instance history; disable legacy UseInstancing for " + geometry.getName());
            }
            if (geometry.getMesh().getBuffer(VertexBuffer.Type.Position) == null) {
                return;
            }
            Pose pose = poses.computeIfAbsent(geometry, key -> new Pose());
            boolean compatible = pose.mesh == geometry.getMesh() && pose.frame == frame - 1;
            FloatBuffer current = samplePositions(geometry, pose.sparePositions);
            compatible &= pose.positions != null && pose.positions.limit() == current.limit();
            FloatBuffer old = compatible && !reset ? pose.positions : current;
            if (pose.previous == null) {
                pose.previous = new VertexBuffer(VertexBuffer.Type.PreviousPosition);
                pose.previous.setupData(VertexBuffer.Usage.Stream, 3, VertexBuffer.Format.Float, old);
            } else {
                pose.previous.updateData(old);
            }
            pose.valid = compatible && !reset
                    && geometry.getMaterial().getMaterialDef().getMaterialParam("TemporalVelocity") != null;
            pose.oldWorld.set(pose.valid ? pose.world : geometry.getWorldMatrix());
            pose.world.set(geometry.isIgnoreTransform() ? Matrix4f.IDENTITY : geometry.getWorldMatrix());
            pose.sparePositions = pose.positions;
            pose.positions = current;
            if (pose.current == null) {
                pose.current = new VertexBuffer(VertexBuffer.Type.Position);
                pose.current.setupData(VertexBuffer.Usage.Stream, 3, VertexBuffer.Format.Float, current);
            } else {
                pose.current.updateData(current);
            }
            pose.mesh = geometry.getMesh();
            pose.frame = frame;
            pose.mainWritten = false;
        }
    }

    /** Samples the same packed position morphs and four skin weights as built-in shaders. */
    static FloatBuffer samplePositions(Geometry geometry) {
        return samplePositions(geometry, null);
    }

    private static FloatBuffer samplePositions(Geometry geometry, FloatBuffer reuse) {
        Mesh mesh = geometry.getMesh();
        VertexBuffer source = mesh.getBuffer(VertexBuffer.Type.Position);
        int size = source.getNumElements() * 3;
        FloatBuffer result = reuse != null && reuse.capacity() == size ? reuse : BufferUtils.createFloatBuffer(size);
        result.clear();
        Matrix4f[] bones = (Matrix4f[]) parameter(geometry, "BoneMatrices");
        Object count = parameter(geometry, "NumberOfBones");
        if (!(count instanceof Number) || ((Number) count).intValue() <= 0) {
            bones = null;
        }
        float[] weights = (float[]) parameter(geometry, "MorphWeights");
        Object strideParam = parameter(geometry, "NumberOfTargetsBuffers");
        int stride = strideParam instanceof Number ? ((Number) strideParam).intValue() : 0;
        if (bones == null && (weights == null || stride <= 0) && source.getData() instanceof FloatBuffer
                && source.getNumComponents() == 3 && source.getStride() == 0 && source.getOffset() == 0) {
            FloatBuffer original = ((FloatBuffer) source.getData()).duplicate();
            original.position(0);
            original.limit(size);
            result.put(original).flip();
            return result;
        }
        VertexBuffer indices = mesh.getBuffer(VertexBuffer.Type.HWBoneIndex);
        VertexBuffer skinWeights = mesh.getBuffer(VertexBuffer.Type.HWBoneWeight);
        Vector3f p = new Vector3f();
        Vector3f sum = new Vector3f();
        Vector3f transformed = new Vector3f();
        for (int v = 0; v < source.getNumElements(); v++) {
            p.set(component(source, v, 0), component(source, v, 1), component(source, v, 2));
            if (weights != null && stride > 0) {
                for (int t = 0; t < weights.length && t * stride < 14; t++) {
                    VertexBuffer delta = mesh.getBuffer(VertexBuffer.Type.values()[VertexBuffer.Type.MorphTarget0.ordinal() + t * stride]);
                    if (delta != null) {
                        p.addLocal(weights[t] * component(delta, v, 0), weights[t] * component(delta, v, 1), weights[t] * component(delta, v, 2));
                    }
                }
            }
            if (bones != null && indices != null && skinWeights != null && component(skinWeights, v, 0) != 0f) {
                sum.set(0, 0, 0);
                for (int k = 0; k < 4; k++) {
                    float weight = component(skinWeights, v, k);
                    int index = ((Number) indices.getElementComponent(v, k)).intValue();
                    if (indices.getFormat() == VertexBuffer.Format.UnsignedByte) {
                        index &= 255;
                    } else if (indices.getFormat() == VertexBuffer.Format.UnsignedShort) {
                        index &= 65535;
                    }
                    if (weight != 0 && index >= 0 && index < bones.length) {
                        bones[index].mult(p, transformed);
                        sum.addLocal(transformed.multLocal(weight));
                    }
                }
                p.set(sum);
            }
            result.put(p.x).put(p.y).put(p.z);
        }
        result.flip();
        return result;
    }

    private static float component(VertexBuffer buffer, int v, int c) {
        return ((Number) buffer.getElementComponent(v, c)).floatValue();
    }

    private static Object parameter(Geometry geometry, String name) {
        Object value = geometry.getMaterial().getParamValue(name);
        for (MatParamOverride override : geometry.getWorldMatParamOverrides()) {
            if (override.isEnabled() && name.equals(override.getName())) {
                value = override.getValue();
            }
        }
        return value;
    }

    private void override(VarType type, String name, Object value) {
        MatParamOverride param = new MatParamOverride(type, name, value);
        overrides.add(param);
        manager.addForcedMatParam(param);
    }

    /** Wraps main geometry draws. Shadow and capture draws use their original transforms. */
    public Draw beginGeometry(Geometry geometry) {
        if (drawing || !basePass || geometry.getQueueBucket() != RenderQueue.Bucket.Opaque
                || geometry.getMaterial().getMaterialDef().getMaterialParam("TemporalVelocity") == null) {
            return null;
        }
        Pose pose = poses.get(geometry);
        FrameBuffer target = manager.getRenderer().getCurrentFrameBuffer();
        if (pose == null || target == null || target.getSamples() != 1) {
            return null;
        }
        boolean gbuffer = "GBuffer".equals(manager.getForcedTechnique());
        if (gbuffer && !gbufferPass) {
            return null;
        }
        // Multipass additive lighting would add velocity once per light. Use the
        // independent pass for that legacy forward mode instead.
        if (!gbuffer && (manager.getForcedTechnique() != null
                || manager.getPreferredLightMode() == TechniqueDef.LightMode.MultiPass)) {
            return null;
        }
        VertexBuffer previous = geometry.getMesh().getBuffer(VertexBuffer.Type.PreviousPosition);
        boolean gpu = geometry instanceof com.jme3.renderer.gpuscene.GpuSceneGeometry;
        if (!gpu) {
            geometry.getMesh().setBuffer(pose.previous);
        }
        bind(pose, gpu);
        boolean attached = !gbuffer && target.getNumColorTargets() == 1;
        boolean multi = target.isMultiTarget();
        if (attached) {
            target.addColorTarget(FrameBuffer.FrameBufferTarget.newTarget(velocity));
            target.setMultiTarget(true);
            manager.getRenderer().setFrameBuffer(target);
        }
        pose.mainWritten = true;
        return new Draw(geometry, previous, target, attached, multi);
    }

    private void bind(Pose pose, boolean gpu) {
        override(VarType.Boolean, "TemporalGpuInstances", gpu);
        override(VarType.Boolean, "TemporalVelocity", true);
        override(VarType.Matrix4, "TemporalCurrentVP", currentVP);
        override(VarType.Matrix4, "TemporalPreviousVP", previousVP);
        override(VarType.Matrix4, "TemporalPreviousWorld", pose.oldWorld);
        override(VarType.Boolean, "TemporalValid", pose.valid);
    }

    public final class Draw implements AutoCloseable {
        private final Geometry geometry;
        private final VertexBuffer previous;
        private final FrameBuffer target;
        private final boolean attached;
        private final boolean multi;
        Draw(Geometry geometry, VertexBuffer previous, FrameBuffer target, boolean attached, boolean multi) {
            this.geometry = geometry;
            this.previous = previous;
            this.target = target;
            this.attached = attached;
            this.multi = multi;
        }
        @Override
        public void close() {
            for (MatParamOverride override : overrides) {
                manager.removeForcedMatParam(override);
            }
            overrides.clear();
            geometry.getMesh().clearBuffer(VertexBuffer.Type.PreviousPosition);
            if (previous != null) {
                geometry.getMesh().setBuffer(previous);
            }
            if (attached) {
                target.removeColorTarget(target.getNumColorTargets() - 1);
                target.setMultiTarget(multi);
                manager.getRenderer().setFrameBuffer(target);
            }
        }
    }

    /** Appends missing opaque and explicitly selected translucent motion after scene color. */
    public void renderSupplement(boolean transparentPass) {
        FrameBuffer source = view.getOutputFrameBuffer();
        Renderer renderer = manager.getRenderer();
        if (source == null || source.getSamples() != 1) {
            throw new IllegalStateException("Temporal AA requires single-sample offscreen scene color");
        }
        if (transparentPass && !translucent) {
            return;
        }
        renderer.copyFrameBuffer(source, velocityBuffer, false, true);
        renderer.setFrameBuffer(velocityBuffer);
        // The jittered camera still has logical dimensions under FSR2.
        // Match the physical scene and motion targets to reuse the scene depth.
        manager.setCamera(view.getCamera(), false, width, height);
        drawing = true;
        try {
            for (Map.Entry<Geometry, Pose> entry : poses.entrySet()) {
                Geometry geometry = entry.getKey();
                Pose pose = entry.getValue();
                boolean transparent = geometry.getQueueBucket() == RenderQueue.Bucket.Transparent;
                if (transparent != transparentPass || (!transparent && geometry.getQueueBucket() != RenderQueue.Bucket.Opaque)
                        || (!transparent && basePass && pose.mainWritten)
                        || geometry.getCullHint() == Spatial.CullHint.Always) {
                    continue;
                }
                renderVelocity(geometry, pose, transparent);
            }
        } finally {
            drawing = false;
            renderer.setFrameBuffer(source);
        }
    }

    private void renderVelocity(Geometry geometry, Pose pose, boolean transparent) {
        Mesh mesh = geometry.getMesh();
        VertexBuffer original = mesh.getBuffer(VertexBuffer.Type.Position);
        VertexBuffer originalPrevious = mesh.getBuffer(VertexBuffer.Type.PreviousPosition);
        VertexBuffer current = pose.current;
        Material material = geometry.getMaterial();
        Object map = parameter(geometry, "BaseColorMap");
        if (map == null) {
            map = parameter(geometry, "DiffuseMap");
        }
        if (map == null) {
            map = parameter(geometry, "ColorMap");
        }
        Object color = parameter(geometry, "BaseColor");
        if (color == null) {
            color = parameter(geometry, "Diffuse");
        }
        if (color == null) {
            color = parameter(geometry, "Color");
        }
        Object discard = parameter(geometry, "AlphaDiscardThreshold");
        boolean gpu = geometry instanceof com.jme3.renderer.gpuscene.GpuSceneGeometry;
        velocityMaterial.setBoolean("UseInstancing", gpu);
        velocityMaterial.setBoolean("TemporalGpuInstances", gpu);
        velocityMaterial.setColor("Color", color instanceof ColorRGBA ? (ColorRGBA) color : ColorRGBA.White);
        if (map instanceof Texture) {
            velocityMaterial.setTexture("ColorMap", (Texture) map);
        } else {
            velocityMaterial.clearParam("ColorMap");
        }
        velocityMaterial.setFloat("AlphaThreshold", Math.max(transparent ? alphaThreshold : -1f,
                discard instanceof Number ? ((Number) discard).floatValue() : -1f));
        velocityMaterial.setBoolean("VertexColor", Boolean.TRUE.equals(parameter(geometry, "VertexColor"))
                || Boolean.TRUE.equals(parameter(geometry, "UseVertexColor")));
        velocityMaterial.setMatrix4("TemporalCurrentVP", currentVP);
        velocityMaterial.setMatrix4("TemporalPreviousVP", previousVP);
        velocityMaterial.setMatrix4("TemporalPreviousWorld", pose.oldWorld);
        velocityMaterial.setBoolean("TemporalValid", pose.valid);
        velocityMaterial.getAdditionalRenderState().setFaceCullMode(material.getAdditionalRenderState().getFaceCullMode());
        manager.setWorldMatrix(pose.world);
        if (gpu) {
            velocityMaterial.render(geometry, geometry.getWorldLightList(), manager);
            return;
        }
        try {
            mesh.clearBuffer(VertexBuffer.Type.Position);
            mesh.setBuffer(current);
            mesh.setBuffer(pose.previous);
            velocityMaterial.render(geometry, geometry.getWorldLightList(), manager);
        } finally {
            mesh.clearBuffer(VertexBuffer.Type.Position);
            mesh.setBuffer(original);
            mesh.clearBuffer(VertexBuffer.Type.PreviousPosition);
            if (originalPrevious != null) {
                mesh.setBuffer(originalPrevious);
            }

        }
    }

    public void restoreCamera() {
        if (jitteredCamera != null) {
            if (view.getCamera() == jitteredCamera) {
                view.setCamera(sceneCamera);
            }
            jitteredCamera = null;
        }
    }

    public void commit() {
        previousVP.set(currentVP);
        reset = false;
    }

    private void releasePose(Pose pose) {
        if (pose.previous != null) {
            pose.previous.dispose();
        }
        if (pose.current != null) {
            pose.current.dispose();
        }
        if (pose.positions != null) {
            BufferUtils.destroyDirectBuffer(pose.positions);
        }
        if (pose.sparePositions != null && pose.sparePositions != pose.positions) {
            BufferUtils.destroyDirectBuffer(pose.sparePositions);
        }
    }

    private void releaseTarget() {
        if (velocityBuffer != null) {
            velocityBuffer.dispose();
        }
        if (velocity != null) {
            velocity.getImage().dispose();
        }
        if (velocityDepth != null) {
            velocityDepth.getImage().dispose();
        }
    }

    @Override
    public void close() {
        restoreCamera();
        releaseTarget();
        velocityBuffer = null;
        velocity = null;
        for (Pose pose : poses.values()) {
            releasePose(pose);
        }
        poses.clear();
        if (view.getTemporalScene() == this) {
            view.setTemporalScene(null);
        }
    }
}
