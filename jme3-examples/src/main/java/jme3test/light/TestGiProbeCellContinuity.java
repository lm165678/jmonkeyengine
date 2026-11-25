package jme3test.light;

import com.jme3.app.SimpleApplication;
import com.jme3.environment.gi.GiProbeData;
import com.jme3.environment.gi.GiProbeVolume;
import com.jme3.environment.gi.GiSceneBaker;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.math.Vector3f;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.RenderPath;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mobility;
import com.jme3.scene.shape.Box;
import com.jme3.system.AppSettings;
import com.jme3.system.JmeContext;
import com.jme3.texture.Image;
import com.jme3.texture.Texture2D;
import com.jme3.texture.image.ColorSpace;
import com.jme3.util.BufferUtils;
import java.nio.ByteBuffer;

/**
 * A planar receiver crosses two grid cells at their shared corner. Front-side
 * probes have different irradiance on either side. A hard half-space gate on an eight-corner stencil loses
 * support there. Wrapped weights must stay continuous without a wide stencil.
 * Requires OpenGL 4.3 and float targets; entry main, no input controls.
 * Run {@code gradlew :jme3-examples:giProbeCellContinuitySmoke}; completion prints
 * GI_CELL_CONTINUITY_PASS. Exercises Lighting/PBR on four rendering paths.
 * Set -Dgi.test.wide=true to verify the optional 27-probe reference mode.
 * <pre>
 * 第一步：普通应用加载烘焙 J3O，自动使用连续网格支持域，不需要新的管理器。
 * 第二步：原烘焙数据继续有效；本例仅为回归构造低层合成材质绑定。
 * 第三步：在渲染线程沿同一平面越过网格交界，不允许突然切换红黑贡献。
 * 第四步：卸载时关闭绑定；此修复不需要平滑开关，也不引入最低遮挡权重。
 * </pre>
 */
public final class TestGiProbeCellContinuity extends SimpleApplication {
    private static final int STEPS = 65;
    private static final RenderPath[] PATHS = {RenderPath.Forward, RenderPath.Deferred,
        RenderPath.TiledDeferredCPU, RenderPath.TiledDeferredGPU};
    private final Vector3f normal = new Vector3f(1, 1, 0).normalizeLocal();
    private final int[] values = new int[STEPS];
    private GiProbeData data;
    private GiProbeData.Binding binding;
    private Geometry receiver;
    private int frame;

    public static void main(String[] args) {
        TestGiProbeCellContinuity app = new TestGiProbeCellContinuity();
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
        GiProbeVolume volume = new GiProbeVolume();
        volume.configure(new Vector3f(4, 4, 4), 3, 3, 2);
        volume.setLocalTranslation(-2, -2, -2);
        rootNode.attachChild(volume);
        rootNode.updateGeometricState();
        GiSceneBaker.Settings settings = new GiSceneBaker.Settings();
        settings.captureSize = 8;
        settings.radianceSamples = 256;
        settings.visibilityResolution = 4;
        settings.visibilitySamples = 4;
        settings.compactStorage = false;
        settings.separateSky = false;
        settings.background = new ColorRGBA(0.4f, 0.4f, 0.4f, 1);
        data = GiSceneBaker.bake(renderManager, rootNode, volume, settings,
                view -> { }, () -> false, count -> { });
        receiver = new Geometry("Slanted planar receiver", new Box(3, 0.1f, 3));
        receiver.setMobility(Mobility.Dynamic);
        receiver.rotate(0, 0, -FastMath.QUARTER_PI);
        receiver.setLocalTranslation(normal.mult(-0.1f));
        rootNode.attachChild(receiver);
        setDisplayFps(false);
        setDisplayStatView(false);
    }

    @Override
    public void simpleUpdate(float tpf) {
        if (frame % 3 != 0) {
            return;
        }
        int phase = frame / 3;
        int step = phase % STEPS;
        int testCase = phase / STEPS;
        float x = -0.4f + 0.8f * step / (STEPS - 1);
        Vector3f target = new Vector3f(x, -x, 0);
        cam.setLocation(target.add(normal.mult(3)));
        cam.lookAt(target, Vector3f.UNIT_Z);
        if (step != 0) {
            return;
        }
        viewPort.setRenderPath(PATHS[testCase % 4]);
        if (binding != null) {
            binding.close();
        }
        boolean pbr = testCase % 8 >= 4;
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
        binding = data.bind(material, new Vector3f(-2, -2, -2), 0);
        com.jme3.math.Vector4f atlas = ((com.jme3.math.Vector4f) material.getParam("GiAtlas").getValue()).clone();
        if (testCase >= 8) {
            atlas.z += 0.125f;
        }
        if (Boolean.getBoolean("gi.test.wide")) {
            atlas.z += 0.015625f;
        }
        material.setVector4("GiAtlas", atlas);
        Image image = material.getTextureParam("GiIrradiance").getTextureValue().getImage();
        ByteBuffer coefficients = BufferUtils.createByteBuffer(image.getData(0).capacity());
        coefficients.put(image.getData(0).duplicate().clear()).clear();
        int columns = image.getWidth() / 9;
        for (int probe = 0; probe < 18; probe++) {
            float scale = (probe / 3) % 3 == 2 ? 1f : 0.1f;
            for (int c = 0; c < 9; c++) {
                int pixel = (probe / columns * image.getWidth() + probe % columns * 9 + c) * 16;
                for (int channel = 0; channel < 3; channel++) {
                    int offset = pixel + channel * 4;
                    coefficients.putFloat(offset, coefficients.getFloat(offset) * scale);
                }
            }
        }
        material.setTexture("GiIrradiance", new Texture2D(new Image(Image.Format.RGBA32F,
                image.getWidth(), image.getHeight(), coefficients, ColorSpace.Linear)));
    }

    @Override
    public void simpleRender(RenderManager manager) {
        if (frame % 3 == 2) {
            ByteBuffer pixels = BufferUtils.createByteBuffer(64 * 64 * 4);
            renderer.readFrameBufferWithFormat(viewPort.getOutputFrameBuffer(), pixels, Image.Format.RGBA8);
            int step = frame / 3 % STEPS;
            values[step] = pixels.get((32 * 64 + 32) * 4) & 255;
            if (step == STEPS - 1) {
                int maxJump = 0;
                for (int i = 1; i < STEPS; i++) {
                    maxJump = Math.max(maxJump, Math.abs(values[i] - values[i - 1]));
                }
                System.out.println("GI_CELL maxJump=" + maxJump + " values="
                        + java.util.Arrays.toString(values));
                if (maxJump > 3 || values[0] - values[STEPS - 1] < 10) {
                    throw new AssertionError("Cell boundary discontinuity or missing lighting variation");
                }
            }
            if (frame == 16 * STEPS * 3 - 1) {
                binding.close();
                data.dispose();
                System.out.println("GI_CELL_CONTINUITY_PASS");
                stop();
            }
        }
        frame++;
    }
}
