package jme3test.post;

import com.jme3.app.SimpleApplication;
import com.jme3.export.binary.BinaryExporter;
import com.jme3.input.KeyInput;
import com.jme3.input.controls.*;
import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.math.*;
import com.jme3.post.FilterPostProcessor;
import com.jme3.post.filters.TemporalAAFilter;
import com.jme3.renderer.*;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.shape.Box;
import com.jme3.system.*;
import com.jme3.texture.*;
import com.jme3.util.BufferUtils;
import java.nio.ByteBuffer;

/**
 * Native TAA example. Requires GL3.3, floating-point MRT and single-sample FPP.
 * Run: gradlew :jme3-examples:temporalAASmoke
 * Direct: java -cp runtime jme3test.post.TestTemporalAA --verify
 * Add -Dtaa.material=Lighting or PBR, -Dtaa.deform=skin or morph for variants.
 * Verification reads velocity/output pixels across four paths and both opaque
 * modes; success prints TEMPORAL_AA_GPU_PASS. No external assets required.
 * Static silhouette regression: gradlew :jme3-examples:temporalAAStabilitySmoke
 * or use --verify-stability. Four paths and two velocity modes each warm up for
 * 64 frames, then measure RGB ranges for 32 frames at 128x96; fail above .25.
 * This detects large jitter discontinuities, not zero flicker or full visual parity.
 * Stability PBR uses unit emissive intensity for the same unit-contrast edges.
 * Interactive: B toggles base-pass velocity, V translucent motion, T TAA, C cuts
 * history. Fly camera uses standard controls. This is native-resolution TAA, not
 * UE pixel parity, FSR, or a new depth prepass. GPU Scene remains native.
 * Variants: -Dtaa.gpu=false, -Dtaa.mdi=true, -Dtaa.compact=true, -Dtaa.hiz=true.
 */
public final class TestTemporalAA extends SimpleApplication {
    private static boolean verify;
    private static boolean stability;
    private final float[] stabilityMin = new float[128 * 96 * 3];
    private final float[] stabilityMax = new float[128 * 96 * 3];
    private TemporalAAFilter taa = new TemporalAAFilter();
    private FilterPostProcessor fpp;
    private int rawEdges;
    private float deformationShift;
    private final boolean cameraMotion = Boolean.getBoolean("taa.camera");
    private Geometry moving;
    private Geometry glass;
    private FrameBuffer output;
    private FrameBuffer motionRead;
    private int frame;
    private boolean failed;
    private Matrix4f projectionBefore;
    private final String deformation = System.getProperty("taa.deform", "rigid");
    private Matrix4f bone = new Matrix4f();
    private final RenderPath[] paths = {RenderPath.Forward, RenderPath.Deferred,
            RenderPath.TiledDeferredCPU, RenderPath.TiledDeferredGPU};

    public static void main(String[] args) {
        stability = args.length > 0 && "--verify-stability".equals(args[0]);
        verify = stability || (args.length > 0 && "--verify".equals(args[0]));
        TestTemporalAA app = new TestTemporalAA();
        AppSettings settings = new AppSettings(true);
        settings.setRenderer(AppSettings.LWJGL_OPENGL43);
        settings.setResolution(verify ? 128 : 960, verify ? 96 : 540);
        settings.setAudioRenderer(null);
        settings.setGammaCorrection(false);
        settings.setFrameRate(60);
        settings.setGpuSceneEnabled(Boolean.parseBoolean(System.getProperty("taa.gpu", "true")));
        app.setSettings(settings);
        app.setShowSettings(false);
        app.start(verify ? JmeContext.Type.OffscreenSurface : JmeContext.Type.Display);
    }

    @Override
    public void simpleInitApp() {
        getSceneServices().setEnabled(false);
        setDisplayFps(!verify);
        setDisplayStatView(false);
        cam.setLocation(new Vector3f(0, 0, 7));
        cam.lookAt(Vector3f.ZERO, Vector3f.UNIT_Y);
        projectionBefore = cam.getProjectionMatrix().clone();
        rootNode.addLight(new com.jme3.light.AmbientLight(ColorRGBA.White));
        // 第一步：建立能区分相机、物体与半透明层运动的场景。
        Geometry background = box("Background", new Vector3f(0, 0, -1), new Vector3f(4, 3, .1f), ColorRGBA.Blue);
        moving = box("Moving", Vector3f.ZERO, new Vector3f(.7f, .7f, .1f), ColorRGBA.Red);
        if (!"rigid".equals(deformation)) {
            com.jme3.scene.Mesh mesh = moving.getMesh();
            int vertices = mesh.getVertexCount();
            if ("skin".equals(deformation)) {
                byte[] indices = new byte[vertices * 4];
                float[] weights = new float[vertices * 4];
                for (int v = 0; v < vertices; v++) {
                    weights[v * 4] = 1;
                }
                mesh.setBuffer(com.jme3.scene.VertexBuffer.Type.HWBoneIndex, 4, BufferUtils.createByteBuffer(indices));
                mesh.setBuffer(com.jme3.scene.VertexBuffer.Type.HWBoneWeight, 4, weights);
                moving.getMaterial().setInt("NumberOfBones", 1);
                moving.getMaterial().setParam("BoneMatrices", com.jme3.shader.VarType.Matrix4Array, new Matrix4f[]{bone});
            } else if ("morph".equals(deformation)) {
                float[] delta = new float[vertices * 3];
                for (int v = 0; v < vertices; v++) {
                    delta[v * 3] = 1;
                }
                mesh.setBuffer(com.jme3.scene.VertexBuffer.Type.MorphTarget0, 3, delta);
                moving.getMaterial().setInt("NumberOfMorphTargets", 1);
                moving.getMaterial().setInt("NumberOfTargetsBuffers", 1);
                moving.getMaterial().setParam("MorphWeights", com.jme3.shader.VarType.FloatArray, new float[]{0});
            }
        }
        moving.addControl(new com.jme3.scene.control.AbstractControl() {
            @Override
            protected void controlUpdate(float tpf) {
            }
            @Override
            protected void controlRender(RenderManager manager, ViewPort viewport) {
                // Update late in the frame, as SkinningControl and MorphControl do.
                if ("skin".equals(deformation)) {
                    bone.setTranslation(deformationShift, 0, 0);
                } else if ("morph".equals(deformation)) {
                    moving.getMaterial().setParam("MorphWeights", com.jme3.shader.VarType.FloatArray,
                            new float[]{deformationShift});
                }
            }
        });
        if ("rigid".equals(deformation)) {
            // Use one shared batch so culling can reorder instances during compaction.
            for (int i = 0; i < 8; i++) {
                Geometry instance = new Geometry("Native sibling " + i,
                        i < 4 ? moving.getMesh() : new Box(.2f, .2f, .1f));
                instance.setMaterial(moving.getMaterial());
                instance.setLocalTranslation(i < 4 ? -2.4f + i * 1.6f : 30f + i, 2.3f, 0);
                rootNode.attachChild(instance);
            }
        }
        glass = box("Transparent", new Vector3f(1.5f, 0, 1), new Vector3f(.3f, .6f, .1f), new ColorRGBA(0, 1, 0, .7f));
        glass.setQueueBucket(RenderQueue.Bucket.Transparent);
        glass.getMaterial().getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
        glass.getMaterial().getAdditionalRenderState().setDepthWrite(false);
        // 第二步：TAA 放在后处理首位，后续可加入曝光与 Tonemap。
        fpp = new FilterPostProcessor(assetManager);
        fpp.setFrameBufferFormat(Image.Format.RGBA16F);
        fpp.setNumSamples(1);
        taa = BinaryExporter.saveAndLoad(assetManager, taa);
        fpp.addFilter(taa);
        viewPort.addProcessor(fpp);
        if (verify) {
            output = new FrameBuffer(128, 96, 1);
            output.addColorTarget(FrameBuffer.FrameBufferTarget.newTarget(new Texture2D(128, 96, Image.Format.RGBA32F)));
            viewPort.setOutputFrameBuffer(output);
        } else {
            // 第三步：运行时切换策略自动重置历史；传送用 resetHistory。
            inputManager.addMapping("BaseVelocity", new KeyTrigger(KeyInput.KEY_B));
            inputManager.addMapping("TransparentVelocity", new KeyTrigger(KeyInput.KEY_V));
            inputManager.addMapping("TemporalAA", new KeyTrigger(KeyInput.KEY_T));
            inputManager.addMapping("Cut", new KeyTrigger(KeyInput.KEY_C));
            inputManager.addListener((ActionListener) (name, pressed, tpf) -> {
                if (!pressed) {
                    return;
                }
                if ("BaseVelocity".equals(name)) {
                    taa.setBasePassOutputsVelocity(!taa.isBasePassOutputsVelocity());
                }
                if ("TransparentVelocity".equals(name)) {
                    taa.setTranslucentVelocityEnabled(!taa.isTranslucentVelocityEnabled());
                }
                if ("TemporalAA".equals(name)) {
                    taa.setEnabled(!taa.isEnabled());
                }
                if ("Cut".equals(name)) {
                    taa.resetHistory();
                }
            }, "BaseVelocity", "TransparentVelocity", "TemporalAA", "Cut");
        }
    }

    private Geometry box(String name, Vector3f position, Vector3f size, ColorRGBA color) {
        Geometry geometry = new Geometry(name, new Box(size.x, size.y, size.z));
        Material material = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        String surface = System.getProperty("taa.material", "Unshaded");
        if ("Lighting".equals(surface)) {
            material = new Material(assetManager, "Common/MatDefs/Light/Lighting.j3md");
            material.setBoolean("UseMaterialColors", true);
            material.setColor("Diffuse", color);
            material.setColor("Ambient", color);
        } else if ("PBR".equals(surface)) {
            material = new Material(assetManager, "Common/MatDefs/Light/PBRLighting.j3md");
            material.setColor("BaseColor", color);
            material.setColor("Emissive", color);
            if (stability) {
                material.setFloat("EmissiveIntensity", 1f);
            }
        } else {
            material.setColor("Color", color);
        }
        geometry.setMaterial(material);
        geometry.setLocalTranslation(position);
        rootNode.attachChild(geometry);
        return geometry;
    }

    @Override
    public void simpleUpdate(float tpf) {
        if (stability) {
            if (frame % 96 == 0) {
                viewPort.setRenderPath(paths[(frame / 96) / 2]);
                taa.setBasePassOutputsVelocity((frame / 96) % 2 == 0);
                taa.resetHistory();
                java.util.Arrays.fill(stabilityMin, Float.POSITIVE_INFINITY);
                java.util.Arrays.fill(stabilityMax, Float.NEGATIVE_INFINITY);
            }
            moving.setLocalRotation(new Quaternion().fromAngleAxis(.25f, Vector3f.UNIT_Z));
            return;
        }
        if (verify) {
            int stage = frame / 10;
            if (frame >= 225) {
                updateNativeHistory();
                return;
            }
            if (stage >= 16) {
                moving.setLocalTranslation(0, 0, 0);
                moving.setLocalRotation(new Quaternion().fromAngleAxis(.25f, Vector3f.UNIT_Z));
                bone.loadIdentity();
                deformationShift = 0;
                if ("morph".equals(deformation)) {
                    moving.getMaterial().setParam("MorphWeights", com.jme3.shader.VarType.FloatArray, new float[]{0});
                }
                if (frame == 160) {
                    taa.setEnabled(false);
                }
                if (frame == 170) {
                    taa.setEnabled(true);
                }
                if (frame == 200) {
                    fpp.removeAllFilters();
                }
                if (frame == 205) {
                    fpp.addFilter(taa);
                }
                return;
            }
            if (frame % 10 == 0) {
                viewPort.setRenderPath(paths[(stage / 2) % 4]);
                var pipeline = renderManager.getPipeline(viewPort);
                if (pipeline instanceof com.jme3.renderer.gpuscene.GpuScenePipeline) {
                    var gpu = (com.jme3.renderer.gpuscene.GpuScenePipeline) pipeline;
                    gpu.setMultiDrawIndirect(Boolean.getBoolean("taa.mdi"));
                    gpu.setMeshMerging(Boolean.getBoolean("taa.mdi"));
                    gpu.setCommandCompaction(Boolean.getBoolean("taa.compact"));
                    gpu.setOcclusionCulling(Boolean.getBoolean("taa.hiz"));
                    if (Boolean.getBoolean("taa.hizHistory")) {
                        gpu.setHiZMode(com.jme3.renderer.gpuscene.HiZMode.HISTORY_TWO_PHASE);
                    }
                }
                taa.setTranslucentVelocityEnabled(stage < 8);
                taa.setBasePassOutputsVelocity(stage % 2 == 0);
                taa.resetHistory();
            }
            float shift = (frame % 10) * .025f;
            deformationShift = shift;
            if (cameraMotion) {
                cam.setLocation(new Vector3f(-(frame % 10) * .01f, 0, 7));
            }
            if ("skin".equals(deformation)) {
                bone.setTranslation(shift, 0, 0);
            } else if ("morph".equals(deformation)) {
                moving.getMaterial().setParam("MorphWeights", com.jme3.shader.VarType.FloatArray, new float[]{shift});
            } else {
                moving.setLocalTranslation(shift, 0, 0);
            }
            glass.setLocalTranslation(1.5f - (frame % 10) * .02f, 0, 1);
        } else {
            moving.setLocalTranslation((float) Math.sin(timer.getTimeInSeconds()) * 1.3f, 0, 0);
        }
    }

    @Override
    public void simpleRender(RenderManager rm) {
        if (!verify || failed) {
            return;
        }
        try {
            if (stability) {
                verifyStability();
                return;
            }
            if (frame >= 225) {
                verifyNativeHistory();
                frame++;
                if (frame == 260) {
                    System.out.println("TEMPORAL_GPU_HISTORY_PASS");
                    System.out.println("TEMPORAL_AA_GPU_PASS");
                    stop();
                }
                return;
            }
            if (frame >= 160) {
                verifyLifecycle();
                frame++;
                if (frame == 225 && (!Boolean.parseBoolean(System.getProperty("taa.gpu", "true"))
                        || !"rigid".equals(deformation))) {
                    System.out.println("TEMPORAL_AA_GPU_PASS");
                    stop();
                }
                return;
            }
            Texture2D velocity = viewPort.getTemporalScene().getVelocity();
            if (motionRead == null) {
                motionRead = new FrameBuffer(128, 96, 1);
                motionRead.addColorTarget(FrameBuffer.FrameBufferTarget.newTarget(velocity));
            }
            ByteBuffer pixels = BufferUtils.createByteBuffer(128 * 96 * 16);
            renderer.readFrameBufferWithFormat(motionRead, pixels, Image.Format.RGBA32F);
            int at = (48 * 128 + 64) * 16;
            float vx = pixels.getFloat(at);
            float valid = pixels.getFloat(at + 12);
            if (frame % 10 == 0 && valid > .5f) {
                throw new AssertionError("Cut retained valid motion history");
            }
            if (frame % 10 > 1 && (!Float.isFinite(vx) || vx < .001f || vx > .02f || valid < .5f)) {
                throw new AssertionError("Wrong moving velocity stage=" + frame / 10 + " vx=" + vx + " valid=" + valid);
            }
            Vector3f glassScreen = cam.getScreenCoordinates(glass.getWorldTranslation());
            int glassAt = (Math.round(glassScreen.y) * 128 + Math.round(glassScreen.x)) * 16;
            float glassVx = pixels.getFloat(glassAt);
            if (!cameraMotion && frame % 10 > 1 && ((frame < 80 && glassVx > -.001f) || (frame >= 80 && Math.abs(glassVx) > .0001f))) {
                throw new AssertionError("Translucent switch failed frame=" + frame + " velocity=" + glassVx);
            }
            if (viewPort.getCamera() != cam || !cam.getProjectionMatrix().equals(projectionBefore)) {
                throw new AssertionError("Scene camera projection was not restored");
            }
            renderer.readFrameBufferWithFormat(output, pixels, Image.Format.RGBA32F);
            float red = pixels.getFloat(at);
            if (!Float.isFinite(red) || red < .8f) {
                throw new AssertionError("Temporal output lost visible red surface: " + red);
            }
            if (frame % 10 == 9) {
                if (Boolean.parseBoolean(System.getProperty("taa.gpu", "true"))) {
                    var pipeline = rm.getPipeline(viewPort);
                    var gpu = pipeline instanceof com.jme3.renderer.gpuscene.GpuScenePipeline
                            ? (com.jme3.renderer.gpuscene.GpuScenePipeline) pipeline : null;
                    if (gpu == null
                            || !gpu.isActive() || gpu.getInstanceCount() < 1 || gpu.readVisibleCount() < 1) {
                        throw new AssertionError("TAA lost native GPU Scene submission");
                    }
                }
                System.out.println("TEMPORAL_STAGE_PASS " + frame / 10 + " velocity=" + vx);
            }
            frame++;

        } catch (Throwable error) {
            failed = true;
            error.printStackTrace();
            stop();
            System.exit(1);
        }
    }

    private void verifyStability() {
        if (frame % 96 >= 64) {
            ByteBuffer pixels = BufferUtils.createByteBuffer(128 * 96 * 16);
            renderer.readFrameBufferWithFormat(output, pixels, Image.Format.RGBA32F);
            for (int p = 0; p < 128 * 96; p++) {
                for (int c = 0; c < 3; c++) {
                    float value = pixels.getFloat(p * 16 + c * 4);
                    if (!Float.isFinite(value)) {
                        throw new AssertionError("Non-finite temporal output");
                    }
                    int index = p * 3 + c;
                    stabilityMin[index] = Math.min(stabilityMin[index], value);
                    stabilityMax[index] = Math.max(stabilityMax[index], value);
                }
            }
        }
        if (frame % 96 == 95) {
            float worst = 0;
            float centerWorst = 0;
            int worstAt = 0;
            int unstable = 0;
            for (int i = 0; i < stabilityMin.length; i++) {
                float range = stabilityMax[i] - stabilityMin[i];
                if (range > worst) {
                    worstAt = i;
                    worst = range;
                }
                int pixel = i / 3;
                if (pixel % 128 > 44 && pixel % 128 < 81 && pixel / 128 > 29 && pixel / 128 < 67) {
                    centerWorst = Math.max(centerWorst, range);
                }
                if (range > .25f) {
                    unstable++;
                }
            }
            System.out.println("TEMPORAL_STABILITY stage=" + frame / 96 + " range=" + worst + " unstable=" + unstable + " pixel=" + (worstAt / 3 % 128) + "," + (worstAt / 3 / 128) + " center=" + centerWorst);
            if (unstable != 0) {
                throw new AssertionError("Static silhouette changes by more than .25 across the jitter cycle");
            }
        }
        frame++;
        if (frame == 768) {
            System.out.println("TEMPORAL_STABILITY_PASS");
            stop();
        }
    }

    private void updateNativeHistory() {
        if (frame == 225) {
            cam.setLocation(new Vector3f(0, 0, 7));
            viewPort.setRenderPath(RenderPath.Forward);
            taa.setBasePassOutputsVelocity(true);
            moving.setLocalRotation(Quaternion.IDENTITY);
        }
        if (frame == 230) {
            moving.removeFromParent();
        }
        if (frame == 231) {
            moving = new Geometry("Replacement", moving.getMesh());
            moving.setMaterial(boxMaterialForReplacement());
            rootNode.attachChild(moving);
        }
        if (frame == 235) {
            moving.setLocalTranslation(100, 0, 0);
        }
        if (frame == 236) {
            moving.setLocalTranslation(0, 0, 0);
        }
        if (frame == 240) {
            moving.setMaterial(moving.getMaterial().clone());
        }
        if (frame == 245) {
            moving.setLocalTranslation(.03f, 0, 0);
        }
        if (frame == 250) {
            taa.setEnabled(false);
        }
        if (frame == 255) {
            taa.setEnabled(true);
        }
    }

    private Material boxMaterialForReplacement() {
        Geometry reference = box("Temporary", Vector3f.ZERO, new Vector3f(.7f, .7f, .1f), ColorRGBA.Red);
        reference.removeFromParent();
        return reference.getMaterial();
    }

    private void verifyNativeHistory() {
        var pipeline = renderManager.getPipeline(viewPort);
        var gpu = pipeline instanceof com.jme3.renderer.gpuscene.GpuScenePipeline
                ? (com.jme3.renderer.gpuscene.GpuScenePipeline) pipeline : null;
        if (gpu == null
                || !gpu.isActive() || (frame != 230 && !gpu.ownsTemporalGeometry(moving))) {
            throw new AssertionError("Native instance registration lost at " + frame);
        }
        if ((frame >= 250 && frame < 255) || frame == 230 || frame == 235) {
            return;
        }
        FrameBuffer read = new FrameBuffer(128, 96, 1);
        read.addColorTarget(FrameBuffer.FrameBufferTarget.newTarget(viewPort.getTemporalScene().getVelocity()));
        ByteBuffer pixels = BufferUtils.createByteBuffer(128 * 96 * 16);
        renderer.readFrameBufferWithFormat(read, pixels, Image.Format.RGBA32F);
        read.dispose();
        int at = (48 * 128 + 64) * 16;
        float valid = pixels.getFloat(at + 12);
        float velocity = pixels.getFloat(at);
        boolean invalid = frame == 225 || frame == 231 || frame == 236 || frame == 240 || frame == 255;
        if ((valid < .5f) != invalid) {
            throw new AssertionError("Wrong native history validity at " + frame + ": " + valid);
        }
        if (frame == 245 && velocity < .001f) {
            throw new AssertionError("Native move lost velocity");
        }
        if (frame == 246 && Math.abs(velocity) > .0001f) {
            throw new AssertionError("Stopped native instance retained velocity: " + velocity);
        }
    }

    private void verifyLifecycle() {
        boolean inactive = frame < 170 || (frame >= 200 && frame < 205);
        if ((viewPort.getTemporalScene() == null) != inactive || viewPort.getCamera() != cam
                || !cam.getProjectionMatrix().equals(projectionBefore)) {
            throw new AssertionError("Temporal lifecycle restoration failed at " + frame);
        }
        if (frame == 169 || frame == 199) {
            ByteBuffer pixels = BufferUtils.createByteBuffer(128 * 96 * 16);
            renderer.readFrameBufferWithFormat(output, pixels, Image.Format.RGBA32F);
            int edges = 0;
            for (int y = 30; y < 66; y++) {
                for (int x = 45; x < 80; x++) {
                    float red = pixels.getFloat((y * 128 + x) * 16);
                    if (red > .05f && red < .95f) {
                        edges++;
                    }
                }
            }
            if (frame == 169) {
                rawEdges = edges;
            } else {
                if (edges < rawEdges + 4) {
                    throw new AssertionError("TAA did not accumulate edge coverage: " + rawEdges + " -> " + edges);
                }
                System.out.println("TEMPORAL_EDGE_PASS raw=" + rawEdges + " temporal=" + edges);
            }
        }
        if (frame == 224) {
            System.out.println("TEMPORAL_LIFECYCLE_PASS");
        }
    }

    @Override
    public void handleError(String message, Throwable error) {
        super.handleError(message, error);
        if (verify) {
            System.exit(1);
        }
    }
}
