package com.jme3.post.filters;

import com.jme3.export.*;
import com.jme3.material.Material;
import com.jme3.math.Vector2f;
import com.jme3.post.FilterGraphContext;
import com.jme3.renderer.Renderer;
import com.jme3.renderer.Caps;
import com.jme3.renderer.framegraph.*;
import com.jme3.renderer.temporal.TemporalScene;
import com.jme3.texture.Image;
import java.io.IOException;
import java.util.Map;

/**
 * Native-resolution temporal AA with reprojection and YCoCg history
 * clipping. Not a port of Epic shader code or a temporal upscaler. Install FIRST
 * in a single-sample HDR FPP, before exposure, bloom and tone mapping. Render thread
 * only; never share an instance between viewports. Defaults: base-pass velocity on,
 * translucent velocity on with alpha threshold .5, current-frame weight .1.
 * Previous mesh positions include built-in morph/skin deformation; CPU snapshots
 * currently trade CPU/bandwidth for correctness on ordinary animated geometry.
 * GPU Scene rigid instances retain native indirect drawing and GPU-resident history.
 * Custom vertex displacement/instancing need a motion integration before use.
 * Output and history use an unjittered grid; current color is reconstructed from
 * jittered scene samples. A nearest-depth 3x3 selection supplies silhouette motion
 * and depth history. Rejection tolerates half-float previous-depth quantization,
 * and history clipping pads degenerate color ranges for half-float precision.
 * Sky and invalid motion reject history. Transparent velocity selects one nearest
 * alpha-tested layer; it cannot represent multiple blended depths simultaneously.
 * Bucket.Translucent is rendered after post-processing and is not accumulated.
 * <pre>{@code
 * // 第一步：使用单采样 HDR；TAA 必须在曝光、Bloom、Tonemap 之前。
 * FilterPostProcessor fpp = new FilterPostProcessor(assetManager);
 * fpp.setNumSamples(1);
 * TemporalAAFilter taa = new TemporalAAFilter();
 * fpp.addFilter(taa);
 * viewPort.addProcessor(fpp);
 * // 第二步：选择主绘制 MRT，或切换独立 OpaqueVelocityPass。MRT 不足时自动回退。
 * taa.setBasePassOutputsVelocity(true);
 * taa.setBasePassOutputsVelocity(false);
 * // 第三步：按需补充 Transparent 桶的运动；不新增 Depth Prepass。
 * taa.setTranslucentVelocityEnabled(true);
 * taa.setTranslucentAlphaThreshold(.5f);
 * // 第四步：传送、替换场景、相机切换后显式丢弃历史。
 * taa.resetHistory();
 * // 第五步：关闭后恢复原投影和提交路径；重新打开从新历史开始。
 * taa.setEnabled(false);
 * }</pre>
 * See jme3test.post.TestTemporalAA for controls and automated checks.
 */
public final class TemporalAAFilter extends HdrGraphFilter {
    @Override
    protected void onCaptureSuspended(boolean suspended) {
        onPipelineBypass(suspended);
    }

    @Override
    public int getPipelineOrder() {
        return -10;
    }

    @Override
    public boolean isAntiAliasingFilter() {
        return true;
    }

    @Override
    protected void onPipelineBypass(boolean bypassed) {
        resetHistory();
        if (temporal != null) {
            temporal.restoreCamera();
            if (view.getTemporalScene() == temporal) {
                view.setTemporalScene(null);
            }
        }
    }

    private boolean basePassOutputsVelocity = true;
    private boolean translucentVelocityEnabled = true;
    private float translucentAlphaThreshold = .5f;
    private float currentFrameWeight = .1f;
    private transient TemporalScene temporal;
    private transient Material resolve;
    private transient Material depthCopy;
    private transient RenderTarget[] colorHistory = new RenderTarget[2];
    private transient RenderTarget[] depthHistory = new RenderTarget[2];
    private transient RenderTargetDefinition colorDefinition;
    private transient RenderTargetDefinition depthDefinition;
    private transient int historyIndex;
    private transient int sampleIndex;
    private transient boolean reset = true;
    private transient Vector2f jitter = new Vector2f();

    public TemporalAAFilter() {

        super("TemporalAA");

    }

    /**
     * Prefer base-pass MRT output (default) or use separate opaque velocity.
     * Insufficient MRT support falls back to the separate pass. Change on the
     * render thread between frames; switching resets the TAA history.
     *
     * @param value true to prefer the base pass, false for the separate pass
     */
    public void setBasePassOutputsVelocity(boolean value) {
        if (value != basePassOutputsVelocity) {
            resetHistory();
        }
        basePassOutputsVelocity = value;
    }
    public boolean isBasePassOutputsVelocity() {
        return basePassOutputsVelocity;
    }
    /** Enables nearest alpha-tested Transparent motion, independent of opaque mode. */
    public void setTranslucentVelocityEnabled(boolean value) {
        if (value != translucentVelocityEnabled) {
            resetHistory();
        }
        translucentVelocityEnabled = value;
    }
    public boolean isTranslucentVelocityEnabled() {
        return translucentVelocityEnabled;
    }
    /** Sets alpha coverage threshold [0,1], default .5; does not change scene color. */
    public void setTranslucentAlphaThreshold(float value) {
        translucentAlphaThreshold = bounded(value, 0, 1, "Translucent alpha threshold");
        resetHistory();
    }
    /** Weight of the current frame, from 0.01 to 1. Higher values use less history and reduce ghosting. */
    public void setCurrentFrameWeight(float value) {
        currentFrameWeight = bounded(value, .01f, 1, "Current frame weight");
    }
    public float getCurrentFrameWeight() {
        return currentFrameWeight;
    }
    public float getTranslucentAlphaThreshold() {
        return translucentAlphaThreshold;
    }
    /** Render-thread camera cut / scene replacement notification. */
    public void resetHistory() {
        reset = true;
        sampleIndex = 0;
        if (temporal != null) {
            temporal.resetHistory();
        }
    }

    @Override
    protected boolean isRequiresDepthTexture() {
        return true;
    }

    @Override
    protected void initialize() {
        release();
        if (format == null || !manager.getRenderer().getCaps().contains(Caps.GLSL330)
                || !manager.getRenderer().getCaps().contains(Caps.FrameBufferMRT)) {
            throw new IllegalStateException("Temporal AA requires GLSL330 and floating-point MRT");
        }
        temporal = new TemporalScene(assets, manager, view);
        resolve = shader("TemporalAA");
        depthCopy = shader("TemporalDepth");
        colorDefinition = new RenderTargetDefinition(1f, 1, null, format);
        depthDefinition = new RenderTargetDefinition(1f, 1, null, Image.Format.RGBA32F);
        for (int i = 0; i < 2; i++) {
            colorHistory[i] = colorDefinition.create(manager.getRenderer(), width, height);
            colorHistory[i].getColorTexture(0).setMagFilter(com.jme3.texture.Texture.MagFilter.Bilinear);
            colorHistory[i].getColorTexture(0).setMinFilter(com.jme3.texture.Texture.MinFilter.BilinearNoMipMaps);
            depthHistory[i] = depthDefinition.create(manager.getRenderer(), width, height);
        }
        resetHistory();
    }

    @Override
    protected void preFrame(float tpf) {
        if (view.getOutputFrameBuffer() != null && view.getOutputFrameBuffer().getSamples() > 1) {
            throw new IllegalStateException("Set FPP samples to 1 before enabling Temporal AA");
        }
        view.setTemporalScene(temporal);
        temporal.configure(basePassOutputsVelocity, translucentVelocityEnabled, translucentAlphaThreshold);
        int sample = sampleIndex++ % 8 + 1;
        jitter.set(halton(sample, 2) - .5f, halton(sample, 3) - .5f);
        temporal.begin(width, height, jitter);
    }

    private static float halton(int index, int radix) {
        float result = 0;
        float fraction = 1;
        while (index > 0) {
            fraction /= radix;
            result += fraction * (index % radix);
            index /= radix;
        }
        return result;
    }

    @Override
    protected ResourceHandle<RenderTarget> build(FilterGraphContext context, ResourceHandle<RenderTarget> input) {
        temporal.restoreCamera();
        var graph = context.getGraph();
        int previous = historyIndex;
        int next = 1 - previous;
        var oldColor = graph.importResource(context.name("HistoryColor"), RenderTarget.class, () -> colorHistory[previous]);
        var oldDepth = graph.importResource(context.name("HistoryDepth"), RenderTarget.class, () -> depthHistory[previous]);
        var nextColor = graph.importResource(context.name("NextColor"), RenderTarget.class, () -> colorHistory[next]);
        var nextDepth = graph.importResource(context.name("NextDepth"), RenderTarget.class, () -> depthHistory[next]);
        resolve.setTexture("Velocity", temporal.getVelocity());
        resolve.setTexture("MotionDepth", temporal.getVelocityDepth());
        resolve.setVector2("InverseSize", new Vector2f(1f / width, 1f / height));
        resolve.setVector2("CurrentJitter", new Vector2f(jitter.x / width, jitter.y / height));
        resolve.setFloat("CurrentWeight", currentFrameWeight);
        resolve.setBoolean("Reset", reset || temporal.isReset());
        var velocityInput = graph.importResource(context.name("SceneVelocity"), com.jme3.texture.Texture2D.class, temporal::getVelocity);
        var motionDepth = graph.importResource(context.name("MotionDepth"), com.jme3.texture.Texture2D.class, temporal::getVelocityDepth);
        context.draw("Resolve", resolve, input, nextColor, Map.of("History", oldColor, "HistoryDepth", oldDepth)).read(velocityInput).read(motionDepth);
        depthCopy.setTexture("MotionDepth", temporal.getVelocityDepth());
        depthCopy.setVector2("InverseSize", new Vector2f(1f / width, 1f / height));
        depthCopy.setVector2("CurrentJitter", new Vector2f(jitter.x / width, jitter.y / height));
        context.draw("StoreDepth", depthCopy, input, nextDepth).read(motionDepth);
        graph.addPass(context.name("CommitHistory"), pass -> {
            historyIndex = next;
            reset = false;
            temporal.commit();
        }).read(nextColor).read(nextDepth).sideEffect();
        return nextColor;
    }

    @Override
    public void setEnabled(boolean enabled) {
        if (enabled != isEnabled()) {
            resetHistory();
            if (!enabled && temporal != null) {
                temporal.restoreCamera();
                if (view.getTemporalScene() == temporal) {
                    view.setTemporalScene(null);
                }
            }
        }
        super.setEnabled(enabled);
    }

    private void release() {
        if (temporal != null) {
            temporal.close();
            temporal = null;
        }
        for (int i = 0; i < 2; i++) {
            if (colorHistory[i] != null) {
                colorDefinition.destroy(colorHistory[i]);
                colorHistory[i] = null;
            }
            if (depthHistory[i] != null) {
                depthDefinition.destroy(depthHistory[i]);
                depthHistory[i] = null;
            }
        }
    }
    @Override
    protected void cleanUpFilter(Renderer renderer) {
        release();
    }

    @Override
    public void write(JmeExporter exporter) throws IOException {
        super.write(exporter);
        var out = exporter.getCapsule(this);
        out.write(basePassOutputsVelocity, "basePassOutputsVelocity", true);
        out.write(translucentVelocityEnabled, "translucentVelocityEnabled", true);
        out.write(translucentAlphaThreshold, "translucentAlphaThreshold", .5f);
        out.write(currentFrameWeight, "currentFrameWeight", .1f);
    }
    @Override
    public void read(JmeImporter importer) throws IOException {
        super.read(importer);
        var in = importer.getCapsule(this);
        setBasePassOutputsVelocity(in.readBoolean("basePassOutputsVelocity", true));
        setTranslucentVelocityEnabled(in.readBoolean("translucentVelocityEnabled", true));
        setTranslucentAlphaThreshold(in.readFloat("translucentAlphaThreshold", .5f));
        setCurrentFrameWeight(in.readFloat("currentFrameWeight", .1f));
        resetHistory();
    }
}
