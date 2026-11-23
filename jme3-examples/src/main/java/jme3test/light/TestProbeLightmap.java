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
import com.jme3.scene.VertexBuffer.Type;
import com.jme3.scene.shape.Quad;
import com.jme3.system.AppSettings;
import com.jme3.system.JmeContext;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

/**
 * Offscreen OpenGL smoke for dual-format bake, overlapping-field lightmap and independent
 * sky visibility rasterization, runtime format switching and regional unloading. No input controls.
 * Run {@code gradlew :jme3-examples:probeLightmapSmoke}. Requires OpenGL 4.3.
 * <pre>
 * 第一步：用 GiSceneBaker 烘焙总漫反射，启用八面体和单次切比雪夫选项。
 * 第二步：用重叠的天空分离 Volume 和 UV2 烘焙两区域图集。
 * 第三步：保存带类型的资产，在 ProbeLightmapControl 中引用并验证流送。
 * </pre>
 */
public final class TestProbeLightmap extends SimpleApplication {
    private Geometry receiver;
    private int frame;
    private int phase;
    private WorldSettings world;

    public static void main(String[] args) {
        var app = new TestProbeLightmap();
        var settings = new AppSettings(true);
        settings.setRenderer(AppSettings.LWJGL_OPENGL43);
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
        GiProbeVolume volume = new GiProbeVolume();
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
        GiProbeVolume skyVolume = new GiProbeVolume();
        skyVolume.configure(new Vector3f(2, 2, 2), 3, 2, 2);
        skyVolume.setLocalTranslation(0, -1, -1);
        rootNode.attachChild(skyVolume);
        rootNode.updateGeometricState();
        recipe.separateSky = true;
        var skyBake = GiSceneBaker.bake(renderManager, rootNode, skyVolume, recipe, v -> { }, () -> false, n -> { });
        Quad mesh = new Quad(1, 1);
        mesh.setBuffer(Type.TexCoord2, 2, new float[]{0, 0, 1, 0, 1, 1, 0, 1});
        receiver = new Geometry("UV2 receiver", mesh);
        receiver.setMaterial(new Material(assetManager, "Common/MatDefs/Light/PBRLighting.j3md"));
        receiver.setLocalTranslation(-.5f, -.5f, 0);
        rootNode.attachChild(receiver);
        rootNode.updateGeometricState();
        var entries = List.of(new GiProbeSet.Entry(baked, volume.getWorldTranslation(), 0),
                new GiProbeSet.Entry(skyBake, skyVolume.getWorldTranslation(), 0));
        com.jme3.texture.Texture2D lightmap;
        try (GiProbeSet field = ProbeLightmapBaker.openField(renderManager, entries, 16L << 20, true, true)) {
            lightmap = ProbeLightmapBaker.bakeField(renderManager, assetManager, receiver, field, 32, 32);
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
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
        receiver.addControl(new ProbeLightmapControl("map.j3o", receiver.getWorldTransform(), 20));
        cam.setLocation(new Vector3f(0, 0, 2));
        cam.lookAt(Vector3f.ZERO, Vector3f.UNIT_Y);
    }

    @Override
    public void simpleUpdate(float tpf) {
        frame++;
        boolean resident = receiver.getResidentProbeLightmap() != null;
        if (phase == 0 && resident) {
            if (receiver.getResidentProbeLightmap().getImage().getHeight() != 64) {
                throw new AssertionError("Could not load the tagged two-band lightmap");
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
            stop();
        } else if (frame > 900) {
            throw new AssertionError("Timed out waiting for lightmap residency");
        }
    }
}
