package com.jme3.post.filters;

import com.jme3.export.*;
import com.jme3.material.Material;
import com.jme3.math.Vector2f;
import com.jme3.math.Vector4f;
import com.jme3.post.FilterGraphContext;
import com.jme3.renderer.Renderer;
import com.jme3.renderer.framegraph.*;
import java.io.IOException;
import java.util.Map;

/**
 * GPU-only 64-bin log-luminance histogram exposure, with percentile trimming and
 * frame-rate-independent adaptation in stops. Meters a fixed 32x32 stratified
 * image (not every source pixel); tiny highlights can be undersampled. No CPU
 * readback or compute requirement. Default automatic mode, compensation 0 EV,
 * multiplier limits [-8,+8] EV, 10..90 percentiles, adaptation 3/1 per second.
 * These are exposure multiplier stops, not a physically calibrated EV100 camera.
 * Install before Bloom/tone mapping, never in GI capture. Requires float targets;
 * unsupported renderers bypass. Each filter belongs to one viewport. Mutation,
 * resets and cleanup are render-thread operations. Resize/disable/re-enable resets
 * history; call resetHistory after camera cuts or changing scene/lighting context.
 * <pre>{@code
 * // 第一步：创建 HDR FPP，先添加 AO 等场景效果。
 * FilterPostProcessor fpp = new FilterPostProcessor(assetManager);
 * fpp.setFrameBufferFormat(Image.Format.RGBA16F);
 * AutoExposureFilter exposure = new AutoExposureFilter();
 * // 第二步：限制自动曝光范围，避免黑场被无限提亮。
 * exposure.setExposureRange(-6f, 6f);
 * exposure.setPercentiles(.1f, .9f);
 * exposure.setAdaptationSpeeds(3f, 1f);
 * fpp.addFilter(exposure);
 * // 第三步：后续添加 CinematicBloomFilter 和 ColorGradingFilter，再挂到视口。
 * viewPort.addProcessor(fpp);
 * // 第四步：在应用线程切为手动；曝光补偿仍以 stop 为单位。
 * exposure.setAutomatic(false);
 * exposure.setExposureCompensation(0f);
 * // 第五步：恢复自动或相机瞬移时重置；资源由 FPP 清理。
 * exposure.setAutomatic(true);
 * exposure.resetHistory();
 * }</pre>
 */
public class AutoExposureFilter extends HdrGraphFilter {
    @Override
    public int getPipelineOrder() {
        return 30;
    }

    /** Legacy preserves serialized multiplier-stop behavior; SceneHistogram uses scene luminance. */
    public enum Mode { Legacy, SceneHistogram }
    private Mode mode = Mode.Legacy;
    private float minBrightness = .03f;
    private float maxBrightness = 2f;
    private float histogramMin = -8f;
    private float histogramMax = 4f;
    private float transitionDistance = 1.5f;
    private boolean automatic = true;
    private float compensation;
    private float minEV = -8f;
    private float maxEV = 8f;
    private float low = .1f;
    private float high = .9f;
    private float speedUp = 3f;
    private float speedDown = 1f;
    private float deltaTime;
    private boolean reset = true;
    private int current;
    private final RenderTarget[] history = new RenderTarget[2];
    private RenderTargetDefinition historyDefinition;
    private Material meter;
    private Material histogram;
    private Material adapt;
    private Material apply;

    public AutoExposureFilter() {
        super("AutoExposure");
    }

    @Override
    protected void initialize() {
        releaseHistory();
        meter = shader("Meter");
        histogram = shader("Histogram");
        adapt = shader("AdaptExposure");
        apply = shader("ApplyExposure");
        if (format != null) {
            historyDefinition = new RenderTargetDefinition(1f, 1, null, format);
            history[0] = historyDefinition.create(manager.getRenderer(), 1, 1);
            history[1] = historyDefinition.create(manager.getRenderer(), 1, 1);
        }
        resetHistory();
    }

    /**
     * Selects metering/adaptation semantics and resets history. Legacy is the default
     * for old serialized assets. SceneHistogram uses scene luminance:
     * 18% grey, brightness limits, and linear/exponential adaptation in stops.
     * Speed Up means increasing scene brightness (decreasing exposure) in this mode.
     * The portable meter uses a 32x32 luminance sample grid. No pre-exposure or physical camera EV100 is implied.
     * <pre>{@code
     * // 第一步：切换新计量模式；放在 Bloom 和 CombineLUTs 之前。
     * exposure.setMode(AutoExposureFilter.Mode.SceneHistogram);
     * exposure.setBrightnessRange(.03f, 2f);
     * exposure.setHistogramRange(-8f, 4f);
     * // 第二步：Speed Up 为进入亮处的适应速度，单位为 stop/秒。
     * exposure.setAdaptationSpeeds(3f, 1f);
     * exposure.setTransitionDistance(1.5f);
     * // 第三步：切回 Legacy 恢复旧 setExposureRange 的乘数范围语义。
     * exposure.setMode(AutoExposureFilter.Mode.Legacy);
     * }</pre>
     */
    public void setMode(Mode value) {
        mode = java.util.Objects.requireNonNull(value);
        resetHistory();
    }

    public Mode getMode() {
        return mode;
    }

    /** Scene luminance limits, not multiplier EV. Equal endpoints lock metering. */
    public void setBrightnessRange(float min, float max) {
        bounded(min, .00001f, 65504f, "Minimum brightness");
        bounded(max, min, 65504f, "Maximum brightness");
        minBrightness = min;
        maxBrightness = max;
    }

    /** Log2 scene luminance bounds for the histogram, in [-16,24]. */
    public void setHistogramRange(float min, float max) {
        bounded(min, -16f, 23f, "Histogram minimum");
        bounded(max, min + .01f, 24f, "Histogram maximum");
        histogramMin = min;
        histogramMax = max;
    }

    /** Distance in stops at which linear adaptation changes to exponential. */
    public void setTransitionDistance(float value) {
        transitionDistance = bounded(value, .1f, 10f, "Transition distance");
    }

    /** @param enabled true for histogram metering; false for compensation only */
    public void setAutomatic(boolean enabled) {
        if (automatic != enabled) {
            automatic = enabled;
            resetHistory();
        }
    }

    /** @return true for automatic histogram metering */
    public boolean isAutomatic() {
        return automatic;
    }

    /** @param ev compensation stops [-16,16], applied after automatic metering */
    public void setExposureCompensation(float ev) {
        compensation = bounded(ev, -16f, 16f, "Compensation");
    }

    /** @return compensation stops, applied in manual and automatic modes */
    public float getExposureCompensation() {
        return compensation;
    }

    /** @param min minimum multiplier stops @param max maximum multiplier stops */
    public void setExposureRange(float min, float max) {
        bounded(min, -16f, 16f, "Minimum EV");
        bounded(max, min, 16f, "Maximum EV");
        minEV = min;
        maxEV = max;
    }

    /** @param low lower retained percentile [0,1) @param high upper percentile (low,1] */
    public void setPercentiles(float low, float high) {
        bounded(low, 0f, 1f, "Low percentile");
        bounded(high, 0f, 1f, "High percentile");
        if (high <= low) {
            throw new IllegalArgumentException("Percentiles must be increasing");
        }
        this.low = low;
        this.high = high;
    }

    /**
     * Legacy: up/down are exponential rates for rising/falling multipliers.
     * SceneHistogram: up/down are stops per second for rising/falling scene brightness.
     * @param up positive speed in the selected mode
     * @param down positive speed in the selected mode
     */
    public void setAdaptationSpeeds(float up, float down) {
        speedUp = bounded(up, .01f, 100f, "Speed up");
        speedDown = bounded(down, .01f, 100f, "Speed down");
    }

    /** Discards temporal adaptation on the next rendered frame, e.g. after a cut. */
    public void resetHistory() {
        reset = true;
    }

    @Override
    public void setEnabled(boolean enabled) {
        if (enabled != isEnabled()) {
            resetHistory();
        }
        super.setEnabled(enabled);
    }

    @Override
    protected void preFrame(float tpf) {
        deltaTime = Float.isFinite(tpf) ? Math.max(0f, Math.min(tpf, 1f)) : 0f;
    }

    @Override
    protected ResourceHandle<RenderTarget> build(FilterGraphContext context, ResourceHandle<RenderTarget> input) {
        if (format == null) {
            return input;
        }
        var graph = context.getGraph();
        int previous = current;
        int next = 1 - current;
        var oldValue = graph.importResource(context.name("PreviousExposure"), RenderTarget.class, () -> history[previous]);
        var nextValue = graph.importResource(context.name("Exposure"), RenderTarget.class, () -> history[next]);
        if (automatic) {
            var luminance = context.createColor("LogLuminance", 32, 32, format);
            var bins = context.createColor("Histogram", 64, 1, format);
            Vector2f range = mode == Mode.Legacy ? new Vector2f(-12f, 16f)
                    : new Vector2f(histogramMin, histogramMax);
            meter.setVector2("LogRange", range);
            histogram.setVector2("LogRange", range);
            histogram.setBoolean("SceneHistogram", mode == Mode.SceneHistogram);
            adapt.setBoolean("SceneHistogram", mode == Mode.SceneHistogram);
            adapt.setVector2("Brightness", new Vector2f(minBrightness, maxBrightness));
            adapt.setFloat("TransitionDistance", transitionDistance);
            context.draw("Meter", meter, input, luminance);
            context.draw("Histogram", histogram, luminance, bins);
            adapt.setVector4("Settings", new Vector4f(minEV, maxEV, low, high));
            adapt.setVector2("Speeds", new Vector2f(speedUp, speedDown));
            adapt.setFloat("DeltaTime", deltaTime);
            adapt.setBoolean("Reset", reset);
            context.draw("Adapt", adapt, bins, nextValue, Map.of("History", oldValue));
        }
        apply.setBoolean("Automatic", automatic);
        apply.setFloat("Compensation", compensation);
        var result = context.createColor("Exposed", 1f, format);
        if (automatic) {
            context.draw("Apply", apply, input, result, Map.of("Exposure", nextValue));
            graph.addPass(context.name("CommitHistory"), pass -> {
                current = next;
                reset = false;
            }).read(nextValue).read(result).sideEffect();
        } else {
            // A valid sampler is still required even though the uniform branch skips it.
            apply.setTexture("Exposure", history[0].getColorTexture(0));
            context.draw("Manual", apply, input, result);
        }
        return result;
    }

    private void releaseHistory() {
        if (historyDefinition != null) {
            for (int i = 0; i < history.length; i++) {
                if (history[i] != null) {
                    historyDefinition.destroy(history[i]);
                    history[i] = null;
                }
            }
        }
    }

    @Override
    protected void cleanUpFilter(Renderer renderer) {
        releaseHistory();
    }

    @Override
    public void write(JmeExporter exporter) throws IOException {
        super.write(exporter);
        var out = exporter.getCapsule(this);
        out.write(mode, "mode", Mode.Legacy);
        out.write(minBrightness, "minBrightness", .03f);
        out.write(maxBrightness, "maxBrightness", 2f);
        out.write(histogramMin, "histogramMin", -8f);
        out.write(histogramMax, "histogramMax", 4f);
        out.write(transitionDistance, "transitionDistance", 1.5f);
        out.write(automatic, "automatic", true);
        out.write(compensation, "compensation", 0f);
        out.write(minEV, "minEV", -8f);
        out.write(maxEV, "maxEV", 8f);
        out.write(low, "low", .1f);
        out.write(high, "high", .9f);
        out.write(speedUp, "speedUp", 3f);
        out.write(speedDown, "speedDown", 1f);
    }

    @Override
    public void read(JmeImporter importer) throws IOException {
        super.read(importer);
        var in = importer.getCapsule(this);
        try {
            String savedMode = in.readString("mode", Mode.Legacy.name());
            // Map the original serialized name before enum lookup.
            setMode("UE\u0034Histogram".equals(savedMode) ? Mode.SceneHistogram : Mode.valueOf(savedMode));
            setBrightnessRange(in.readFloat("minBrightness", .03f), in.readFloat("maxBrightness", 2f));
            setHistogramRange(in.readFloat("histogramMin", -8f), in.readFloat("histogramMax", 4f));
            setTransitionDistance(in.readFloat("transitionDistance", 1.5f));
            setAutomatic(in.readBoolean("automatic", true));
            setExposureCompensation(in.readFloat("compensation", 0f));
            setExposureRange(in.readFloat("minEV", -8f), in.readFloat("maxEV", 8f));
            setPercentiles(in.readFloat("low", .1f), in.readFloat("high", .9f));
            setAdaptationSpeeds(in.readFloat("speedUp", 3f), in.readFloat("speedDown", 1f));
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid automatic exposure settings", invalid);
        }
        resetHistory();
    }
}
