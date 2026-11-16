package jme3test.light;

import com.jme3.app.SimpleApplication;
import com.jme3.environment.gi.GiAdaptiveBakeJob;
import com.jme3.environment.gi.GiAdaptiveProbeLayout;
import com.jme3.environment.gi.GiProbeData;
import com.jme3.environment.gi.GiProbeSet;
import com.jme3.environment.gi.GiProbeVolume;
import com.jme3.environment.gi.GiSceneBaker;
import com.jme3.export.binary.BinaryExporter;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.RenderPath;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.shape.Box;
import com.jme3.system.AppSettings;
import com.jme3.system.JmeContext;
import com.jme3.texture.Image;
import com.jme3.util.BufferUtils;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Runnable adaptive GI transaction/serialization/GPU regression. Entry main,
 * no controls; offscreen OpenGL 4.3, FloatTexture and FloatColorBufferRGBA required.
 * Run {@code gradlew :jme3-examples:giAdaptiveProbesSmoke}. Key APIs are
 * GiAdaptiveProbeLayout.Settings and GiAdaptiveBakeJob.start/step/close.
 * Checks cancellation keeps the previous bake, successful replacement publishes
 * coarse and fine volumes together, J3O round trips retain them, and constant
 * captured radiance remains stable on four rendering paths and both materials.
 * A second matrix uses assetManager.loadModel followed by root attachment and
 * default automatic services; incremental uploads are allowed to become ready.
 * Stabilized blending survives serialization and propagates to generated bricks.
 * The tiny capture is for automation, not a production quality recommendation.
 * Manual bindings below isolate GPU tests; normal SimpleApplication scenes use
 * automatic services. Restore those services only after closing manual bindings.
 */
public final class TestGiAdaptiveProbes extends SimpleApplication {
    private static byte[] serializedScene;
    private static final boolean SURFACE = Boolean.getBoolean("jme.test.surfaceProbes");

    /** In-memory test asset; production projects use their ordinary asset locator. */
    public static final class SceneLocator implements com.jme3.asset.AssetLocator {
        @Override
        public void setRootPath(String path) {
        }

        @Override
        public com.jme3.asset.AssetInfo locate(com.jme3.asset.AssetManager manager, com.jme3.asset.AssetKey key) {
            if (!"adaptive-roundtrip.j3o".equals(key.getName())) {
                return null;
            }
            return new com.jme3.asset.AssetInfo(manager, key) {
                @Override
                public java.io.InputStream openStream() {
                    return new ByteArrayInputStream(serializedScene);
                }
            };
        }
    }
    private static final RenderPath[] PATHS = {RenderPath.Forward, RenderPath.Deferred,
        RenderPath.TiledDeferredCPU, RenderPath.TiledDeferredGPU};
    private GiProbeVolume volume;
    private GiAdaptiveBakeJob job;
    private GiProbeSet atlas;
    private GiProbeSet.Binding binding;
    private Geometry receiver;
    private Node restoredScene;
    private int frame;
    private int reference = -1;
    private boolean automaticAttached;
    private int uploadWaitFrames;
    private final List<GiProbeData> owned = new ArrayList<>();

    public static void main(String[] args) {
        TestGiAdaptiveProbes app = new TestGiAdaptiveProbes();
        AppSettings settings = new AppSettings(true);
        settings.setRenderer(AppSettings.LWJGL_OPENGL43);
        settings.setResolution(64, 64);
        settings.setAudioRenderer(null);
        app.setSettings(settings);
        app.setShowSettings(false);
        app.start(JmeContext.Type.OffscreenSurface);
    }

    @Override
    public void simpleInitApp() {
        getSceneServices().setEnabled(false);
        setDisplayFps(false);
        setDisplayStatView(false);
        // 第一步：固定一个带局部几何的捕获场景，粗网格覆盖其他空旷区域。
        Geometry obstacle = new Geometry("refinement target", new Box(SURFACE ? 0.8f : 0.1f, 0.1f, SURFACE ? 0.8f : 0.1f));
        obstacle.setLocalTranslation(SURFACE ? new Vector3f(2, 1.4f, 2) : new Vector3f(1, 1, 1));
        Material unlit = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        unlit.setColor("Color", new ColorRGBA(0.2f, 0.2f, 0.2f, 1));
        obstacle.setMaterial(unlit);
        rootNode.attachChild(obstacle);
        volume = new GiProbeVolume();
        // 启用稳定混合并验证其随 J3O 与细化子节点保存；false 恢复严格混合。
        volume.setStabilizedBlending(true);

        volume.configure(new Vector3f(4, 4, 4), 3, 3, 3);
        volume.setSurfaceLayout(SURFACE);
        rootNode.attachChild(volume);
        rootNode.updateGeometricState();
        GiSceneBaker.Settings quality = new GiSceneBaker.Settings();
        quality.captureSize = 8;
        quality.visibilityResolution = 4;
        quality.visibilitySamples = 4;
        quality.radianceSamples = 256;
        quality.captureShadows = false;
        quality.separateSky = false;
        quality.background = new ColorRGBA(0.2f, 0.2f, 0.2f, 1);
        GiProbeData previous = GiSceneBaker.bake(renderManager, rootNode, volume, quality,
                view -> { }, () -> false, count -> { });
        owned.add(previous);
        GiAdaptiveProbeLayout.Settings layout = new GiAdaptiveProbeLayout.Settings();
        layout.maxRefinements = 1;
        // 第二步：验证取消事务不破坏旧数据，然后每帧推进完整事务。
        try (GiAdaptiveBakeJob cancelled = GiAdaptiveBakeJob.start(renderManager, rootNode, volume,
                quality, layout, view -> { }, () -> true, count -> { })) {
            try {
                cancelled.step(1_000_000);
                throw new AssertionError("Cancellation ignored");
            } catch (java.util.concurrent.CancellationException expected) {
                if (volume.getData() != previous || volume.getQuantity() != 0) {
                    throw new AssertionError("Cancellation modified live volume");
                }
            }
        }
        job = GiAdaptiveBakeJob.start(renderManager, rootNode, volume, quality, layout,
                view -> { }, () -> false, count -> { });
        cam.setLocation(new Vector3f(2, 3, 2));
        cam.lookAt(new Vector3f(2, 1.5f, 2), Vector3f.UNIT_Z);
    }

    @Override
    public void simpleUpdate(float tpf) {
        if (atlas == null) {
            if (!job.step(5_000_000)) {
                return;
            }
            // 第三步：检查细化的表面采样覆盖；预算不足时其余区域仍使用粗网格。
            GiAdaptiveProbeLayout.Plan plan = job.getLayoutPlan();
            if (!SURFACE && (plan.getSurfaceTargets() <= 0 || plan.getCoveredTargets() <= 0
                    || plan.getCoveredTargets() > plan.getSurfaceTargets())) {
                throw new AssertionError("Invalid surface coverage report");
            }
            if (job.getPublishedVolumes().size() != (SURFACE ? 1 : 2)) {
                throw new AssertionError("Expected coarse grid and one local refinement");
            }
            try {
                // 第三步：保存并重载普通 J3O，确认子块数据及世界位置保留。
                rootNode.updateGeometricState();
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                BinaryExporter.getInstance().save(volume, bytes);
                serializedScene = bytes.toByteArray();
                assetManager.registerLocator("adaptive-test", SceneLocator.class);
                Node restored = (Node) assetManager.loadModel("adaptive-roundtrip.j3o");
                assetManager.unregisterLocator("adaptive-test", SceneLocator.class);
                restoredScene = restored;
                restored.updateGeometricState();
                List<GiProbeSet.Entry> entries = new ArrayList<>();
                restored.depthFirstTraversal(spatial -> {
                    if (spatial instanceof GiProbeVolume) {
                        GiProbeVolume baked = (GiProbeVolume) spatial;
                        if (baked.getData() == null) {
                            throw new AssertionError("Lost baked placement after serialization");
                        }
                        if (baked.isSurfaceLayout() != SURFACE || baked.getData().isSurfaceLayout() != SURFACE) {
                            throw new AssertionError("Lost layout mode in J3O");
                        }
                        owned.add(baked.getData());
                        if (baked.isSmoothBlending()) {
                            throw new AssertionError("Removed smooth GI mode restored unexpectedly");
                        }
                        if (!baked.isStabilizedBlending()) {
                            throw new AssertionError("Lost serialized blending option");
                        }
                        entries.add(new GiProbeSet.Entry(baked.getData(), baked.getWorldTranslation(), 0, 1, true));
                    }
                });
                if (entries.size() != (SURFACE ? 1 : 2)) {
                    throw new AssertionError("Missing serialized refinement");
                }
                // Invalid refinement probes must not claim coverage or darken
                // the valid coarse/fine pair underneath them.
                GiProbeVolume invalid = new GiProbeVolume();
                invalid.configure(new Vector3f(2, 2, 2), 3, 3, 3);
                invalid.setLocalTranslation(1, 1, 1);
                invalid.updateGeometricState();
                GiSceneBaker.Settings invalidQuality = new GiSceneBaker.Settings();
                invalidQuality.captureSize = 8;
                invalidQuality.visibilityResolution = 4;
                invalidQuality.captureShadows = false;
                invalidQuality.validator = (scene, position, spacing, clearance, store) -> false;
                GiProbeData invalidData = GiSceneBaker.bake(renderManager, rootNode, invalid,
                        invalidQuality, view -> { }, () -> false, count -> { });
                owned.add(invalidData);
                entries.add(new GiProbeSet.Entry(invalidData, invalid.getWorldTranslation(), 0));
                atlas = new GiProbeSet(entries, 2048, 16L * 1024 * 1024);
                for (GiProbeVolume baked : job.getPublishedVolumes()) {
                    owned.add(baked.getData());
                }
            } catch (java.io.IOException error) {
                throw new RuntimeException(error);
            }
            receiver = new Geometry("receiver", new Box(0.8f, 0.1f, 0.8f));
            receiver.setLocalTranslation(2, 1.5f, 2);
            rootNode.attachChild(receiver);
        }
        if (frame == 24 && !automaticAttached) {
            automaticAttached = true;
            binding.close();
            binding = null;
            atlas.close();
            volume.removeFromParent();
            // 第五步：恢复自动服务。挂载重载的 J3O 后，不创建任何 GI 处理器。
            rootNode.attachChild(restoredScene);
            getSceneServices().setEnabled(true);
        }
        if (frame % 3 == 0) {
            if (binding != null) {
                binding.close();
            }
            boolean pbr = (frame / 12) % 2 == 1;
            Material material = new Material(assetManager, pbr
                    ? "Common/MatDefs/Light/PBRLighting.j3md" : "Common/MatDefs/Light/Lighting.j3md");
            if (pbr) {
                material.setFloat("Metallic", 0);
                material.setFloat("Roughness", 1);
            } else {
                material.setBoolean("UseMaterialColors", true);
                material.setColor("Diffuse", ColorRGBA.White);
                material.setColor("Ambient", ColorRGBA.Black);
                material.setColor("Specular", ColorRGBA.Black);
            }
            receiver.setMaterial(material);
            if (frame < 24) {
                binding = atlas.bind(material, 0.05f);
            }
            viewPort.setRenderPath(PATHS[frame / 3 % 4]);
        }
    }

    @Override
    public void simpleRender(RenderManager manager) {
        if (atlas == null) {
            return;
        }
        if (automaticAttached) {
            com.jme3.environment.gi.GiProbeStreamer stream = getSceneServices().getGiStreamer();
            if (stream == null || stream.getPublishedCount() < (SURFACE ? 1 : 2)) {
                if (++uploadWaitFrames > 300) {
                    throw new AssertionError("Automatic GI did not become resident: "
                            + (stream == null ? "no streamer" : stream.getFailures() + "/" + stream.getPageAdmissionFailures()));
                }
                return;
            }
        }
        if (frame % 3 == 2) {
            ByteBuffer pixels = BufferUtils.createByteBuffer(64 * 64 * 4);
            renderer.readFrameBufferWithFormat(viewPort.getOutputFrameBuffer(), pixels, Image.Format.RGBA8);
            int value = pixels.get((32 * 64 + 32) * 4) & 255;
            BufferUtils.destroyDirectBuffer(pixels);
            if (reference < 0) {
                reference = value;
            }
            if (value < 30 || Math.abs(value - reference) > 3) {
                throw new AssertionError("Adaptive irradiance changed across paths: " + value + "/" + reference);
            }
            System.out.println("GI_ADAPTIVE phase=" + frame / 3 + " value=" + value);
            if (frame == 47) {
                System.out.println(SURFACE ? "GI_SURFACE_PASS" : "GI_ADAPTIVE_PASS");
                stop();
            }
        }
        frame++;
    }

    @Override
    public void destroy() {
        getSceneServices().setEnabled(false);
        // 第四步：卸载时先恢复绑定，再释放本例拥有的资源。
        if (job != null) {
            job.close();
        }
        if (binding != null) {
            binding.close();
            atlas.close();
        }
        for (GiProbeData data : owned) {
            data.dispose();
        }
        super.destroy();
    }

    @Override
    public void handleError(String message, Throwable error) {
        error.printStackTrace();
        System.exit(1);
    }
}
