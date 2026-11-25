package jme3test.light;

import com.jme3.app.SimpleApplication;
import com.jme3.environment.gi.*;
import com.jme3.material.Material;
import com.jme3.math.*;
import com.jme3.renderer.*;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mobility;
import com.jme3.scene.shape.Box;
import com.jme3.system.*;
import com.jme3.texture.Image;
import com.jme3.util.BufferUtils;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Disk-streamed mixed-resolution GI using stable GPU pages. Entry main; no input
 * controls, OpenGL 4.3 required. Run {@code gradlew :jme3-examples:giPagedStreamingSmoke}.
 * Two fixture bakes are saved to temporary compressed files, loaded by workers,
 * then incrementally uploaded with a 16 KiB / 2ms frame budget. Checks four render
 * paths, regional blending and zero reuploads during a stationary active set.
 * Initial allocation and individual driver calls may exceed the soft time budget.
 * SDK uses the same API and saves revisions under assets/GI/Streaming.
 */
public final class TestGiPagedStreaming extends SimpleApplication {
    private static final RenderPath[] PATHS = {RenderPath.Forward, RenderPath.Deferred,
        RenderPath.TiledDeferredCPU, RenderPath.TiledDeferredGPU};
    private GiProbeVolume red;
    private GiProbeVolume green;
    private GiProbeStreamer stream;
    private java.util.concurrent.ExecutorService io;
    private java.nio.file.Path directory;
    private long stableUploadBytes = -1;
    private int frame;

    public static void main(String[] args) {
        TestGiPagedStreaming app = new TestGiPagedStreaming();
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
        // Low-level GI ownership test; ordinary loaded scenes use automatic services.
        getSceneServices().setEnabled(false);
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
        floor.setMobility(Mobility.Dynamic);
        floor.setMaterial(material);
        floor.setLocalTranslation(0, -0.1f, 0);
        rootNode.attachChild(floor);
        // 第三步：同一材质绑定多个体积，由 shader 按像素选择与混合。
        // 第三步：保存独立分块（示例小数据）；实际编辑器在后台保存并原子提交。
        io = java.util.concurrent.Executors.newFixedThreadPool(2);
        stream = new GiProbeStreamer(io, 16L << 20,
                renderer.getLimits().get(Limits.TextureSize), 20, renderer);
        stream.setUploadBudget(16384, 2_000_000);
        stream.setUnloadMultiplier(1.3);
        try {
            directory = java.nio.file.Files.createTempDirectory("jme-gi-stream-test");
            for (GiProbeVolume volume : new GiProbeVolume[]{red, green}) {
                java.nio.file.Path path = directory.resolve(volume == red ? "red.giprobe" : "green.giprobe");
                try (var output = java.nio.file.Files.newOutputStream(path)) {
                    volume.getData().saveCompressed(output);
                }
                Vector3f position = volume.getWorldTranslation();
                stream.register(new GiProbeStreamer.Chunk(path.getFileName().toString(),
                        position.x, position.y, position.z, volume.getCoverage(),
                        volume.getData().getTextureBytes(), () -> {
                            try (var input = java.nio.file.Files.newInputStream(path)) {
                                return GiProbeData.loadCompressed(input);
                            }
                        }));
            }
        } catch (java.io.IOException error) {
            throw new RuntimeException(error);
        }
        stream.addMaterial(material);
        cam.setLocation(new Vector3f(0, 8, 0));
        cam.lookAt(Vector3f.ZERO, Vector3f.UNIT_Z);
        cam.setParallelProjection(true);
        cam.setFrustum(1, 20, -4, 4, 3, -3);
        setDisplayFps(false);
        setDisplayStatView(false);
    }

    @Override
    public void simpleUpdate(float tpf) {
        // 第四步：渲染线程推进上传；未发布页面不能进入 shader 的有效描述符。
        stream.update(Vector3f.ZERO, 0, 0, 0);
        viewPort.setRenderPath(PATHS[Math.min(frame / 8, 3)]);
    }

    @Override
    public void simpleRender(RenderManager manager) {
        if (!stream.getFailures().isEmpty()) {
            throw new AssertionError(stream.getFailures());
        }
        if (stream.getPublishedCount() != 2) {
            return;
        }
        if (stableUploadBytes < 0) {
            stableUploadBytes = stream.getUploadedBytes();
        } else if (stableUploadBytes != stream.getUploadedBytes()) {
            throw new AssertionError("Stationary pages uploaded again");
        }
        if (frame % 8 == 7) {
            ByteBuffer pixels = BufferUtils.createByteBuffer(240 * 180 * 4);
            renderer.readFrameBufferWithFormat(viewPort.getOutputFrameBuffer(), pixels, Image.Format.RGBA8);
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
                if (Math.abs(color[0] + color[1] - 102) > 4
                        || Math.abs(color[0] - previous[0]) > 30 || Math.abs(color[1] - previous[1]) > 30) {
                    throw new AssertionError("Discontinuous or unnormalized overlap: " + Arrays.toString(color));
                }
                previous = color;
            }
            int[] outside = sample(pixels, 3.5f);
            if (outside[0] + outside[1] > 2) {
                throw new AssertionError("GI escaped both coverage bounds");
            }
            System.out.println("GI REGIONS " + viewPort.getRenderPath() + " PASS " + Arrays.toString(center));
            if (frame == 31) {
                // 第五步：恢复材质、释放 GPU/CPU 分块，再停止应用和后台线程。
                stream.close();
                io.shutdownNow();
                try {
                    java.nio.file.Files.deleteIfExists(directory.resolve("red.giprobe"));
                    java.nio.file.Files.deleteIfExists(directory.resolve("green.giprobe"));
                    java.nio.file.Files.deleteIfExists(directory);
                } catch (java.io.IOException error) {
                    throw new RuntimeException(error);
                }
                System.out.println("GI_PAGED_STREAMING_PASS bytes=" + stableUploadBytes);
                stop();
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
        if (stream != null) {
            stream.close();
        }
        if (io != null) {
            io.shutdownNow();
        }
        if (red != null && red.getData() != null) {
            red.getData().dispose();
        }
        if (green != null && green.getData() != null) {
            green.getData().dispose();
        }
        super.destroy();
    }

    @Override
    public void handleError(String message, Throwable error) {
        error.printStackTrace();
        System.exit(1);
    }
}
