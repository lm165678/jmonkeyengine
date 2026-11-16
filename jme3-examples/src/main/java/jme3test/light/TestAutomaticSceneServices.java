package jme3test.light;

import com.jme3.app.SimpleApplication;
import com.jme3.environment.gi.*;
import com.jme3.material.Material;
import com.jme3.math.*;
import com.jme3.renderer.*;
import com.jme3.scene.Geometry;
import com.jme3.scene.shape.Box;
import com.jme3.system.*;
import com.jme3.texture.Image;
import com.jme3.util.BufferUtils;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Automatic runtime GI from a real J3O with external bake assets. Entry main;
 * OpenGL 4.3 required, no input controls. Run
 * {@code gradlew :jme3-examples:automaticSceneServicesSmoke}.
 * Verifies four rendering paths, region blending, no repeated uploads, restoration
 * of material bindings and teardown after scene detachment. Boundary receivers
 * retain full GI; runtime intensity 1/2 updates only descriptors without rebaking. Fixture baking and
 * file writing are synchronous here only to keep this small test deterministic.
 * Applications only load and attach; SceneServicesState owns streaming lifecycle.
 */
public final class TestAutomaticSceneServices extends SimpleApplication {
    private static final RenderPath[] PATHS = {RenderPath.Forward, RenderPath.Deferred,
        RenderPath.TiledDeferredCPU, RenderPath.TiledDeferredGPU};
    private GiProbeVolume red;
    private GiProbeVolume green;
    private int waitFrames;
    private java.nio.file.Path directory;
    private long stableUploadBytes = -1;
    private int frame;

    public static void main(String[] args) {
        TestAutomaticSceneServices app = new TestAutomaticSceneServices();
        AppSettings settings = new AppSettings(true);
        settings.setRenderer(AppSettings.LWJGL_OPENGL43);
        settings.setResolution(240, 180);
        settings.setAudioRenderer(null);
        settings.setGpuSceneEnabled(true);
        app.setSettings(settings);
        app.setShowSettings(false);
        app.start(JmeContext.Type.OffscreenSurface);
    }

    @Override
    public void simpleInitApp() {
        // 第一步：区域 A 与 B 独立配置，在 x=[-1,1] 内重叠；B 使用更密的网格。
        red = new GiProbeVolume();
        red.configure(new Vector3f(4, 2, 4), 2, 2, 2);
        red.setLocalTranslation(-3, -1, -2);
        green = new GiProbeVolume();
        green.configure(new Vector3f(4, 2, 4), 5, 3, 3);
        green.setLocalTranslation(-1, -1, -2);
        rootNode.attachChild(red);
        rootNode.attachChild(green);
        rootNode.updateGeometricState();
        GiSceneBaker.Settings quality = new GiSceneBaker.Settings();
        quality.captureSize = 8;
        quality.radianceSamples = 256;
        quality.visibilitySamples = 4;
        quality.visibilityResolution = 4;
        // This synthetic background is the irradiance under test, not a SkyLight.
        quality.separateSky = false;
        quality.background = new ColorRGBA(0.4f, 0, 0, 1);
        // 第二步：分别烘焙区域光照；测试使用均匀背景，便于检查混合能量。
        GiSceneBaker.bake(renderManager, rootNode, red, quality, view -> { }, () -> false, count -> { });
        quality.compactStorage = false;
        quality.visibilityResolution = 8;
        quality.background = new ColorRGBA(0, 0.4f, 0, 1);
        GiSceneBaker.bake(renderManager, rootNode, green, quality, view -> { }, () -> false, count -> { });
        Material material = new Material(assetManager, "Common/MatDefs/Light/Lighting.j3md");
        material.setBoolean("UseMaterialColors", true);
        material.setColor("Diffuse", ColorRGBA.White);
        material.setColor("Ambient", ColorRGBA.Black);
        material.setColor("Specular", ColorRGBA.Black);
        Geometry floor = new Geometry("One receiver crossing both regions", new Box(4, 0.1f, 3));
        floor.setMaterial(material);
        floor.setLocalTranslation(0, -0.1f, 0);
        rootNode.attachChild(floor);
        // 第三步：模拟 SDK 保存带独立 GI 数据引用的 J3O。
        try {
            directory = java.nio.file.Files.createTempDirectory("jme-auto-scene-test");
            for (GiProbeVolume volume : new GiProbeVolume[]{red, green}) {
                String key = volume == red ? "red.giprobe" : "green.giprobe";
                GiProbeData data = volume.getData();
                try (var output = java.nio.file.Files.newOutputStream(directory.resolve(key))) {
                    data.saveCompressed(output);
                }
                volume.useStreamAsset(key, data);
                data.dispose();
            }
            com.jme3.export.binary.BinaryExporter.getInstance().save(rootNode, directory.resolve("scene.j3o").toFile());
            rootNode.detachAllChildren();
            assetManager.registerLocator(directory.toString(), com.jme3.asset.plugins.FileLocator.class);
            // 第四步：游戏端仅加载并挂载，不创建流送器、不手动绑定材质。
            rootNode.attachChild(assetManager.loadModel("scene.j3o"));
        } catch (java.io.IOException error) {
            throw new RuntimeException(error);
        }
        cam.setLocation(new Vector3f(0, 8, 0));
        cam.lookAt(Vector3f.ZERO, Vector3f.UNIT_Z);
        cam.setParallelProjection(true);
        cam.setFrustum(1, 20, -4, 4, 3, -3);
        setDisplayFps(false);
        setDisplayStatView(false);
    }

    @Override
    public void simpleUpdate(float tpf) {
        // 自动服务在应用的渲染准备阶段推进流送。
        // 第六步：强度在运行时调整，不重新烘焙或上传纹理。
        rootNode.depthFirstTraversal(spatial -> {
            if (spatial instanceof GiProbeVolume) {
                ((GiProbeVolume) spatial).setIntensity(frame < 32 ? 1 : 2);
            }
        });
        viewPort.setRenderPath(PATHS[(frame / 8) % 4]);
    }

    @Override
    public void simpleRender(RenderManager manager) {
        if (frame == 64) {
            if (getSceneServices().getGiStreamer() != null) {
                throw new AssertionError("Detached scene retained GI runtime");
            }
            System.out.println("AUTOMATIC_SCENE_SERVICES_PASS bytes=" + stableUploadBytes);
            stop();
            return;
        }
        GiProbeStreamer stream = getSceneServices().getGiStreamer();
        if (++waitFrames > 1800) {
            throw new AssertionError("Automatic GI did not become ready");
        }
        if (stream == null) {
            return;
        }
        if (!stream.getFailures().isEmpty()) {
            throw new AssertionError(stream.getFailures());
        }
        rootNode.depthFirstTraversal(spatial -> {
            if (spatial instanceof Geometry) {
                var count = ((Geometry) spatial).getMaterial().getParam("GiVolumeCount");
                if (count != null && ((Number) count.getValue()).intValue() > 0) {
                    throw new AssertionError("Transient GI binding escaped the render frame");
                }
            }
        });
        if (stream.getPublishedCount() != 2) {
            return;
        }
        if (stableUploadBytes < 0) {
            stableUploadBytes = stream.getUploadedBytes();
        } else if (frame == 32) {
            if (stream.getUploadedBytes() - stableUploadBytes != 128) {
                throw new AssertionError("Intensity must update only two 64-byte descriptors");
            }
            stableUploadBytes = stream.getUploadedBytes();
        } else if (stableUploadBytes != stream.getUploadedBytes()) {
            throw new AssertionError("Stationary pages uploaded again");
        }
        if (frame % 8 == 7) {
            ByteBuffer pixels = BufferUtils.createByteBuffer(240 * 180 * 4);
            renderer.readFrameBufferWithFormat(viewPort.getOutputFrameBuffer(), pixels, Image.Format.RGBA8);
            int expected = frame < 32 ? 102 : 204;
            int[] edge = sample(pixels, -2.95f);
            if (Math.abs(edge[0] - expected) > 3) {
                throw new AssertionError("Room boundary was attenuated: " + Arrays.toString(edge));
            }
            int[] left = sample(pixels, -2);
            int[] right = sample(pixels, 2);
            int[] center = sample(pixels, 0);
            if (left[0] < 90 || left[1] > 2 || right[1] < 90 || right[0] > 2
                    || center[0] < 5 || center[1] < 5) {
                throw new AssertionError("Incorrect regional GI selection: " + Arrays.toString(left)
                        + " / " + Arrays.toString(center) + " / " + Arrays.toString(right));
            }
            int[] previous = left;
            for (int step = 0; step <= 40; step++) {
                int[] color = sample(pixels, -2 + step * 0.1f);
                if (Math.abs(color[0] + color[1] - expected) > 4
                        || Math.abs(color[0] - previous[0]) > 60 || Math.abs(color[1] - previous[1]) > 60) {
                    throw new AssertionError("Discontinuous or unnormalized overlap: " + Arrays.toString(color));
                }
                previous = color;
            }
            int[] outside = sample(pixels, 3.5f);
            if (outside[0] + outside[1] > 2) {
                throw new AssertionError("GI escaped both coverage bounds");
            }
            System.out.println("GI REGIONS " + viewPort.getRenderPath() + " PASS " + Arrays.toString(center));
            if (frame == 63) {
                // 第五步：移除场景；下一帧自动释放页池和线程池。
                rootNode.detachAllChildren();
            }
        }
        frame++;
    }

    private int[] sample(ByteBuffer pixels, float x) {
        Vector3f screen = cam.getScreenCoordinates(new Vector3f(x, 0, 0));
        int pixel = ((int) screen.y * 240 + (int) screen.x) * 4;
        return new int[]{pixels.get(pixel) & 255, pixels.get(pixel + 1) & 255};
    }

    @Override
    public void destroy() {
        try {
            if (directory != null) {
                java.nio.file.Files.deleteIfExists(directory.resolve("scene.j3o"));
                java.nio.file.Files.deleteIfExists(directory.resolve("red.giprobe"));
                java.nio.file.Files.deleteIfExists(directory.resolve("green.giprobe"));
                java.nio.file.Files.deleteIfExists(directory);
            }
        } catch (java.io.IOException error) {
            throw new RuntimeException(error);
        }
        super.destroy();
    }

    @Override
    public void handleError(String message, Throwable error) {
        error.printStackTrace();
        System.exit(1);
    }
}
