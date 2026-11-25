package jme3test.light;

import com.jme3.app.SimpleApplication;
import com.jme3.environment.SkyLightControl;
import com.jme3.environment.gi.*;
import com.jme3.light.LightProbe;
import com.jme3.material.Material;
import com.jme3.math.*;
import com.jme3.renderer.RenderPath;
import com.jme3.scene.*;
import com.jme3.scene.shape.Box;
import com.jme3.system.*;
import com.jme3.texture.*;
import com.jme3.texture.image.ColorSpace;
import com.jme3.util.*;
import java.nio.ByteBuffer;
import java.util.ArrayList;

/**
 * Entry main; OpenGL 4.3 offscreen, fixed linear exposure, no input controls.
 * Run gradlew :jme3-examples:skyVolumeCompositionSmoke for separated mode,
 * or :jme3-examples:skyTotalVolumeCompositionSmoke for total mode. Bakes black-walled outdoor,
 * enclosed and window rooms, isolating sky visibility from bounced irradiance.
 * Tests sky / sky+GI / sky+reflection / all, intensity 0/1/2, Phong/PBR and four
 * paths. Add -Dgi.test.totalSky=true to test the new default total-light mode,
 * including volume exit, GI disable and bake-bounce sky suppression.
 * Add -Dgi.test.halfResolution=true to exercise opt-in deferred GI reconstruction.
 * This isolates composition; black walls do not validate multibounce transport.
 * <pre>
 * 第一步：SkyFactory 创建独立天光，等待捕捉完成。
 * 第二步：默认 Settings.separateSky=false 保存完整漫反射，不保存天空可见度。
 * 本示例通常显式设 true 测试分离模式；-Dgi.test.totalSky=true 测试默认模式。
 * 切换模式必须重新烘焙；运行时根据数据格式自动选择，不改变已载入数据。
 * 第三步：在渲染线程绑定体积；无体积时天空全可见，旧 total 格式仍替代天空漫反射。
 * 第四步：修改天光强度；直接照明更新，旧反弹光保持并提示过期，手动重烘焙。
 * 第五步：ReflectionProbe 只提供局部镜面反射；这里故意保留旧 diffuseEnabled=true。
 * 第六步：卸载时关闭 binding，释放自己创建的体积资源；不释放共享天空贴图。
 * </pre>
 */
public final class TestSkyVolumeComposition extends SimpleApplication {
    private final RenderPath[] paths = {RenderPath.Forward, RenderPath.Deferred,
        RenderPath.TiledDeferredCPU, RenderPath.TiledDeferredGPU};
    private final GiProbeData[] volumes = new GiProbeData[3];
    private final int[][][][] values = new int[3][4][2][3];
    private final int[][] directional = new int[3][2];
    private final boolean totalSky = Boolean.getBoolean("gi.test.totalSky");
    private SkyLightControl sky;
    private Geometry receiver;
    private LightProbe reflection;
    private GiProbeData.Binding binding;
    private int frame;
    private int test;
    private boolean prepared;

    public static void main(String[] args) {
        TestSkyVolumeComposition app = new TestSkyVolumeComposition();
        AppSettings settings = new AppSettings(true);
        settings.setRenderer(AppSettings.LWJGL_OPENGL43);
        settings.setAudioRenderer(null);
        settings.setResolution(64, 64);
        settings.setGammaCorrection(false);
        settings.setGpuSceneEnabled(true);
        app.setSettings(settings);
        app.setShowSettings(false);
        app.start(JmeContext.Type.OffscreenSurface);
    }

    @Override
    public void simpleInitApp() {
        getSceneServices().setEnabled(false);
        viewPort.setHalfResolutionProbeGi(Boolean.getBoolean("gi.test.halfResolution"));
        ArrayList<ByteBuffer> faces = new ArrayList<>();
        for (int face = 0; face < 6; face++) {
            ByteBuffer pixels = BufferUtils.createByteBuffer(4 * 4 * 16);
            for (int i = 0; i < 16; i++) {
                pixels.putFloat(1).putFloat(1).putFloat(1).putFloat(1);
            }
            pixels.flip();
            faces.add(pixels);
        }
        TextureCubeMap cube = new TextureCubeMap(new Image(Image.Format.RGBA32F,
                4, 4, 0, faces, ColorSpace.Linear));
        Spatial background = SkyFactory.createSky(assetManager, cube, SkyFactory.EnvMapType.CubeMap);
        rootNode.attachChild(background);
        sky = background.getControl(SkyLightControl.class);
        sky.setResolution(16);
        receiver = new Geometry("receiver", new Box(0.3f, 0.3f, 0.02f));
        receiver.setMobility(Mobility.Dynamic);
        Material initial = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        initial.setColor("Color", ColorRGBA.Black);
        receiver.setMaterial(initial);
        rootNode.attachChild(receiver);
        cam.setLocation(new Vector3f(0, 0, 2));
        cam.lookAt(Vector3f.ZERO, Vector3f.UNIT_Y);
        setDisplayFps(false);
        setDisplayStatView(false);
    }

    private void wall(Node room, float x, float y, float z, float sx, float sy, float sz) {
        Geometry geometry = new Geometry("black wall", new Box(sx, sy, sz));
        Material material = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        material.setColor("Color", ColorRGBA.Black);
        geometry.setMaterial(material);
        geometry.setLocalTranslation(x, y, z);
        room.attachChild(geometry);
    }

    private void prepare() {
        receiver.setCullHint(Spatial.CullHint.Always);
        for (int scenario = 0; scenario < 3; scenario++) {
            Node room = new Node("capture room");
            rootNode.attachChild(room);
            if (scenario != 0) {
                wall(room, -3, 0, 0, .1f, 3, 3);
                wall(room, 3, 0, 0, .1f, 3, 3);
                wall(room, 0, -3, 0, 3, .1f, 3);
                wall(room, 0, 3, 0, 3, .1f, 3);
                wall(room, 0, 0, -3, 3, 3, .1f);
                if (scenario == 1) {
                    wall(room, 0, 0, 3, 3, 3, .1f);
                } else {
                    // A broad off-centre opening in the front wall.
                    wall(room, -1.5f, 0, 3, 1.5f, 3, .1f);
                }
            }
            GiProbeVolume volume = new GiProbeVolume();
            volume.configure(new Vector3f(2, 2, 2), 2, 2, 2);
            volume.setLocalTranslation(-1, -1, -1);
            rootNode.attachChild(volume);
            rootNode.updateGeometricState();
            GiSceneBaker.Settings settings = new GiSceneBaker.Settings();
            settings.separateSky = !totalSky;
            settings.captureSize = 16;
            settings.radianceSamples = 512;
            settings.visibilityResolution = 4;
            settings.visibilitySamples = 16;
            settings.classifyProbes = false;
            settings.captureShadows = false;
            volumes[scenario] = GiSceneBaker.bake(renderManager, rootNode, volume,
                    settings, view -> { }, () -> false, completed -> { });
            if (!"Current".equals(volume.getSkyBakeStatus())) {
                throw new AssertionError("Fresh bake status");
            }
            sky.getLight().setIntensity(2);
            if (!"Stale (rebake)".equals(volume.getSkyBakeStatus())) {
                throw new AssertionError("Sky edit must mark baked bounce stale");
            }
            sky.getLight().setIntensity(1);
            volume.removeFromParent();
            room.removeFromParent();
        }
        receiver.setCullHint(Spatial.CullHint.Inherit);
        reflection = new LightProbe();
        reflection.setPrefilteredMap(sky.getLight().getPrefilteredEnvMap());
        reflection.setNbMipMaps(sky.getLight().getNbMipMaps());
        reflection.setShCoeffs(sky.getLight().getShCoeffs());
        reflection.getArea().setRadius(10);
        reflection.setReady(true);
    }

    @Override
    public void simpleUpdate(float tpf) {
        if (!sky.getLight().isReady()) {
            return;
        }
        if (!prepared) {
            prepare();
            prepared = true;
        }
        if (frame % 3 != 0) {
            return;
        }
        if (test >= 288) {
            configureDirectional();
            return;
        }
        int path = test % 4;
        int intensity = test / 4 % 3;
        int model = test / 12 % 2;
        int combination = test / 24 % 4;
        int scenario = test / 96;
        if (binding != null) {
            binding.close();
        }
        rootNode.removeLight(reflection);
        if (combination >= 2) {
            rootNode.addLight(reflection);
        }
        Material material = new Material(assetManager, model == 0
                ? "Common/MatDefs/Light/Lighting.j3md" : "Common/MatDefs/Light/PBRLighting.j3md");
        if (model == 0) {
            material.setBoolean("UseMaterialColors", true);
            material.setColor("Diffuse", new ColorRGBA(.1f, .1f, .1f, 1));
            material.setColor("Specular", ColorRGBA.Black);
        } else {
            material.setColor("BaseColor", new ColorRGBA(.1f, .1f, .1f, 1));
            material.setFloat("Metallic", 0);
            material.setFloat("Roughness", .5f);
        }
        binding = combination % 2 == 1 ? volumes[scenario].bind(material, new Vector3f(-1, -1, -1), .02f) : null;
        receiver.setMaterial(material);
        sky.getLight().setIntensity(intensity);
        viewPort.setRenderPath(paths[path]);
    }

    private void configureDirectional() {
        if (totalSky) {
            configureTotalFallback();
            return;
        }
        int scenario = (test - 288) / 2;
        int side = (test - 288) % 2;
        if (binding != null) {
            binding.close();
        }
        rootNode.removeLight(reflection);
        Vector3f[] coefficients = new Vector3f[9];
        for (int i = 0; i < 9; i++) {
            coefficients[i] = new Vector3f();
        }
        coefficients[0].set(1, 1, 1);
        coefficients[3].set(side == 0 ? .9f : -.9f, 0, 0);
        sky.getLight().setShCoeffs(coefficients);
        sky.getLight().setIntensity(1);
        Material material = new Material(assetManager, "Common/MatDefs/Light/Lighting.j3md");
        material.setBoolean("UseMaterialColors", true);
        material.setColor("Diffuse", new ColorRGBA(.5f, .5f, .5f, 1));
        material.setColor("Specular", ColorRGBA.Black);
        receiver.setMaterial(material);
        binding = volumes[scenario].bind(material, new Vector3f(-1, -1, -1), .02f);
        viewPort.setRenderPath(RenderPath.Forward);
    }

    @Override
    public void simpleRender(com.jme3.renderer.RenderManager manager) {
        if (!prepared) {
            return;
        }
        if (frame++ % 3 != 2) {
            return;
        }
        ByteBuffer pixels = BufferUtils.createByteBuffer(64 * 64 * 4);
        renderer.readFrameBufferWithFormat(viewPort.getOutputFrameBuffer(), pixels, Image.Format.RGBA8);
        int red = pixels.get((32 * 64 + 32) * 4) & 255;
        if (test >= 288) {
            if (totalSky) {
                verifyTotalFallback(red);
                return;
            }
            directional[(test - 288) / 2][(test - 288) % 2] = red;
            if (++test == 294) {
                if (Math.abs(directional[0][0] - directional[0][1]) > 2
                        || directional[1][0] > 1 || directional[1][1] > 1
                        || directional[2][0] <= directional[2][1] + 1) {
                    throw new AssertionError("Directional sky visibility: "
                            + java.util.Arrays.deepToString(directional));
                }
                binding.close();
                for (GiProbeData data : volumes) {
                    data.dispose();
                }
                System.out.println("SKY_VOLUME_COMPOSITION_PASS cases=288 directional=6");
                stop();
            }
            return;
        }
        int path = test % 4;
        int intensity = test / 4 % 3;
        int model = test / 12 % 2;
        int combination = test / 24 % 4;
        int scenario = test / 96;
        int[] result = values[scenario][combination][model];
        if (path == 0) {
            result[intensity] = red;
        } else if (Math.abs(result[intensity] - red) > 2) {
            throw new AssertionError("Path mismatch test=" + test + " red=" + red);
        }
        if (++test == 288) {
            verify();
        }
    }

    private void configureTotalFallback() {
        int scenario = (test - 288) / 2;
        if (binding != null) {
            binding.close();
        }
        rootNode.removeLight(reflection);
        sky.getLight().setIntensity(1);
        Material material = new Material(assetManager, "Common/MatDefs/Light/Lighting.j3md");
        material.setBoolean("UseMaterialColors", true);
        material.setColor("Diffuse", new ColorRGBA(.1f, .1f, .1f, 1));
        material.setColor("Specular", ColorRGBA.Black);
        binding = volumes[1].bind(material,
                scenario == 1 ? new Vector3f(100, 100, 100) : new Vector3f(-1, -1, -1), .02f);
        material.setBoolean("GiBakeBounce", (test - 288) % 2 == 1);
        if (scenario == 2) {
            material.setBoolean("GiEnabled", false);
        }
        receiver.setMaterial(material);
        viewPort.setRenderPath(RenderPath.Forward);
    }

    private void verifyTotalFallback(int red) {
        int scenario = (test - 288) / 2;
        if ((scenario == 0 && red > 1) || (scenario != 0 && red < 20)) {
            throw new AssertionError("Total coverage/fallback case=" + (test - 288) + " red=" + red);
        }
        if (++test == 294) {
            binding.close();
            for (GiProbeData data : volumes) {
                if (data.hasSeparatedSky()) {
                    throw new AssertionError("Total mode must not allocate sky visibility");
                }
                data.dispose();
            }
            System.out.println("SKY_TOTAL_COMPOSITION_PASS cases=288 fallback=6");
            stop();
        }
    }

    private void verify() {
        for (int scenario = 0; scenario < 3; scenario++) {
            for (int combination = 0; combination < 4; combination++) {
                for (int model = 0; model < 2; model++) {
                    int[] v = values[scenario][combination][model];
                    if (Math.abs(v[2] - 2 * v[1] + v[0]) > 3) {
                        throw new AssertionError("Nonlinear/double sky composition");
                    }
                }
                int[] diffuse = values[scenario][combination][0];
                if ((!totalSky || combination % 2 == 0) && diffuse[0] > 1) {
                    throw new AssertionError("Local reflection leaked diffuse");
                }
                if (combination % 2 == 0 && diffuse[1] < 20) {
                    throw new AssertionError("Missing unoccluded sky fallback");
                }
                if (scenario == 1 && combination % 2 == 1 && diffuse[1] > 1) {
                    throw new AssertionError("Enclosed room sky leak: " + diffuse[1]);
                }
            }
        }
        if (totalSky) {
            for (int scenario = 0; scenario < 3; scenario++) {
                int[] diffuse = values[scenario][1][0];
                if (Math.abs(diffuse[0] - diffuse[2]) > 2) {
                    throw new AssertionError("Total GI must not add live sky diffuse");
                }
            }
        }
        int window = values[2][1][0][1];
        if (window <= 1 || window >= values[0][1][0][1] - 2) {
            throw new AssertionError("Window visibility not between closed/open: " + window);
        }
    }

    @Override
    public void handleError(String message, Throwable error) {
        error.printStackTrace();
        System.exit(1);
    }
}
