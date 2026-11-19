package jme3test.post;

import com.jme3.app.SimpleApplication;
import com.jme3.export.binary.BinaryExporter;
import com.jme3.math.ColorRGBA;
import com.jme3.post.FilterPostProcessor;
import com.jme3.post.filters.AutoExposureFilter;
import com.jme3.post.filters.ColorGradingFilter;
import com.jme3.renderer.RenderManager;
import com.jme3.system.*;
import com.jme3.texture.*;
import com.jme3.texture.image.ColorSpace;
import com.jme3.util.BufferUtils;
import java.nio.ByteBuffer;

/**
 * UE4-style SDR exposure/Film/CombineLUTs example, no external models required.
 * Requires GL3+/float color targets; test requests GL4.3. No CPU readback in the
 * production filters; --verify alone reads test output. Run with --verify for
 * UE4_POST_GPU_PASS; use -Duepost.samples=4 for MSAA coverage. Without --verify,
 * displays a changing neutral HDR background; standard fly-camera input applies.
 * Equivalent invocation after compilation: java -cp <runtime> jme3test.post.TestUE4PostProcessing --verify
 * Tests do not assert identical UE imagery: no PQ/pre-exposure,
 * sparse portable metering and approximate tint remain documented differences.
 */
public final class TestUE4PostProcessing extends SimpleApplication {
    private static boolean verify;
    private final AutoExposureFilter exposure = new AutoExposureFilter() {
        @Override
        protected void preFrame(float tpf) {
            super.preFrame(verify ? .1f : tpf);
        }
    };
    private ColorGradingFilter grading = new ColorGradingFilter();
    private FilterPostProcessor chain;
    private FrameBuffer output;
    private int frame;
    private float priorBright = 2f;

    public static void main(String[] args) {
        verify = args.length > 0 && args[0].equals("--verify");
        TestUE4PostProcessing app = new TestUE4PostProcessing();
        AppSettings settings = new AppSettings(true);
        settings.setRenderer(AppSettings.LWJGL_OPENGL43);
        settings.setResolution(verify ? 64 : 960, verify ? 64 : 540);
        settings.setGammaCorrection(!verify);
        settings.setAudioRenderer(null);
        settings.setFrameRate(60);
        app.setSettings(settings);
        app.setShowSettings(false);
        app.start(verify ? JmeContext.Type.OffscreenSurface : JmeContext.Type.Display);
    }

    @Override
    public void simpleInitApp() {
        getSceneServices().setEnabled(false);
        setDisplayFps(!verify);
        setDisplayStatView(false);
        // 第一步：使用线性 HDR 场景颜色。不要给 GI 捕获视口安装此链。
        chain = new FilterPostProcessor(assetManager);
        chain.setFrameBufferFormat(Image.Format.RGBA16F);
        chain.setNumSamples(Integer.getInteger("uepost.samples", 1));
        // 第二步：曝光 -> Bloom（按需）-> Film/CombineLUTs -> FXAA（按需）。
        exposure.setMode(AutoExposureFilter.Mode.UE4Histogram);
        exposure.setBrightnessRange(.0001f, 100f);
        exposure.setAdaptationSpeeds(3f, 1f);
        grading.setCurve(ColorGradingFilter.Curve.UE4);
        // 第三步：序列化只保留配置，LUT 与曝光历史在目标视口重新生成。
        grading = BinaryExporter.saveAndLoad(assetManager, grading);
        AutoExposureFilter restored = BinaryExporter.saveAndLoad(assetManager, new AutoExposureFilter());
        if (restored.getMode() != AutoExposureFilter.Mode.Legacy) {
            throw new AssertionError("Legacy serialization changed");
        }
        AutoExposureFilter modern = new AutoExposureFilter();
        modern.setMode(AutoExposureFilter.Mode.UE4Histogram);
        if (BinaryExporter.saveAndLoad(assetManager, modern).getMode() != AutoExposureFilter.Mode.UE4Histogram) {
            throw new AssertionError("Modern serialization lost mode");
        }
        chain.addFilter(exposure);
        chain.addFilter(grading);
        viewPort.addProcessor(chain);
        if (verify) {
            output = new FrameBuffer(64,64,1);
            output.addColorTexture(new Texture2D(64,64,Image.Format.RGBA32F));
            viewPort.setOutputFrameBuffer(output);
        }
        // 第四步：关闭/恢复沿用 Filter API；相机切换调用 exposure.resetHistory()。
    }

    private Texture2D redLut() {
        ByteBuffer data = BufferUtils.createByteBuffer(16*16*16*4);
        for (int i = 0; i < 16*16*16; i++) {
            data.put((byte)255).put((byte)0).put((byte)0).put((byte)255);
        }
        data.flip();
        return new Texture2D(new Image(Image.Format.RGBA8,256,16,data,ColorSpace.Linear));
    }

    @Override
    public void simpleUpdate(float tpf) {
        if (!verify) {
            float v = (float) Math.pow(2, Math.sin(timer.getTimeInSeconds()*.3)*6)*.18f;
            viewPort.setBackgroundColor(new ColorRGBA(v,v,v,1));
            return;
        }
        if (frame % 8 != 0) {
            return;
        }
        int stage = frame/8;
        exposure.setEnabled(stage <= 2);
        grading.setEnabled(stage >= 3 && stage != 11);
        if (stage == 0 || stage == 2) {
            exposure.resetHistory();
        }
        float v = stage == 1 ? 1.8f : stage == 2 ? .018f
                : stage == 4 || stage == 11 || stage == 12 ? 4f : stage == 5 ? 64f
                : stage == 6 ? 0f : .18f;
        if (stage == 7) {
            grading.setColorLut(redLut(),1f);
        } else if (stage == 8) {
            grading.setColorLut(null,0f);
        } else if (stage == 9) {
            viewPort.removeProcessor(chain);
            viewPort.addProcessor(chain);
        } else if (stage == 10) {
            ColorGradingFilter reloaded = BinaryExporter.saveAndLoad(assetManager,grading);
            chain.removeFilter(grading);
            grading = reloaded;
            chain.addFilter(grading);
        } else if (stage == 12) {
            grading.setCurve(ColorGradingFilter.Curve.Neutral);
        } else if (stage == 13) {
            grading.setCurve(ColorGradingFilter.Curve.UE4);
        }
        viewPort.setBackgroundColor(stage == 14 ? new ColorRGBA(16,0,0,.25f)
                : stage == 15 ? new ColorRGBA(0,16,0,.25f)
                : stage == 16 ? new ColorRGBA(0,0,16,.25f)
                : new ColorRGBA(v,v,v,.25f));
    }

    @Override
    public void simpleRender(RenderManager rm) {
        if (!verify) {
            return;
        }
        ByteBuffer pixels = BufferUtils.createByteBuffer(64*64*16);
        renderer.readFrameBufferWithFormat(output,pixels,Image.Format.RGBA32F);
        int at = (32*64+32)*16;
        float r = pixels.getFloat(at);
        float g = pixels.getFloat(at+4);
        float b = pixels.getFloat(at+8);
        float a = pixels.getFloat(at+12);
        int stage = frame/8;
        if (!Float.isFinite(r+g+b+a) || Math.abs(a-.25f)>.01f) {
            throw new AssertionError("Nonfinite or changed alpha");
        }
        if (stage == 1) {
            if (r > priorBright+.01f || r < .15f) {
                throw new AssertionError("Bright-scene adaptation direction/overshoot");
            }
            priorBright = r;
        }
        if (frame%8==7) {
            System.out.println("UE4_STAGE " + stage + " rgb=" + r + "," + g + "," + b);
            if ((stage==0 || stage==2 || stage==3 || stage==8 || stage==9 || stage==10 || stage==13)
                    && Math.abs(r-.18f)>.035f) {
                throw new AssertionError("Grey or restored LUT mismatch");
            }
            if (stage==6 && r>.005f || stage==7 && (r<.98f || g>.005f || b>.005f)
                    || stage==11 && Math.abs(r-4f)>.02f
                    || (stage==4 || stage==5 || stage==12) && (r<.7f || r>1.001f)) {
                throw new AssertionError("Film/LUT/bypass mismatch");
            }
            if (stage >= 14 && (Math.min(r,Math.min(g,b)) < 0f || Math.max(r,Math.max(g,b)) > 1.001f)) {
                throw new AssertionError("Saturated Film output outside SDR");
            }
            if (stage==16) {
                System.out.println("UE4_POST_GPU_PASS");
                stop();
            }
        }
        frame++;
    }
}
