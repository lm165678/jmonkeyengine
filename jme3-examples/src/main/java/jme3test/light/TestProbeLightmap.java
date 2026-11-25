package jme3test.light;

import com.jme3.app.SimpleApplication;
import com.jme3.environment.gi.GiProbeSet;
import com.jme3.environment.gi.GiProbeStreamingData;
import com.jme3.environment.gi.GiProbeVolume;
import com.jme3.environment.gi.GiSceneBaker;
import com.jme3.environment.gi.ProbeLightmapAsset;
import com.jme3.environment.gi.ProbeLightmapBaker;
import com.jme3.environment.gi.ProbeLightmapControl;
import com.jme3.environment.gi.ProbeLightmapRegion;
import com.jme3.export.binary.BinaryExporter;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.math.Vector3f;
import com.jme3.post.WorldSettings;
import com.jme3.renderer.RenderPath;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mobility;
import com.jme3.scene.VertexBuffer;
import com.jme3.scene.VertexBuffer.Type;
import com.jme3.scene.shape.Quad;
import com.jme3.texture.FrameBuffer;
import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import com.jme3.texture.Texture2D;
import com.jme3.texture.image.ColorSpace;
import com.jme3.util.BufferUtils;
import com.jme3.system.AppSettings;
import com.jme3.system.JmeContext;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

/**
 * Offscreen OpenGL smoke for dual-format bake, overlapping-field lightmap and independent
 * sky visibility rasterization, UV chart gutters, runtime format switching and
 * regional unloading. Checks Forward and Tiled Deferred output with GPU Scene
 * enabled while switching LOD, residency and mobility. No input controls.
 * Run {@code gradlew :jme3-examples:probeLightmapSmoke}. Requires OpenGL 4.3.
 * WorldSettings prefers octahedral irradiance and one hardware bilinear moment
 * lookup for new scenes. Disable setSingleProbeVisibility to restore the old
 * probability-filtered path; source bakes and saved false choices remain valid.
 * <pre>
 * 第一步：用 GiSceneBaker 烘焙总漫反射，启用八面体和单次切比雪夫选项。
 * 第二步：用重叠 Volume 和带留边的 UV2 烘焙双层图集，检查两个纹理层的扩边。
 *        用 bakeField(..., false) 重新烘焙可关闭接缝平滑，原 UV2 可复用。
 * 第三步：保存带类型的资产，在 ProbeLightmapControl 中引用并验证流送。
 * 第四步：对 Static 接收体切换 LOD，UV2 不变时继续读取同一张 Lightmap。
 * 第五步：清空 Lightmap 验证静态物体不读取实时探针，再切到 Dynamic 对比。
 * 第六步：对新烘焙的 J3O 调用 world.setLightmapMipEnabled(true)，切换远处采样。
 *        设回 false 立即恢复基础层。旧 J3O 没有 mip 时使用基础层，无须修改资产。
 * </pre>
 */
public final class TestProbeLightmap extends SimpleApplication {
    private static final RenderPath[] LOD_TEST_PATHS = {RenderPath.Forward, RenderPath.TiledDeferredGPU};
    private static final int LOD_TEST_START = 7;
    private static final int LOD_TEST_STEPS = 5;
    private static final int LIVE_FIELD_STEPS = LOD_TEST_PATHS.length * LOD_TEST_STEPS;
    private static final int STREAMING_START = LOD_TEST_START + (LIVE_FIELD_STEPS + LOD_TEST_PATHS.length) * 2;
    private Geometry receiver;
    private GiProbeVolume volume;
    private GiProbeVolume skyVolume;
    private int frame;
    private int phase;
    private int probeWaitFrames;
    private WorldSettings world;
    private ProbeLightmapControl streaming;
    private ProbeLightmapControl mipFixture;
    private ProbeLightmapControl legacyFixture;
    private int mipStep = -1;
    private Texture2D mipResident;
    private FrameBuffer coverageTarget;
    private Texture2D coverageMap;
    private float zeroCoverageRed;
    private float partialRed;
    private final float[] lodTestRed = new float[LOD_TEST_STEPS];
    private final float[] mappedLodRed = new float[LOD_TEST_PATHS.length];

    public static void main(String[] args) {
        var app = new TestProbeLightmap();
        var settings = new AppSettings(true);
        settings.setRenderer(AppSettings.LWJGL_OPENGL43);
        settings.setGpuSceneEnabled(true);
        settings.setResolution(64, 64);
        settings.setAudioRenderer(null);
        app.setSettings(settings);
        app.setShowSettings(false);
        app.start(JmeContext.Type.OffscreenSurface);
    }

    @Override
    public void simpleInitApp() {
        setDisplayFps(false);
        setDisplayStatView(false);
        viewPort.setRenderPath(RenderPath.TiledDeferredGPU);
        world = new WorldSettings();
        world.setSingleProbeVisibility(true);
        world.setOctahedralProbeIrradiance(true);
        world.setGiStreaming(new WorldSettings.GiStreaming(64f, 256, 16));
        rootNode.addControl(world);
        volume = new GiProbeVolume();
        volume.configure(new Vector3f(4, 4, 4), 2, 2, 2);
        volume.setLocalTranslation(-2, -2, -2);
        rootNode.attachChild(volume);
        rootNode.updateGeometricState();
        var recipe = new GiSceneBaker.Settings();
        recipe.captureSize = 16;
        recipe.radianceSamples = 64;
        recipe.visibilityResolution = 4;
        recipe.visibilitySamples = 16;
        recipe.classifyProbes = false;
        recipe.captureShadows = false;
        recipe.background = ColorRGBA.White;
        var baked = GiSceneBaker.bake(renderManager, rootNode, volume, recipe, v -> { }, () -> false, n -> { });
        if (!baked.hasOctahedralIrradiance()) {
            throw new AssertionError("Missing directional atlas");
        }
        skyVolume = new GiProbeVolume();
        skyVolume.configure(new Vector3f(2, 2, 2), 3, 2, 2);
        skyVolume.setLocalTranslation(0, -1, -1);
        rootNode.attachChild(skyVolume);
        rootNode.updateGeometricState();
        recipe.separateSky = true;
        var skyBake = GiSceneBaker.bake(renderManager, rootNode, skyVolume, recipe, v -> { }, () -> false, n -> { });
        Quad mesh = new Quad(1, 1);
        mesh.setBuffer(Type.TexCoord2, 2, new float[]{0, .125f, 1, .125f, 1, .875f, 0, .875f});
        VertexBuffer reduced = new VertexBuffer(Type.Index);
        reduced.setupData(VertexBuffer.Usage.Static, 3, VertexBuffer.Format.UnsignedShort,
                BufferUtils.createShortBuffer((short) 0, (short) 2, (short) 3));
        mesh.setLodLevels(new VertexBuffer[]{mesh.getBuffer(Type.Index), reduced});
        receiver = new Geometry("UV2 receiver", mesh);
        receiver.setMaterial(new Material(assetManager, "Common/MatDefs/Light/PBRLighting.j3md"));
        receiver.getMaterial().setFloat("Metallic", 0f);
        receiver.setLocalTranslation(-.5f, -.5f, 0);
        rootNode.attachChild(receiver);
        rootNode.updateGeometricState();
        var entries = List.of(new GiProbeSet.Entry(baked, volume.getWorldTranslation(), 0),
                new GiProbeSet.Entry(skyBake, skyVolume.getWorldTranslation(), 0));
        com.jme3.texture.Texture2D lightmap;
        try (GiProbeSet field = ProbeLightmapBaker.openField(renderManager, entries, 16L << 20, true, true)) {
            lightmap = ProbeLightmapBaker.bakeField(renderManager, assetManager, receiver, field, 32, 32);
            Texture2D unblended = ProbeLightmapBaker.bakeField(renderManager, assetManager, receiver, field, 32, 32,
                    false);
            try {
                if (!lightmap.getImage().getData(0).equals(unblended.getImage().getData(0))) {
                    throw new AssertionError("The seam option must leave a single UV chart unchanged");
                }
            } finally {
                unblended.getImage().dispose();
            }
            System.out.println("PROBE_LIGHTMAP_SEAM_OPTION_PASS");
        }
        if (!lightmap.getImage().hasMipmaps() || lightmap.getMinFilter().usesMipMapLevels()) {
            throw new AssertionError("A padded chart should store safe mip levels with base-level playback by default");
        }
        var bytes = lightmap.getImage().getData(0);
        int left = 8 * (16 * 32 + 4);
        int right = 8 * (16 * 32 + 30);
        int skyBand = 32 * 32 * 8;
        float irradiance = FastMath.convertHalfToFloat(bytes.getShort(left));
        float coverage = FastMath.convertHalfToFloat(bytes.getShort(left + 6));
        float leftSky = FastMath.convertHalfToFloat(bytes.getShort(skyBand + left + 6));
        float rightSky = FastMath.convertHalfToFloat(bytes.getShort(skyBand + right + 6));
        if (irradiance < 1f || coverage < .99f || leftSky > .02f || rightSky < .9f) {
            throw new AssertionError("Missing irradiance or separate sky in a two-volume lightmap: "
                    + irradiance + ", " + coverage + ", " + leftSky + ", " + rightSky);
        }
        verifyGutter(bytes, 0);
        verifyGutter(bytes, skyBand);
        var singleBand = ProbeLightmapBaker.bake(renderManager, assetManager, receiver, baked,
                volume.getWorldTranslation(), 32, 32);
        verifyGutter(singleBand.getImage().getData(0), 0);
        singleBand.getImage().dispose();
        try {
            var directory = Files.createTempDirectory("probe-lightmap");
            directory.toFile().deleteOnExit();
            assetManager.registerLocator(directory.toString(), com.jme3.asset.plugins.FileLocator.class);
            var streamed = GiProbeStreamingData.build(skyBake, "sky-cell", key -> {
                var brick = directory.resolve(key);
                brick.toFile().deleteOnExit();
                return Files.newOutputStream(brick);
            });
            if (!skyVolume.useStreamingData(streamed, skyBake)) {
                throw new AssertionError("Could not publish the test fine-probe directory");
            }
            var local = ProbeLightmapRegion.load(assetManager, List.of(ProbeLightmapRegion.Source.capture(volume),
                    ProbeLightmapRegion.Source.capture(skyVolume)), receiver.getWorldBound(), 16L << 20, true);
            try (GiProbeSet field = local.open(renderManager, 16L << 20, true, true)) {
                var paged = ProbeLightmapBaker.bakeField(renderManager, assetManager, receiver, field, 32, 32);
                float pagedSky = FastMath.convertHalfToFloat(paged.getImage().getData(0).getShort(skyBand + right + 6));
                paged.getImage().dispose();
                if (Math.abs(pagedSky - rightSky) > .025f) {
                    throw new AssertionError("Fine-probe field did not retain the overlap's separate sky: " + pagedSky);
                }
            }
            var file = directory.resolve("map.j3o");
            new BinaryExporter().save(new ProbeLightmapAsset(lightmap, true), file.toFile());
            file.toFile().deleteOnExit();
            writeMipFixture(directory.resolve("mip-sampling.j3o"), true);
            writeMipFixture(directory.resolve("legacy-sampling.j3o"), false);
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
        streaming = new ProbeLightmapControl("map.j3o", receiver.getWorldTransform(), 20);
        mipFixture = new ProbeLightmapControl("mip-sampling.j3o", receiver.getWorldTransform(), 20);
        legacyFixture = new ProbeLightmapControl("legacy-sampling.j3o", receiver.getWorldTransform(), 20);
        coverageTarget = new FrameBuffer(64, 64, 1);
        coverageTarget.addColorTarget(FrameBuffer.FrameBufferTarget.newTarget(Image.Format.RGBA16F));
        coverageTarget.setDepthTarget(FrameBuffer.FrameBufferTarget.newTarget(Image.Format.Depth));
        viewPort.setOutputFrameBuffer(coverageTarget);
        cam.setLocation(new Vector3f(0, 0, 2));
        cam.lookAt(Vector3f.ZERO, Vector3f.UNIT_Y);
    }

    private static void writeMipFixture(java.nio.file.Path path, boolean withMip) throws IOException {
        // Distinct red values let native readback detect which level was sampled.
        int width = 256;
        int baseBytes = width * width * 8;
        int mipBytes = withMip ? baseBytes / 4 : 0;
        var pixels = BufferUtils.createByteBuffer(baseBytes + mipBytes);
        for (int offset = 0; offset < pixels.capacity(); offset += 8) {
            float red = offset < baseBytes ? 2f : 8f;
            pixels.putShort(FastMath.convertFloatToHalf(red));
            pixels.putShort((short) 0);
            pixels.putShort((short) 0);
            pixels.putShort(FastMath.convertFloatToHalf(1f));
        }
        pixels.flip();
        Image image = new Image(Image.Format.RGBA16F, width, width, pixels, ColorSpace.Linear);
        if (withMip) {
            image.setMipMapSizes(new int[]{baseBytes, mipBytes});
        }
        Texture2D texture = new Texture2D(image);
        try {
            new BinaryExporter().save(new ProbeLightmapAsset(texture, false), path.toFile());
            path.toFile().deleteOnExit();
        } finally {
            image.dispose();
        }
    }

    private static void verifyGutter(java.nio.ByteBuffer pixels, int bandOffset) {
        int x = 30;
        int edge = bandOffset + (4 * 32 + x) * Long.BYTES;
        int gutter = bandOffset + (2 * 32 + x) * Long.BYTES;
        int empty = bandOffset + (1 * 32 + x) * Long.BYTES;
        long edgeValue = pixels.getLong(edge);
        if (edgeValue == 0L || pixels.getLong(gutter) != edgeValue || pixels.getLong(empty) != 0L) {
            throw new AssertionError("Expected a two-texel gutter in band " + bandOffset);
        }
    }

    @Override
    public void simpleUpdate(float tpf) {
        if (frame == LOD_TEST_START - 1) {
            var streamer = getSceneServices().getGiStreamer();
            if (streamer == null || streamer.getPublishedCount() < 2) {
                if (++probeWaitFrames > 900) {
                    throw new AssertionError("Timed out waiting for both live probe sources: "
                            + (streamer == null ? "no streamer" : streamer.getPageAdmissionFailures()));
                }
                return;
            }
        }
        frame++;
        if (mipStep >= 0) {
            if (frame > 1200) {
                throw new AssertionError("Timed out waiting for Lightmap mip policy " + mipStep);
            }
            return;
        }
        if (frame <= 6) {
            if (frame == 1 || frame == 3 || frame == 5) {
                // A distinctive baked color makes a probe fallback easy to spot.
                float coverage = frame == 1 ? 0f : frame == 3 ? .5f : 1f;
                var pixel = BufferUtils.createByteBuffer(8);
                pixel.putLong(0L);
                pixel.putShort(0, FastMath.convertFloatToHalf(8f * coverage));
                pixel.putShort(6, FastMath.convertFloatToHalf(coverage));
                pixel.flip();
                var previous = coverageMap;
                coverageMap = new Texture2D(new Image(Image.Format.RGBA16F, 1, 1, pixel, ColorSpace.Linear));
                receiver.setResidentProbeLightmap(coverageMap);
                if (previous != null) {
                    previous.getImage().dispose();
                }
            }
            return;
        }
        if (frame < STREAMING_START) {
            int step = (frame - LOD_TEST_START) / 2;
            if ((frame - LOD_TEST_START) % 2 == 0) {
                configureLodTest(step);
            }
            return;
        }
        if (frame == STREAMING_START) {
            receiver.setMobility(Mobility.Static);
            receiver.setResidentProbeLightmap(null);
            coverageMap.getImage().dispose();
            receiver.addControl(streaming);
        }
        boolean resident = receiver.getResidentProbeLightmap() != null;
        if (phase == 0 && resident) {
            if (receiver.getResidentProbeLightmap().getImage().getHeight() != 64
                    || !receiver.getResidentProbeLightmap().getImage().hasMipmaps()) {
                throw new AssertionError("Could not load the tagged two-band lightmap");
            }
            if (receiver.getLodLevel() != 1 || !receiver.usesResidentProbeLightmap()) {
                throw new AssertionError("Streamed lightmap not usable at LOD 1");
            }
            phase++;
            cam.setLocation(new Vector3f(0, 0, 60));
            world.setOctahedralProbeIrradiance(false);
        } else if (phase == 1 && !resident) {
            phase++;
            cam.setLocation(new Vector3f(0, 0, 2));
        } else if (phase == 2 && resident) {
            phase++;
            receiver.setMobility(Mobility.Dynamic);
        } else if (phase == 3 && !resident) {
            System.out.println("PROBE_LIGHTMAP_BAKE_STREAM_PASS");
            receiver.removeControl(streaming);
            receiver.setMobility(Mobility.Static);
            receiver.addControl(mipFixture);
            viewPort.setRenderPath(RenderPath.Forward);
            mipStep = 0;
        } else if (frame > 900) {
            throw new AssertionError("Timed out waiting for lightmap residency");
        }
    }

    private void verifyMipPlayback(float red) {
        Texture2D texture = receiver.getResidentProbeLightmap();
        boolean expectedMipFilter = mipStep == 1 || mipStep == 3;
        if (texture == null || texture.getMinFilter().usesMipMapLevels() != expectedMipFilter) {
            return;
        }
        if (mipStep == 0) {
            mipResident = texture;
        }
        if (mipStep <= 3 && (texture != mipResident || !texture.getImage().hasMipmaps())) {
            throw new AssertionError("A runtime mip toggle replaced the resident texture or its mip data");
        }
        if (mipStep == 4 && (texture == mipResident || texture.getImage().hasMipmaps())) {
            throw new AssertionError("The legacy map gained mip levels when enabling playback");
        }
        float expectedRed = (expectedMipFilter ? 8f : 2f) / FastMath.PI;
        if (Math.abs(red - expectedRed) > .06f) {
            throw new AssertionError("Unexpected mip sample in step " + mipStep + ": " + red + " != " + expectedRed);
        }
        mipStep++;
        if (mipStep == 4) {
            receiver.removeControl(mipFixture);
            receiver.addControl(legacyFixture);
        } else if (mipStep == 5) {
            System.out.println("PROBE_LIGHTMAP_EXPLICIT_MIP_AND_LEGACY_PASS");
            stop();
        } else {
            if (mipStep == 2) {
                viewPort.setRenderPath(RenderPath.TiledDeferredGPU);
            }
            world.setLightmapMipEnabled(mipStep != 2);
        }
    }

    private void configureLodTest(int step) {
        if (step >= LIVE_FIELD_STEPS) {
            volume.removeFromParent();
            skyVolume.removeFromParent();
            viewPort.setRenderPath(LOD_TEST_PATHS[step - LIVE_FIELD_STEPS]);
            receiver.setLodLevel(1);
            receiver.setMobility(Mobility.Static);
            receiver.setResidentProbeLightmap(coverageMap);
            return;
        }
        int probe = step % LOD_TEST_STEPS;
        viewPort.setRenderPath(LOD_TEST_PATHS[step / LOD_TEST_STEPS]);
        receiver.setLodLevel(probe == 0 ? 0 : 1);
        receiver.setMobility(probe == 2 || probe == 4 ? Mobility.Dynamic : Mobility.Static);
        receiver.setResidentProbeLightmap(probe < 3 ? coverageMap : null);
    }

    private void verifyLodTest(int step, float red) {
        if (step >= LIVE_FIELD_STEPS) {
            int path = step - LIVE_FIELD_STEPS;
            if (Math.abs(red - mappedLodRed[path]) > .015f) {
                throw new AssertionError("Standalone lightmap changed in " + LOD_TEST_PATHS[path]
                        + ": " + mappedLodRed[path] + " -> " + red);
            }
            System.out.println("PROBE_LIGHTMAP_WITHOUT_VOLUME_PASS " + LOD_TEST_PATHS[path] + " " + red);
            return;
        }
        int probe = step % LOD_TEST_STEPS;
        lodTestRed[probe] = red;
        if (probe != LOD_TEST_STEPS - 1) {
            return;
        }
        int path = step / LOD_TEST_STEPS;
        mappedLodRed[path] = lodTestRed[1];
        float staticWithoutMap = lodTestRed[3];
        float dynamicWithoutMap = lodTestRed[4];
        if (Math.abs(lodTestRed[1] - lodTestRed[0]) > .015f
                || lodTestRed[1] < staticWithoutMap + .05f
                || dynamicWithoutMap < staticWithoutMap + .05f
                || Math.abs(lodTestRed[2] - dynamicWithoutMap) > .015f) {
            throw new AssertionError("Unexpected lightmap or probe fallback for "
                    + LOD_TEST_PATHS[path] + ": " + java.util.Arrays.toString(lodTestRed));
        }
        System.out.println("PROBE_LIGHTMAP_LOD_AND_MOBILITY_PASS " + LOD_TEST_PATHS[path]
                + " " + java.util.Arrays.toString(lodTestRed));
    }

    @Override
    public void simpleRender(com.jme3.renderer.RenderManager manager) {
        if ((frame & 1) != 0 || (frame >= STREAMING_START && mipStep < 0)
                || (frame == LOD_TEST_START - 1 && probeWaitFrames > 0)) {
            return;
        }
        var readback = BufferUtils.createByteBuffer(64 * 64 * 16);
        renderer.readFrameBufferWithFormat(coverageTarget, readback, Image.Format.RGBA32F);
        float red = readback.getFloat((32 * 64 + 24) * 16);
        BufferUtils.destroyDirectBuffer(readback);
        if (mipStep >= 0) {
            verifyMipPlayback(red);
        } else if (frame == 2) {
            zeroCoverageRed = red;
        } else if (frame == 4) {
            partialRed = red;
        } else if (frame == 6) {
            float midpoint = (zeroCoverageRed + red) * .5f;
            if (red - zeroCoverageRed < .05f || Math.abs(partialRed - midpoint) > .015f) {
                throw new AssertionError("Static lightmap is not continuous across coverage: "
                        + zeroCoverageRed + ", " + partialRed + ", " + red);
            }
            System.out.println("PROBE_LIGHTMAP_CONTINUOUS_COVERAGE_PASS");
        } else {
            verifyLodTest((frame - LOD_TEST_START) / 2, red);
        }
    }

    @Override
    public void handleError(String message, Throwable error) {
        System.err.println(message);
        error.printStackTrace();
        System.exit(1);
    }
}
