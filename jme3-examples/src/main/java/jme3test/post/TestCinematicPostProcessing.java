package jme3test.post;

import com.jme3.app.SimpleApplication;
import com.jme3.input.KeyInput;
import com.jme3.input.controls.KeyTrigger;
import com.jme3.light.DirectionalLight;
import com.jme3.math.*;
import com.jme3.post.FilterPostProcessor;
import com.jme3.post.filters.*;
import com.jme3.renderer.RenderManager;
import com.jme3.scene.Spatial;
import com.jme3.system.*;
import com.jme3.texture.*;
import com.jme3.util.BufferUtils;
import java.nio.ByteBuffer;

/**
 * Model-based HDR preview and numeric GPU regression. OpenGL3+/float render targets
 * required, OpenGL4.3 requested for the test. Uses testdata Tank assets. No SDK or
 * original asset writes. Controls: 1 automatic exposure, 2 Bloom, 3 AO, 4 AO-only,
 * 5 tone curve, Space whole chain, R reset adaptation. Each switch is render-thread
 * owned. The first frame after a camera cut should call resetHistory().
 * <pre>{@code
 * // 第一步：运行本例，先观察 Tank 模型在统一 HDR 链下的显示。
 * // 第二步：按 1 对照自动/手动曝光；按 2、3 分别切换 Bloom/AO。
 * // 第三步：按 4 只看 AO，曝光、Bloom、调色自动旁路；再次按 4 恢复。
 * // 第四步：按 5 对照 Neutral/Filmic；空格旁路整个 FPP，保留设置。
 * // 第五步：自动验证：gradlew :jme3-examples:cinematicPostSmoke
 * // 或 JVM 参数 -Dcinematic.verify=true 启动此 main；观察 CINEMATIC_POST_PASS。
 * // 该模式检查 HDR/alpha、手动/自动曝光、Bloom 能量和调试链旁路。
 * }</pre>
 * This is an SDR HDR-processing baseline, not a claim of UE feature parity or
 * film-quality temporal AA, local exposure, HDR display, DOF or motion blur.
 */
public final class TestCinematicPostProcessing extends SimpleApplication {
    private static final boolean VERIFY = Boolean.getBoolean("cinematic.verify");
    private static final boolean DEMO_SMOKE = Boolean.getBoolean("cinematic.demoSmoke");
    private final AutoExposureFilter exposure = new AutoExposureFilter();
    private final CinematicBloomFilter bloom = new CinematicBloomFilter();
    private final HorizonAOFilter ao = new HorizonAOFilter();
    private final ColorGradingFilter grading = new ColorGradingFilter();
    private final ExposureFilter diagnostic = new ExposureFilter() {
        @Override
        public boolean isDiagnosticOutput() {
            return true;
        }
    };
    private FilterPostProcessor fpp;
    private FrameBuffer output;
    private int frame;
    private boolean showAO;
    private boolean filmic;
    private boolean attached = true;

    public static void main(String[] args) {
        TestCinematicPostProcessing app = new TestCinematicPostProcessing();
        AppSettings settings = new AppSettings(true);
        settings.setRenderer(AppSettings.LWJGL_OPENGL43);
        settings.setResolution(VERIFY ? 64 : 1280, VERIFY ? 64 : 720);
        settings.setAudioRenderer(null);
        settings.setGammaCorrection(!VERIFY);
        app.setSettings(settings);
        app.setShowSettings(false);
        app.start(VERIFY || DEMO_SMOKE ? JmeContext.Type.OffscreenSurface : JmeContext.Type.Display);
    }

    @Override
    public void simpleInitApp() {
        viewPort.setRenderPath(com.jme3.renderer.RenderPath.valueOf(
                System.getProperty("cinematic.path", "Forward")));
        if (VERIFY) {
            getSceneServices().setEnabled(false);
            verifyPersistence();
            output = new FrameBuffer(64, 64, 1);
            output.addColorTexture(new Texture2D(64, 64, Image.Format.RGBA32F));
            viewPort.setOutputFrameBuffer(output);
        } else {
            Spatial tank = assetManager.loadModel("Models/Tank/tank.j3o");
            for (int i = 0; i < 3; i++) {
                Spatial model = tank.clone();
                model.move((i - 1) * 5f, 0, 0);
                model.rotate(0, i * .7f, 0);
                rootNode.attachChild(model);
            }
            rootNode.addLight(new DirectionalLight(new Vector3f(-1, -2, -1).normalizeLocal(),
                    new ColorRGBA(5f, 4.5f, 4f, 1f)));
            cam.setLocation(new Vector3f(12, 10, 18));
            cam.lookAt(Vector3f.ZERO, Vector3f.UNIT_Y);
            flyCam.setMoveSpeed(15f);
            viewPort.setBackgroundColor(new ColorRGBA(.04f, .05f, .07f, 1f));
        }
        // 第一步：显式声明 HDR，避免高亮在进入后处理前已经被截断。
        fpp = new FilterPostProcessor(assetManager);
        if (renderer.getCaps().contains(com.jme3.renderer.Caps.HalfFloatColorBufferRGBA)) {
            fpp.setFrameBufferFormat(Image.Format.RGBA16F);
        } else if (renderer.getCaps().contains(com.jme3.renderer.Caps.FloatColorBufferRGBA)) {
            fpp.setFrameBufferFormat(Image.Format.RGBA32F);
        } else {
            throw new UnsupportedOperationException("Cinematic example requires an HDR color target");
        }
        fpp.setNumSamples(Integer.getInteger("cinematic.samples", 1));
        diagnostic.setEnabled(false);
        fpp.addFilter(diagnostic);
        // 第二步：场景 AO -> 曝光 -> Bloom -> 色调映射；显示编码由输出负责。
        fpp.addFilter(ao);
        fpp.addFilter(exposure);
        fpp.addFilter(bloom);
        fpp.addFilter(grading);
        viewPort.addProcessor(fpp);
        if (!VERIFY) {
            String[] names = {"Automatic", "Bloom", "AO", "AOOnly", "Curve", "Bypass", "Reset"};
            int[] keys = {KeyInput.KEY_1, KeyInput.KEY_2, KeyInput.KEY_3, KeyInput.KEY_4,
                KeyInput.KEY_5, KeyInput.KEY_SPACE, KeyInput.KEY_R};
            for (int i = 0; i < names.length; i++) {
                inputManager.addMapping(names[i], new KeyTrigger(keys[i]));
            }
            inputManager.addListener((com.jme3.input.controls.ActionListener) (name, down, tpf) -> {
                if (!down) {
                    return;
                }
                switch (name) {
                    case "Automatic":
                        exposure.setAutomatic(!exposure.isAutomatic());
                        break;
                    case "Bloom":
                        bloom.setEnabled(!bloom.isEnabled());
                        break;
                    case "AO":
                        ao.setEnabled(!ao.isEnabled());
                        break;
                    case "AOOnly":
                        showAO = !showAO;
                        ao.setShowOnly(showAO);
                        break;
                    case "Curve":
                        filmic = !filmic;
                        grading.setCurve(filmic ? ColorGradingFilter.Curve.Filmic : ColorGradingFilter.Curve.Neutral);
                        break;
                    case "Bypass":
                        attached = !attached;
                        if (attached) {
                            viewPort.addProcessor(fpp);
                        } else {
                            viewPort.removeProcessor(fpp);
                        }
                        break;
                    case "Reset":
                        exposure.resetHistory();
                        break;
                    default:
                        break;
                }
            }, names);
        }
        setDisplayFps(!VERIFY);
        setDisplayStatView(false);
    }

    private void verifyPersistence() {
        exposure.setAutomatic(false);
        exposure.setExposureCompensation(-2f);
        AutoExposureFilter restored = com.jme3.export.binary.BinaryExporter.saveAndLoad(assetManager, exposure);
        if (restored.isAutomatic() || restored.getExposureCompensation() != -2f) {
            throw new AssertionError("Exposure settings did not round-trip");
        }
        com.jme3.export.binary.BinaryExporter.saveAndLoad(assetManager, bloom);
        com.jme3.export.binary.BinaryExporter.saveAndLoad(assetManager, ao);
        com.jme3.export.binary.BinaryExporter.saveAndLoad(assetManager, grading);
        try {
            exposure.setPercentiles(.9f, .1f);
            throw new AssertionError("Invalid percentiles accepted");
        } catch (IllegalArgumentException expected) {
            // Validated before rendering or serialization.
        }
        System.out.println("CINEMATIC_PERSISTENCE_PASS");
    }

    @Override
    public void simpleUpdate(float tpf) {
        if (!VERIFY || frame % 8 != 0) {
            return;
        }
        int stage = frame / 8;
        ao.setEnabled(stage == 8);
        ao.setShowOnly(stage == 8);
        bloom.setEnabled(stage == 4 || stage == 8);
        grading.setEnabled(stage == 5 || stage == 6 || stage == 8);
        diagnostic.setEnabled(stage == 6);
        exposure.setAutomatic(stage == 2 || stage == 3);
        exposure.resetHistory();
        exposure.setExposureCompensation(stage == 1 ? 2f : stage == 6 || stage == 8 ? 12f : 0f);
        bloom.setIntensity(.1f);
        float value = stage == 2 ? .018f : stage == 3 ? 1.8f : stage == 4 || stage == 5 ? 4f : .18f;
        viewPort.setBackgroundColor(new ColorRGBA(value, value, value, .25f));
    }

    @Override
    public void simpleRender(RenderManager rm) {
        if (!VERIFY) {
            if (DEMO_SMOKE && ++frame == 120) {
                System.out.println("CINEMATIC_MODEL_PASS");
                stop();
            }
            return;
        }
        if (frame % 8 == 7) {
            int stage = frame / 8;
            ByteBuffer pixels = BufferUtils.createByteBuffer(64 * 64 * 16);
            renderer.readFrameBufferWithFormat(output, pixels, Image.Format.RGBA32F);
            float color = pixels.getFloat((32 * 64 + 32) * 16);
            float alpha = pixels.getFloat((32 * 64 + 32) * 16 + 12);
            float expected = stage == 1 ? .72f : stage == 4 ? 4.3f : stage == 8 ? 1f : .18f;
            System.out.println("CINEMATIC_STAGE " + stage + " rgb=" + color + " alpha=" + alpha);
            if (!Float.isFinite(color) || Math.abs(alpha - .25f) > .01f
                    || (stage != 5 && Math.abs(color - expected) > .04f)
                    || (stage == 5 && (color < .7f || color > 1f))) {
                throw new AssertionError("Cinematic stage " + stage + " failed");
            }
            if (stage == 8) {
                System.out.println("CINEMATIC_POST_PASS");
                stop();
            }
        }
        frame++;
    }
}
