package jme3test.post;

import com.jme3.app.SimpleApplication;
import com.jme3.input.KeyInput;
import com.jme3.input.controls.KeyTrigger;
import com.jme3.input.controls.ActionListener;
import com.jme3.math.ColorRGBA;
import com.jme3.post.FilterPostProcessor;
import com.jme3.post.filters.ExposureFilter;
import com.jme3.post.filters.ToneMapFilter;
import com.jme3.renderer.Caps;
import com.jme3.system.AppSettings;
import com.jme3.texture.FrameBuffer;
import com.jme3.texture.Image;
import com.jme3.util.BufferUtils;
import java.nio.ByteBuffer;

/**
 * Manual HDR exposure example. Requires floating-point color targets; verification
 * additionally requires RGBA32F. Keys: Up/Down change EV, R restores 0 EV.
 * Run: gradlew :jme3-examples:runExamples -Pexample=jme3test.post.TestExposure
 * Verify: gradlew :jme3-examples:exposureSmoke
 * MSAA/desktop GL: gradlew :jme3-examples:exposureSmoke -PexposureSamples=4 -PexposureRenderer=LWJGL-OpenGL33
 * The smoke test reads linear HDR pixels before tone mapping, checking positive,
 * negative and neutral EV, alpha, highlight retention and filter bypass/restoration.
 * <pre>{@code
 * // 第一步：创建浮点 HDR 后处理目标，保留大于 1 的亮度。
 * // 第二步：添加 ExposureFilter，再添加 ToneMapFilter；不要重复 Gamma 编码。
 * // 第三步：用方向键调节 EV，R 恢复 0。修改发生在应用线程。
 * // 第四步：验证模式不安装 ToneMap，读取原始浮点像素，避免截图掩盖错误。
 * }</pre>
 */
public class TestExposure extends SimpleApplication {
    private final ExposureFilter exposure = new ExposureFilter();
    private final boolean smoke = Boolean.getBoolean("exposureSmoke");
    private FrameBuffer output;
    private int frame;
    private int phase;
    private final float[] ev = {0, 1, -1, 2, 0};
    private ByteBuffer pixels;

    public static void main(String[] args) {
        TestExposure app = new TestExposure();
        AppSettings settings = new AppSettings(true);
        settings.setResolution(640, 360);
        settings.setSamples(Integer.getInteger("exposureSamples", 0));
        if (System.getProperty("exposureRenderer") != null) {
            settings.setRenderer(System.getProperty("exposureRenderer"));
        }
        settings.setAudioRenderer(null);
        settings.setGammaCorrection(true);
        app.setSettings(settings);
        app.setShowSettings(false);
        app.start();
    }

    @Override
    public void simpleInitApp() {
        setDisplayStatView(false);
        setDisplayFps(false);
        boolean half = renderer.getCaps().contains(Caps.HalfFloatColorBufferRGBA);
        boolean full = renderer.getCaps().contains(Caps.FloatColorBufferRGBA);
        if ((!half && !full) || (smoke && !full)) {
            throw new IllegalStateException("Required floating-point render target is unavailable");
        }
        viewPort.setBackgroundColor(new ColorRGBA(0.25f, 0.5f, 2f, 0.4f));
        FilterPostProcessor fpp = new FilterPostProcessor(assetManager);
        fpp.setNumSamples(Math.max(1, settings.getSamples()));
        fpp.setFrameBufferFormat(half ? Image.Format.RGBA16F : Image.Format.RGBA32F);
        fpp.addFilter(exposure);
        if (smoke) {
            output = new FrameBuffer(640, 360, 1);
            output.addColorTarget(FrameBuffer.FrameBufferTarget.newTarget(Image.Format.RGBA32F));
            output.setDepthTarget(FrameBuffer.FrameBufferTarget.newTarget(Image.Format.Depth));
            output.setSrgb(false);
            viewPort.setOutputFrameBuffer(output);
            pixels = BufferUtils.createByteBuffer(640 * 360 * 16);
        } else {
            fpp.addFilter(new ToneMapFilter());
            guiFont = assetManager.loadFont("Interface/Fonts/Default.fnt");
            com.jme3.font.BitmapText help = new com.jme3.font.BitmapText(guiFont);
            help.setText("Exposure: Up/Down +/-0.25 EV; R = 0 EV");
            help.setLocalTranslation(10, 340, 0);
            guiNode.attachChild(help);
            inputManager.addMapping("Brighter", new KeyTrigger(KeyInput.KEY_UP));
            inputManager.addMapping("Darker", new KeyTrigger(KeyInput.KEY_DOWN));
            inputManager.addMapping("Reset", new KeyTrigger(KeyInput.KEY_R));
            inputManager.addListener((ActionListener) (name, pressed, tpf) -> {
                if (pressed) {
                    float next = name.equals("Reset") ? 0 : exposure.getExposureCompensation()
                            + (name.equals("Brighter") ? 0.25f : -0.25f);
                    exposure.setExposureCompensation(Math.max(-16, Math.min(16, next)));
                    help.setText("Exposure " + exposure.getExposureCompensation() + " EV | Up/Down; R = reset");
                }
            }, "Brighter", "Darker", "Reset");
        }
        viewPort.addProcessor(fpp);
    }

    @Override
    public void simpleUpdate(float tpf) {
        if (!smoke || ++frame % 8 != 0) {
            return;
        }
        pixels.clear();
        renderer.readFrameBufferWithFormat(output, pixels, Image.Format.RGBA32F);
        float gain = phase == 3 ? 1 : (float) Math.pow(2, ev[phase]);
        float[] expected = {0.25f * gain, 0.5f * gain, 2f * gain, 0.4f};
        for (int channel = 0; channel < 4; channel++) {
            float actual = pixels.getFloat(((180 * 640 + 320) * 4 + channel) * 4);
            if (!Float.isFinite(actual) || Math.abs(actual - expected[channel]) > 0.003f) {
                throw new AssertionError("Exposure phase " + phase + " channel " + channel
                        + ": expected " + expected[channel] + ", got " + actual);
            }
        }
        if (++phase == ev.length) {
            System.out.println("EXPOSURE_HDR_ALPHA_BYPASS_PASS");
            stop();
            return;
        }
        exposure.setExposureCompensation(ev[phase]);
        exposure.setEnabled(phase != 3);
    }

    @Override
    public void handleError(String message, Throwable error) {
        super.handleError(message, error);
        if (smoke) {
            System.exit(1);
        }
    }
}
