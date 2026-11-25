package jme3test.light;

import com.jme3.app.SimpleApplication;
import com.jme3.environment.gi.*;
import com.jme3.export.binary.BinaryExporter;
import com.jme3.export.binary.BinaryImporter;
import com.jme3.input.KeyInput;
import com.jme3.input.controls.KeyTrigger;
import com.jme3.material.Material;
import com.jme3.math.*;
import com.jme3.renderer.*;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mobility;
import com.jme3.scene.shape.Box;
import com.jme3.scene.plugins.sdf.*;
import com.jme3.system.*;
import com.jme3.texture.Image;
import com.jme3.util.BufferUtils;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;

/**
 * Runnable GI volume example: an emissive ceiling lights three built-in models.
 * Entry: main; requires OpenGL 3.3 with float textures/targets; the full smoke
 * matrix requests OpenGL 4.3 for GPU tiled lighting and GpuScene.
 * G toggles GI, SPACE cycles Forward/Deferred/CPU tiled/GPU tiled. WASD/mouse
 * move the camera. Bake advances across frames at startup, with no external assets.
 * Automated verification: {@code gradlew :jme3-examples:giProbeSmoke}.
 * Pass --stream for asynchronous compressed-chunk residency; verify with
 * {@code gradlew :jme3-examples:giProbeStreamingSmoke}. Two overlapping copies
 * deliberately test normalized blending without doubling the irradiance.
 * Checks real capture, serialization, GI-on/off pixel differences for each model,
 * render-path round trips, engine FG execution and concurrent global SDF updates.
 * GiSceneBaker, GiProbeVolume and GiProbeData.Binding are the application APIs.
 * Positive nonuniform volume scale is baked into probe spacing; the example
 * uses local coverage 4x3x3 and scale 2x1x2 for an effective 8x3x6 volume.
 * Rotation remains unsupported. Rebake after moving/scaling a volume.
 * No PBR environment LightProbe is created or modified.
 *
 * <p>Unshaded opts into additive GI, preserving its original unlit base. PBR and
 * standard Lighting receive diffuse irradiance/pi. This example has only an
 * emissive source, so it needs no capture shadow processors. For directional,
 * point or spot sources receive capture-owned managed shadows by default.
 */
public final class TestGiProbeVolume extends SimpleApplication {
    private static final RenderPath[] PATHS = {RenderPath.Forward, RenderPath.Deferred,
        RenderPath.TiledDeferredCPU, RenderPath.TiledDeferredGPU, RenderPath.Forward};
    private final List<Material> receivers = new ArrayList<>();
    private final List<GiProbeSet.Binding> bindings = new ArrayList<>();
    private GiProbeVolume volume;
    private GiProbeSet residentSet;
    private GiSceneBaker.Settings quality;
    private GiBakeJob bakeJob;
    private boolean resumed;
    private int bakingFrames;
    private GlobalDistanceField field;
    private boolean enabled = true;
    private boolean smoke;
    private boolean streaming;
    private boolean loading;
    private GiProbeStreamer streamer;
    private java.util.concurrent.ExecutorService io;
    private int frame;
    private int path;
    private byte[] reference;
    private byte[] lit;

    public static void main(String[] args) {
        TestGiProbeVolume app = new TestGiProbeVolume();
        app.smoke = Arrays.asList(args).contains("--smoke");
        app.streaming = Arrays.asList(args).contains("--stream");
        AppSettings settings = new AppSettings(true);
        settings.setRenderer(AppSettings.LWJGL_OPENGL43);
        settings.setResolution(600, 400);
        settings.setAudioRenderer(null);
        settings.setVSync(false);
        settings.setGraphicsDebug(true);
        settings.setGpuSceneEnabled(!Boolean.getBoolean("gi.disableGpuScene"));
        app.setSettings(settings);
        app.setShowSettings(false);
        if (app.smoke) {
            Thread timeout = new Thread(() -> {
                try {
                    Thread.sleep(180000);
                } catch (InterruptedException exception) {
                    return;
                }
                System.err.println("GI probe smoke timeout");
                System.exit(2);
            }, "gi-smoke-timeout");
            timeout.setDaemon(true);
            timeout.start();
        }
        app.start(app.smoke ? JmeContext.Type.OffscreenSurface : JmeContext.Type.Display);
    }

    @Override
    public void simpleInitApp() {
        // Low-level GI ownership test; ordinary loaded scenes use automatic services.
        getSceneServices().setEnabled(false);
        setDisplayFps(false);
        setDisplayStatView(false);
        flyCam.setMoveSpeed(5);
        cam.setLocation(new Vector3f(0, 3, 9));
        cam.lookAt(new Vector3f(0, 0.7f, 0), Vector3f.UNIT_Y);
        viewPort.setBackgroundColor(new ColorRGBA(0.01f, 0.01f, 0.01f, 1));
        // 第一步：三种内置材质均由 j3md 声明 GI 资源；无手动 shader ID 注册。
        Box mesh = new Box(0.65f, 0.65f, 0.65f);
        com.jme3.util.TangentBinormalGenerator.generate(mesh);
        ByteBuffer normalBytes = BufferUtils.createByteBuffer(4);
        normalBytes.put(new byte[]{(byte) 128, (byte) 128, (byte) 255, (byte) 255}).flip();
        com.jme3.texture.Texture2D normalMap = new com.jme3.texture.Texture2D(new Image(
                Image.Format.RGBA8, 1, 1, normalBytes, com.jme3.texture.image.ColorSpace.Linear));
        String[] definitions = {"Light/PBRLighting", "Light/Lighting", "Misc/Unshaded"};
        for (int i = 0; i < definitions.length; i++) {
            Material material = new Material(assetManager, "Common/MatDefs/" + definitions[i] + ".j3md");
            if (i == 0) {
                material.setColor("BaseColor", new ColorRGBA(0.6f, 0.6f, 0.6f, 1));
                material.setFloat("Metallic", 0);
                material.setFloat("Roughness", 1);
            } else if (i == 1) {
                material.setBoolean("UseMaterialColors", true);
                material.setColor("Diffuse", new ColorRGBA(0.6f, 0.6f, 0.6f, 1));
                material.setColor("Ambient", ColorRGBA.Black);
            } else {
                material.setColor("Color", new ColorRGBA(0.15f, 0.15f, 0.15f, 1));
            }
            receivers.add(material);
            if (i < 2) {
                material.setTexture("NormalMap", normalMap);
            }
            Geometry geometry = new Geometry("GI receiver " + i, mesh);
            geometry.setMobility(Mobility.Dynamic);
            geometry.setMaterial(material);
            geometry.setLocalTranslation((i - 1) * 2, 0.65f, 0);
            rootNode.attachChild(geometry);
            // 第二步：SDF 与 GI 同时存在；不关闭任意一方的渲染功能。
            if (i == 1) {
                geometry.updateGeometricState();
                SdfVolume sdf = SdfGenerator.generate(geometry,
                        new SdfSettings(8, 8, 8, 0.15f, SdfSettings.SignMode.UNSIGNED), null);
                geometry.setUserData(SdfVolume.USER_DATA_KEY, sdf);
            }
        }
        Geometry emitter = new Geometry("Emissive ceiling", new Box(4, 0.1f, 3));
        Material emission = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        emission.setColor("Color", new ColorRGBA(2, 1.2f, 0.5f, 1));
        emitter.setMaterial(emission);
        emitter.setLocalTranslation(0, 4, 0);
        rootNode.attachChild(emitter);
        // 第三步：体积可作为节点保存；覆盖尺寸和数量由独立属性控制。
        volume = new GiProbeVolume();
        volume.configure(new Vector3f(4, 3, 3), 5, 3, 4);
        // 第四步：缩放覆盖范围但保持数量，实际探针间距由世界缩放计算。
        volume.setLocalScale(2, 1, 2);
        volume.setLocalTranslation(-4, 0.2f, -3);
        rootNode.attachChild(volume);
        rootNode.updateGeometricState();
        java.nio.FloatBuffer tangents = mesh.getFloatBuffer(com.jme3.scene.VertexBuffer.Type.Tangent);
        java.nio.FloatBuffer normals = mesh.getFloatBuffer(com.jme3.scene.VertexBuffer.Type.Normal);
        for (int vertex = 0; vertex < mesh.getVertexCount(); vertex++) {
            Vector3f tangent = new Vector3f(tangents.get(vertex * 4), tangents.get(vertex * 4 + 1), tangents.get(vertex * 4 + 2));
            Vector3f normal = new Vector3f(normals.get(vertex * 3), normals.get(vertex * 3 + 1), normals.get(vertex * 3 + 2));
            if (!Vector3f.isValidVector(tangent) || tangent.lengthSquared() < 0.5f || Math.abs(tangent.dot(normal)) > 0.1f) {
                throw new AssertionError("Invalid receiver tangent: " + vertex + " " + tangent + " normal=" + normal);
            }
        }
        quality = new GiSceneBaker.Settings();
        quality.sceneRevision = "gi-example-v2";
        quality.captureSize = 32;
        quality.far = 30;
        quality.radianceSamples = 512;
        // 第四步：渲染线程烘焙，成功后发布；失败保留此前烘焙结果。
        bakeJob = GiSceneBaker.start(renderManager, rootNode, volume, quality, capture -> { },
                () -> false, completed -> { });
    }

    private void completeBake() {
        if (smoke) {
            GiProbeData previous = volume.getData();
            Camera previousCamera = renderManager.getCurrentCamera();
            com.jme3.texture.FrameBuffer previousBuffer = renderer.getCurrentFrameBuffer();
            int overrides = renderManager.getForcedMatParams().size();
            java.util.concurrent.atomic.AtomicInteger checks = new java.util.concurrent.atomic.AtomicInteger();
            try {
                GiSceneBaker.bake(renderManager, rootNode, volume, quality, capture -> { },
                        () -> checks.incrementAndGet() > 10, completed -> {
                            throw new AssertionError("Cancelled bake published a probe");
                        });
                throw new AssertionError("Cancellation did not stop the bake");
            } catch (java.util.concurrent.CancellationException expected) {
                if (volume.getData() != previous || renderManager.getCurrentCamera() != previousCamera
                        || renderer.getCurrentFrameBuffer() != previousBuffer
                        || renderManager.getForcedMatParams().size() != overrides) {
                    throw new AssertionError("Cancellation changed published data or render state");
                }
            }
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            BinaryExporter.getInstance().save(volume, bytes);
            rootNode.detachChild(volume);
            volume.getData().dispose();
            volume = (GiProbeVolume) BinaryImporter.getInstance().load(new ByteArrayInputStream(bytes.toByteArray()));
            rootNode.attachChild(volume);
            rootNode.updateGeometricState();
        } catch (IOException exception) {
            throw new RuntimeException(exception);
        }
        // 第五步：每个材质保留绑定句柄，G 键演示恢复以及重新绑定。
        // 同一材质按像素混合重叠体积；这里用相同数据验证混合不增亮。
        if (streaming) {
            // 第五步（流式模式）：压缩资产由工作线程解码，主线程只更新驻留集合。
            try {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                volume.getData().saveCompressed(bytes);
                byte[] compressed = bytes.toByteArray();
                io = java.util.concurrent.Executors.newFixedThreadPool(2);
                streamer = new GiProbeStreamer(io, 64L * 1024 * 1024,
                        renderer.getLimits().get(Limits.TextureSize), 200);
                Vector3f offset = volume.getWorldTranslation();
                for (int i = 0; i < 2; i++) {
                    streamer.register(new GiProbeStreamer.Chunk("overlap-" + i, offset.x, offset.y, offset.z,
                            volume.getCoverage(), volume.getData().getTextureBytes(),
                            () -> GiProbeData.loadCompressed(new ByteArrayInputStream(compressed))));
                }
                loading = true;
            } catch (IOException exception) {
                throw new RuntimeException(exception);
            }
        } else {
            residentSet = new GiProbeSet(Arrays.asList(
                new GiProbeSet.Entry(volume.getData(), volume.getWorldTranslation(), 1),
                new GiProbeSet.Entry(volume.getData(), volume.getWorldTranslation(), 2)),
                renderer.getLimits().get(Limits.TextureSize), 64L * 1024 * 1024);
        }
        bind();
        field = new GlobalDistanceField(16, 1, 0.5f);
        inputManager.addMapping("gi-toggle", new KeyTrigger(KeyInput.KEY_G));
        inputManager.addMapping("gi-path", new KeyTrigger(KeyInput.KEY_SPACE));
        inputManager.addListener((com.jme3.input.controls.ActionListener) (name, pressed, tpf) -> {
            if (!pressed) {
                return;
            }
            if (name.equals("gi-toggle")) {
                toggle();
            } else {
                path = (path + 1) % PATHS.length;
                viewPort.setRenderPath(PATHS[path]);
            }
        }, "gi-toggle", "gi-path");
    }

    private void bind() {
        for (Material material : receivers) {
            if (streamer != null) {
                streamer.addMaterial(material);
            } else {
                bindings.add(residentSet.bind(material, 0.03f));
            }
        }
    }

    private void toggle() {
        enabled = !enabled;
        if (enabled) {
            bind();
        } else {
            if (streamer != null) {
                for (Material material : receivers) {
                    streamer.removeMaterial(material);
                }
            }
            for (GiProbeSet.Binding binding : bindings) {
                binding.close();
            }
            bindings.clear();
        }
    }

    @Override
    public void simpleUpdate(float tpf) {
        if (bakeJob != null) {
            rootNode.updateGeometricState();
            bakingFrames++;
            if (smoke && !resumed && bakeJob.getCompletedProbeCount() >= 2) {
                try {
                    ByteArrayOutputStream checkpoint = new ByteArrayOutputStream();
                    bakeJob.saveCheckpoint(checkpoint);
                    int completed = bakeJob.getCompletedProbeCount();
                    bakeJob.close();
                    bakeJob = GiSceneBaker.start(renderManager, rootNode, volume, quality,
                            capture -> { }, () -> false, count -> { });
                    bakeJob.restoreCheckpoint(new ByteArrayInputStream(checkpoint.toByteArray()));
                    if (bakeJob.getCompletedProbeCount() != completed) {
                        throw new AssertionError("Checkpoint lost completed probes");
                    }
                    BitSet dirty = new BitSet();
                    dirty.set(0);
                    bakeJob.invalidate(dirty);
                    resumed = true;
                } catch (IOException error) {
                    throw new RuntimeException(error);
                }
            }
            if (!bakeJob.step(2_000_000L)) {
                return;
            }
            bakeJob = null;
            if (smoke && (bakingFrames < 2 || !resumed)) {
                throw new AssertionError("Did not exercise frame budgeting/checkpoint resume");
            }
            completeBake();
        }
        if (streamer != null) {
            streamer.update(cam.getLocation(), 0, 0, 0);
            if (!streamer.getFailures().isEmpty()) {
                throw new AssertionError("GI streaming failed: " + streamer.getFailures());
            }
            loading = streamer.getResidentCount() < 2;
            if (loading && smoke) {
                return;
            }
        }
        if (field != null) {
            rootNode.updateGeometricState();
            field.update(rootNode, cam.getLocation(), renderer);
        }
        if (smoke) {
            int phase = frame / 8;
            if (phase < PATHS.length) {
                viewPort.setRenderPath(PATHS[phase]);
            }
            if (frame == PATHS.length * 8) {
                toggle();
            }
            if (frame == PATHS.length * 8 + 8) {
                toggle();
                renderManager.setGpuSceneEnabled(false);
            }
            if (frame == PATHS.length * 8 + 16) {
                // Multi-pass GI must not be added once for every direct light.
                renderManager.setPreferredLightMode(com.jme3.material.TechniqueDef.LightMode.MultiPass);
                // clone() clears the technique cache, allowing preference reselection.
                Material multiPass = receivers.get(1).clone();
                multiPass.selectTechnique("Default", renderManager);
                if (multiPass.getActiveTechnique().getDef().getLightMode()
                        != com.jme3.material.TechniqueDef.LightMode.MultiPass) {
                    throw new AssertionError("MultiPass technique was not selected");
                }
                ((Geometry) rootNode.getChild("GI receiver 1")).setMaterial(multiPass);
                for (int i = 0; i < 4; i++) {
                    com.jme3.light.DirectionalLight light = new com.jme3.light.DirectionalLight();
                    light.setColor(ColorRGBA.Black);
                    light.setDirection(new Vector3f(1, -1, 0).normalizeLocal());
                    rootNode.addLight(light);
                }
            }
        }
    }

    @Override
    public void simpleRender(RenderManager manager) {
        if (!smoke || bakeJob != null || loading) {
            return;
        }
        if (frame % 8 == 7) {
            ByteBuffer bytes = BufferUtils.createByteBuffer(600 * 400 * 4);
            renderer.readFrameBufferWithFormat(viewPort.getOutputFrameBuffer(), bytes, Image.Format.RGBA8);
            byte[] pixels = new byte[bytes.capacity()];
            bytes.clear();
            bytes.get(pixels);
            if (frame == 7 || frame == PATHS.length * 8 + 7) {
                saveImage(pixels, enabled ? "gi-enabled.png" : "gi-disabled.png");
            }
            if (frame == 7) {
                reference = pixels;
                lit = pixels;
            } else if (frame < PATHS.length * 8 || frame >= PATHS.length * 8 + 15) {
                long difference = 0;
                for (int i = 0; i < pixels.length; i++) {
                    difference += Math.abs((pixels[i] & 255) - (reference[i] & 255));
                }
                double mean = difference / (double) pixels.length;
                if (mean > 1.0) {
                    throw new AssertionError("GI path pixel mismatch: " + mean);
                }
                System.out.println("GI path " + viewPort.getRenderPath() + " mean pixel error=" + mean);
            } else {
                // Compare projected receiver centers separately, not just an overall image sum.
                for (int receiver = 0; receiver < 3; receiver++) {
                    Vector3f screen = cam.getScreenCoordinates(new Vector3f((receiver - 1) * 2, 1.25f, 0));
                    long delta = 0;
                    long litValue = 0;
                    for (int y = (int) screen.y - 8; y <= (int) screen.y + 8; y++) {
                        for (int x = (int) screen.x - 8; x <= (int) screen.x + 8; x++) {
                            int pixel = (y * 600 + x) * 4;
                            delta += (lit[pixel] & 255) - (pixels[pixel] & 255);
                            litValue += lit[pixel] & 255;
                        }
                    }
                    if (delta < 100) {
                        throw new AssertionError("No GI contribution for model " + receiver + ": " + delta + " lit=" + litValue);
                    }
                }
            }
            if (manager.getViewPortFrameGraph(viewPort).getExecutedPasses().isEmpty()
                    || field.getInstanceCount() != 1) {
                throw new AssertionError("FG or SDF did not remain active");
            }
            com.jme3.renderer.pipeline.OpaquePipeline pipeline = manager.getPipeline(viewPort);
            if (frame < PATHS.length * 8 && manager.isGpuSceneEnabled()) {
                if (!(pipeline instanceof com.jme3.renderer.gpuscene.GpuScenePipeline)) {
                    throw new AssertionError("GpuScene decorator missing");
                }
                com.jme3.renderer.gpuscene.GpuScenePipeline gpu =
                        (com.jme3.renderer.gpuscene.GpuScenePipeline) pipeline;
                if (!gpu.isActive() || gpu.getInstanceCount() < 3) {
                    throw new AssertionError("GI receivers did not enter GpuScene");
                }
                pipeline = gpu.getShadingPipeline();
                if (viewPort.getRenderPath() != RenderPath.Forward) {
                    com.jme3.renderer.deferred.DeferredPipeline deferred =
                            pipeline instanceof com.jme3.renderer.deferred.DeferredPipeline
                            ? (com.jme3.renderer.deferred.DeferredPipeline) pipeline
                            : pipeline instanceof com.jme3.renderer.deferred.TiledDeferredProcessor
                            ? ((com.jme3.renderer.deferred.TiledDeferredProcessor) pipeline).getPipeline() : null;
                    if (deferred == null || deferred.getDeferredGeometryCount() < 3) {
                        throw new AssertionError("GI receivers fell back from deferred shading");
                    }
                    System.out.println("GI actual backend=" + deferred.getActivePath()
                            + " deferred=" + deferred.getDeferredGeometryCount() + " gpuInstances=" + gpu.getInstanceCount());
                }
            }
        }
//        if (++frame == PATHS.length * 8 + 24) {
//            System.out.println("GI PROBE SMOKE PASS");
//            stop();
//        }
    }

    private void saveImage(byte[] pixels, String name) {
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(600, 400,
                java.awt.image.BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 400; y++) {
            for (int x = 0; x < 600; x++) {
                int pixel = (y * 600 + x) * 4;
                image.setRGB(x, 399 - y, ((pixels[pixel] & 255) << 16)
                        | ((pixels[pixel + 1] & 255) << 8) | (pixels[pixel + 2] & 255));
            }
        }
        try {
            File directory = new File("build/gi-probe");
            directory.mkdirs();
            javax.imageio.ImageIO.write(image, "png", new File(directory, name));
        } catch (IOException exception) {
            throw new RuntimeException(exception);
        }
    }

    @Override
    public void handleError(String message, Throwable error) {
        super.handleError(message, error);
        if (smoke) {
            error.printStackTrace();
            System.exit(1);
        }
    }

    @Override
    public void destroy() {
        if (bakeJob != null) {
            bakeJob.close();
        }
        for (GiProbeSet.Binding binding : bindings) {
            binding.close();
        }
        if (residentSet != null) {
            residentSet.close();
        }
        if (streamer != null) {
            streamer.close();
            io.shutdownNow();
        }
        if (field != null) {
            field.close();
        }
        if (volume != null && volume.getData() != null) {
            volume.getData().dispose();
        }
        super.destroy();
    }
}
