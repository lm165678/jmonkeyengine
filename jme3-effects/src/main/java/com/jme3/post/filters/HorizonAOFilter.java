package com.jme3.post.filters;

import com.jme3.export.*;
import com.jme3.material.Material;
import com.jme3.math.Vector2f;
import com.jme3.post.FilterGraphContext;
import com.jme3.renderer.framegraph.*;
import java.io.IOException;
import java.util.Map;

/**
 * Depth-only horizon AO, eight directions/four steps, half-resolution evaluation,
 * bilateral denoising and depth-aware full-resolution reconstruction. Works with
 * Forward/Deferred without a material-specific normal prepass. Not GTAO, temporal
 * AO or ray tracing; offscreen/hidden surfaces remain unavailable. In this FPP
 * compatibility path AO multiplies scene color (including direct light), so keep
 * intensity modest when GI already supplies occlusion. Defaults radius1 world
 * unit, intensity.7, bias.1. Float-target fallback/bypass follows HDR filters.
 * <pre>{@code
 * // 第一步：在 HDR FPP 最前面添加 AO，先于曝光/Bloom/Tonemap。
 * HorizonAOFilter ao = new HorizonAOFilter();
 * ao.setRadius(1f); // 根据模型世界尺度调整，而不是根据窗口大小调整
 * ao.setIntensity(.7f);
 * fpp.addFilter(ao);
 * // 第二步：应用线程显示纯 AO；后续效果自动旁路，曝光不会冲白预览。
 * ao.setShowOnly(true);
 * // 第三步：恢复合成或关闭效果；不修改后续效果的 enabled 状态。
 * ao.setShowOnly(false);
 * ao.setEnabled(false);
 * // 第四步：由 FPP 在 resize/移除时管理资源，不需要场景材质改动。
 * }</pre>
 */
public class HorizonAOFilter extends HdrGraphFilter {
    private float radius = 1f;
    private float intensity = .7f;
    private float bias = .1f;
    private boolean showOnly;
    private Material evaluate;
    private Material blurX;
    private Material blurY;
    private Material composite;

    public HorizonAOFilter() {
        super("HorizonAO");
    }

    @Override
    protected boolean isRequiresDepthTexture() {
        return true;
    }

    @Override
    public boolean isDiagnosticOutput() {
        return showOnly;
    }

    /** @param value world-space radius, finite (0,1000] */
    public void setRadius(float value) {
        radius = bounded(value, .0001f, 1000f, "AO radius");
    }

    /** @param value occlusion exponent [0,4]; zero is neutral */
    public void setIntensity(float value) {
        intensity = bounded(value, 0f, 4f, "AO intensity");
    }

    /** @param value normal-angle self-occlusion bias [0,.9] */
    public void setBias(float value) {
        bias = bounded(value, 0f, .9f, "AO bias");
    }

    /** @param value true presents raw AO and bypasses subsequent effects */
    public void setShowOnly(boolean value) {
        showOnly = value;
    }

    @Override
    protected void initialize() {
        evaluate = shader("HorizonAO");
        blurX = shader("AOBilateral");
        blurY = shader("AOBilateral");
        composite = shader("AOComposite");
    }

    @Override
    protected ResourceHandle<RenderTarget> build(FilterGraphContext context, ResourceHandle<RenderTarget> input) {
        if (format == null) {
            return input;
        }
        var projection = view.getCamera().getProjectionMatrix();
        var inverse = projection.invert();
        evaluate.setMatrix4("InverseProjection", inverse);
        evaluate.setVector2("PixelSize", new Vector2f(1f / width, 1f / height));
        evaluate.setVector2("ProjectionScale", new Vector2f(projection.m00, projection.m11));
        evaluate.setBoolean("Orthographic", view.getCamera().isParallelProjection());
        evaluate.setFloat("Radius", radius);
        evaluate.setFloat("Bias", bias);
        int w = Math.max(1, (width + 1) / 2);
        int h = Math.max(1, (height + 1) / 2);
        blurX.setVector2("Direction", new Vector2f(1f / w, 0f));
        blurY.setVector2("Direction", new Vector2f(0f, 1f / h));
        blurX.setFloat("Sharpness", 10f / radius);
        blurY.setFloat("Sharpness", 10f / radius);
        composite.setMatrix4("InverseProjection", inverse);
        composite.setVector2("AOSize", new Vector2f(w, h));
        composite.setFloat("Sharpness", 10f / radius);
        composite.setFloat("Intensity", intensity);
        composite.setBoolean("ShowOnly", showOnly);
        var ao = context.createColor("Occlusion", w, h, format);
        var horizontal = context.createColor("Horizontal", w, h, format);
        var vertical = context.createColor("Vertical", w, h, format);
        var output = context.createColor("Composite", 1f, format);
        context.draw("HorizonSearch", evaluate, input, ao);
        context.draw("BilateralX", blurX, ao, horizontal);
        context.draw("BilateralY", blurY, horizontal, vertical);
        context.draw("Composite", composite, input, output, Map.of("Occlusion", vertical));
        return output;
    }

    @Override
    public void write(JmeExporter exporter) throws IOException {
        super.write(exporter);
        var out = exporter.getCapsule(this);
        out.write(radius, "radius", 1f);
        out.write(intensity, "intensity", .7f);
        out.write(bias, "bias", .1f);
        // Diagnostic state is intentionally not serialized as a scene effect.
    }

    @Override
    public void read(JmeImporter importer) throws IOException {
        super.read(importer);
        var in = importer.getCapsule(this);
        try {
            setRadius(in.readFloat("radius", 1f));
            setIntensity(in.readFloat("intensity", .7f));
            setBias(in.readFloat("bias", .1f));
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid AO settings", invalid);
        }
    }
}
