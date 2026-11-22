package jme3test.light;

import com.jme3.app.SimpleApplication;
import com.jme3.asset.AssetInfo;
import com.jme3.asset.AssetKey;
import com.jme3.asset.AssetLocator;
import com.jme3.asset.AssetManager;
import com.jme3.environment.gi.GiProbeData;
import com.jme3.environment.gi.GiProbeStreamer;
import com.jme3.environment.gi.GiProbeStreamingData;
import com.jme3.environment.gi.GiProbeVolume;
import com.jme3.environment.gi.GiSceneBaker;
import com.jme3.export.binary.BinaryExporter;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.post.WorldSettings;
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
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bakes a regular grid, serializes a world and verifies localized fine loading
 * and coarse fallback through the normal SimpleApplication entry point.
 * Run {@code gradlew :jme3-examples:giBrickStreamingSmoke}. Requires OpenGL 4.3,
 * floating-point render targets and region uploads. No interactive controls.
 * Uses GiProbeStreamingData.build, GiProbeVolume.useStreamingData and
 * WorldSettings.setGiStreaming. This test uses an in-memory locator; an SDK
 * project ships the J3O and its GI/Streaming directory together.
 */
public final class TestGiBrickStreaming extends SimpleApplication {
    private static final Map<String, byte[]> assets = new ConcurrentHashMap<>();
    private static final Set<String> reads = ConcurrentHashMap.newKeySet();
    private static final String FIRST = "GI/test-0.giprobe";
    private static final String SECOND = "GI/test-1.giprobe";
    private static final RenderPath[] PATHS = {RenderPath.Forward, RenderPath.Deferred,
        RenderPath.TiledDeferredCPU, RenderPath.TiledDeferredGPU};
    private WorldSettings world;
    private Node scene;
    private int phase;
    private int frames;
    private int renderPath;
    private final ByteBuffer pixels = BufferUtils.createByteBuffer(64 * 64 * 4);
    private int coarsePixel;

    public static final class MemoryLocator implements AssetLocator {
        @Override
        public void setRootPath(String ignored) {
        }

        @Override
        @SuppressWarnings("rawtypes")
        public AssetInfo locate(AssetManager manager, AssetKey key) {
            byte[] bytes = assets.get(key.getName());
            if (bytes == null) {
                return null;
            }
            return new AssetInfo(manager, key) {
                @Override
                public ByteArrayInputStream openStream() {
                    reads.add(key.getName());
                    return new ByteArrayInputStream(bytes);
                }
            };
        }
    }

    public static void main(String[] args) {
        TestGiBrickStreaming app = new TestGiBrickStreaming();
        AppSettings config = new AppSettings(true);
        config.setRenderer(AppSettings.LWJGL_OPENGL43);
        config.setAudioRenderer(null);
        config.setResolution(64, 64);
        app.setSettings(config);
        app.setShowSettings(false);
        app.start(JmeContext.Type.OffscreenSurface);
    }

    @Override
    public void simpleInitApp() {
        setDisplayFps(false);
        setDisplayStatView(false);
        // 第一步：普通网格烘焙一次。测试用恒定入射光检查切换时不会叠亮。
        Node source = new Node("World");
        GiProbeVolume volume = new GiProbeVolume();
        volume.configure(new Vector3f(7, 3, 4), 8, 2, 2);
        source.attachChild(volume);
        source.updateGeometricState();
        GiSceneBaker.Settings capture = new GiSceneBaker.Settings();
        capture.captureSize = 8;
        capture.visibilityResolution = 4;
        capture.visibilitySamples = 4;
        capture.radianceSamples = 256;
        capture.captureShadows = false;
        capture.separateSky = false;
        capture.background = new ColorRGBA(0.3f, 0.3f, 0.3f, 1);
        GiProbeData fine = GiSceneBaker.bake(renderManager, source, volume, capture,
                view -> { }, () -> false, pass -> { });
        try {
            // 第二步：独立写入细块，粗层和 WorldSettings 保存进 J3O。
            GiProbeStreamingData directory = GiProbeStreamingData.build(fine, "GI/test", key ->
                    new ByteArrayOutputStream() {
                        @Override
                        public void close() {
                            assets.put(key, toByteArray());
                        }
                    });
            check(volume.useStreamingData(directory, fine), "Failed to install brick directory");
            WorldSettings settings = new WorldSettings();
            settings.setDisplayOutput(WorldSettings.DisplayOutput.Application);
            settings.setGiStreaming(new WorldSettings.GiStreaming(0.5f, 28, 8));
            source.addControl(settings);
            Geometry receiver = new Geometry("Receiver", new Box(3.5f, 0.05f, 2));
            receiver.setLocalTranslation(3.5f, 0.1f, 2);
            Material material = new Material(assetManager, "Common/MatDefs/Light/PBRLighting.j3md");
            material.setColor("BaseColor", ColorRGBA.White);
            material.setFloat("Metallic", 0);
            material.setFloat("Roughness", 1);
            receiver.setMaterial(material);
            source.attachChild(receiver);
            ByteArrayOutputStream serialized = new ByteArrayOutputStream();
            new BinaryExporter().save(source, serialized);
            assets.put("brick-world.j3o", serialized.toByteArray());
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        } finally {
            fine.dispose();
        }
        // 第三步：普通 Java 入口加载并挂载，自动应用预算。
        assetManager.registerLocator("brick-test", MemoryLocator.class);
        scene = (Node) assetManager.loadModel("brick-world.j3o");
        world = scene.getControl(WorldSettings.class);
        check(world.getGiStreaming().getMaxFineProbes() == 28, "Lost probe budget");
        check(world.getGiStreaming().getAtlasMiB() == 8, "Lost atlas size");
        rootNode.attachChild(scene);
        camera(2);
    }

    private void camera(float x) {
        cam.setLocation(new Vector3f(x, 2, 2));
        cam.lookAt(new Vector3f(x, 0, 2), Vector3f.UNIT_Z);
    }

    @Override
    public void simpleRender(RenderManager manager) {
        GiProbeStreamer stream = getSceneServices().getGiStreamer();
        if (++frames > 600) {
            throw new AssertionError("Streaming stalled in phase " + phase + ": "
                    + (stream == null ? "no streamer" : stream.getFailures() + "/" + stream.getPageAdmissionFailures()));
        }
        if (phase == 5) {
            if (stream == null) {
                // 第六步：细块丢失后重挂场景，粗层仍然填补该区域。
                assets.remove(SECOND);
                rootNode.attachChild(scene);
                advance();
            }
            return;
        }
        if (phase == 6) {
            if (stream != null && stream.getPublishedCount() == 1 && !stream.getFailures().isEmpty()) {
                assertIrradiance();
                System.out.println("GI_BRICK_STREAMING_PASS");
                stop();
            }
            return;
        }
        if (stream == null || (phase != 0 && stream.getPublishedCount() != (phase >= 3 ? 2 : 1))
                || stream.getPublishedCount() < 1) {
            return;
        }
        if (phase >= 1 && !reads.contains(FIRST)) {
            return;
        }
        if (phase >= 4 && !reads.contains(SECOND)) {
            return;
        }
        if (phase == 0) {
            if (!reads.contains(FIRST)) {
                return;
            }
            check(!reads.contains(SECOND), "A distant brick was read");
            // 第四步：快速降到零预算，确认只使用粗层，再恢复局部细层。
            world.setGiStreaming(new WorldSettings.GiStreaming(0.5f, 0, 8));
        } else if (phase == 1) {
            check(stream.getRequestedFineProbeCount() == 0, "Zero budget kept fine requests");
            coarsePixel = centerPixel();
            check(coarsePixel > 25, "Coarse fallback did not light the receiver");
            world.setGiStreaming(new WorldSettings.GiStreaming(0.5f, 28, 8));
        } else if (phase == 2) {
            advance();
            return;
        } else if (phase == 3) {
            check(stream.getRequestedFineProbeCount() == 28, "Unexpected first-brick probe count");
            check(!reads.contains(SECOND), "Loaded distant brick while in first region");
            assertIrradiance();
            camera(6);
        } else if (phase == 4) {
            check(stream.getRequestedFineProbeCount() == 24, "Unexpected second-brick probe count");
            assertIrradiance();
            if (renderPath + 1 < PATHS.length) {
                viewPort.setRenderPath(PATHS[++renderPath]);
                return;
            }
            // 第五步：移除场景，自动撤销图集绑定和释放流送器。
            scene.removeFromParent();
        }
        advance();
    }

    private void advance() {
        phase++;
        frames = 0;
    }

    private int centerPixel() {
        pixels.clear();
        renderer.readFrameBufferWithFormat(viewPort.getOutputFrameBuffer(), pixels, Image.Format.RGBA8);
        return pixels.get((32 * 64 + 32) * 4) & 255;
    }

    private void assertIrradiance() {
        int value = centerPixel();
        check(Math.abs(value - coarsePixel) <= 4, "Unexpected transition brightness: " + value + "/" + coarsePixel);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    @Override
    public void handleError(String message, Throwable error) {
        error.printStackTrace();
        System.exit(1);
    }
}
