package jme3test.light;

import com.jme3.app.SimpleApplication;
import com.jme3.environment.gi.GiProbeVolume;
import com.jme3.environment.gi.GiSceneBaker;
import com.jme3.environment.gi.ProbeLightmapBaker;
import com.jme3.environment.gi.ProbeLightmapControl;
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

/**
 * Offscreen OpenGL smoke for dual-format bake, UV2 lightmap rasterization,
 * runtime format switching and regional lightmap unloading. No input controls.
 * Run {@code gradlew :jme3-examples:probeLightmapSmoke}. Requires OpenGL 4.3.
 * <pre>
 * 第一步：用 GiSceneBaker 烘焙总漫反射，启用八面体和单次切比雪夫选项。
 * 第二步：ProbeLightmapBaker 利用静态网格 UV2 输出独立纹理。
 * 第三步：附加 ProbeLightmapControl，移动相机验证按距离加载与卸载。
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
        Quad mesh = new Quad(1, 1);
        mesh.setBuffer(Type.TexCoord2, 2, new float[]{0, 0, 1, 0, 1, 1, 0, 1});
        receiver = new Geometry("UV2 receiver", mesh);
        receiver.setMaterial(new Material(assetManager, "Common/MatDefs/Light/PBRLighting.j3md"));
        receiver.setLocalTranslation(-.5f, -.5f, 0);
        rootNode.attachChild(receiver);
        rootNode.updateGeometricState();
        var lightmap = ProbeLightmapBaker.bake(renderManager, assetManager, receiver, baked,
                volume.getWorldTranslation(), 32, 32);
        var bytes = lightmap.getImage().getData(0);
        int center = 8 * (16 * 32 + 16);
        if (FastMath.convertHalfToFloat(bytes.getShort(center)) < 1f
                || FastMath.convertHalfToFloat(bytes.getShort(center + 6)) < .99f) {
            throw new AssertionError("Expected occupied lightmap texel with positive irradiance");
        }
        try {
            var directory = Files.createTempDirectory("probe-lightmap");
            var file = directory.resolve("map.j3o");
            new BinaryExporter().save(lightmap, file.toFile());
            file.toFile().deleteOnExit();
            directory.toFile().deleteOnExit();
            assetManager.registerLocator(directory.toString(), com.jme3.asset.plugins.FileLocator.class);
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
