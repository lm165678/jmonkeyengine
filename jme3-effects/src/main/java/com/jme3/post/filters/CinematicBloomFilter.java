package com.jme3.post.filters;

import com.jme3.export.*;
import com.jme3.material.Material;
import com.jme3.math.Vector2f;
import com.jme3.post.FilterGraphContext;
import com.jme3.renderer.framegraph.*;
import java.io.IOException;
import java.util.Map;

/**
 * HDR multi-scale Bloom with soft-knee extraction, tent reconstruction and
 * normalized skip connections. Unlike a repeated blur of the smallest image,
 * each scale contributes. Threshold is in exposed linear units. Defaults:
 * threshold 1, knee .5, intensity .08, scatter .7, five half-resolution levels.
 * No gamma encoding, no extra scene capture, no depth attachment. Requires float
 * targets; unsupported GPUs bypass. Does not guarantee temporal stability for
 * subpixel highlights without temporal AA. Configure on the render thread.
 * <pre>{@code
 * // 第一步：HDR FPP 中先曝光，再加入多尺度 Bloom。
 * CinematicBloomFilter bloom = new CinematicBloomFilter();
 * bloom.setThreshold(1f);
 * bloom.setSoftKnee(.5f);
 * bloom.setIntensity(.08f);
 * bloom.setScatter(.7f);
 * fpp.addFilter(bloom);
 * // 第二步：随后加入 ColorGradingFilter，只在最终输出进行一次 sRGB 编码。
 * // 第三步：bloom.setEnabled(false) 旁路；true 恢复参数，FPP 管理临时显存。
 * // 第四步：threshold=0 可使用无阈值的柔和散射；无需修改场景材质。
 * }</pre>
 */
public class CinematicBloomFilter extends HdrGraphFilter {
    @Override
    public int getPipelineOrder() {
        return 40;
    }

    private float threshold = 1f;
    private float knee = .5f;
    private float intensity = .08f;
    private float scatter = .7f;
    private final Material[] down = new Material[5];
    private final Material[] up = new Material[4];
    private Material composite;

    public CinematicBloomFilter() {
        super("CinematicBloom");
    }

    @Override
    protected void initialize() {
        for (int i = 0; i < down.length; i++) {
            down[i] = shader("BloomDown");
        }
        for (int i = 0; i < up.length; i++) {
            up[i] = shader("BloomUp");
        }
        composite = shader("BloomComposite");
    }

    /** @param value exposed-linear threshold [0,65504], zero disables extraction */
    public void setThreshold(float value) {
        threshold = bounded(value, 0f, 65504f, "Threshold");
    }

    /** @param value transition width as a fraction of threshold [0,1] */
    public void setSoftKnee(float value) {
        knee = bounded(value, 0f, 1f, "Soft knee");
    }

    /** @param value additive Bloom intensity [0,10] */
    public void setIntensity(float value) {
        intensity = bounded(value, 0f, 10f, "Bloom intensity");
    }

    /** @param value coarse-scale contribution [0,1], independent of resolution */
    public void setScatter(float value) {
        scatter = bounded(value, 0f, 1f, "Bloom scatter");
    }

    @Override
    protected ResourceHandle<RenderTarget> build(FilterGraphContext context, ResourceHandle<RenderTarget> input) {
        if (format == null || intensity == 0f) {
            return input;
        }
        java.util.List<ResourceHandle<RenderTarget>> levels = new java.util.ArrayList<>();
        ResourceHandle<RenderTarget> source = input;
        int w = width;
        int h = height;
        int[] widths = new int[5];
        int[] heights = new int[5];
        for (int i = 0; i < 5; i++) {
            down[i].setVector2("TexelSize", new Vector2f(1f / w, 1f / h));
            down[i].setFloat("Threshold", threshold);
            down[i].setFloat("Knee", knee);
            down[i].setBoolean("Extract", i == 0);
            w = Math.max(1, (w + 1) / 2);
            h = Math.max(1, (h + 1) / 2);
            widths[i] = w;
            heights[i] = h;
            var target = context.createColor("Down" + i, w, h, format);
            context.draw("Down" + i, down[i], source, target);
            levels.add(target);
            source = target;
            if (w == 1 && h == 1) {
                break;
            }
        }
        for (int i = levels.size() - 2; i >= 0; i--) {
            up[i].setVector2("TexelSize", new Vector2f(1f / widths[i + 1], 1f / heights[i + 1]));
            up[i].setFloat("Scatter", scatter);
            var target = context.createColor("Up" + i, widths[i], heights[i], format);
            context.draw("Up" + i, up[i], source, target, Map.of("Detail", levels.get(i)));
            source = target;
        }
        composite.setFloat("Intensity", intensity);
        composite.setVector2("BloomSize", new Vector2f(widths[0], heights[0]));
        var output = context.createColor("Composite", 1f, format);
        context.draw("Composite", composite, input, output, Map.of("Bloom", source));
        return output;
    }

    @Override
    public void write(JmeExporter exporter) throws IOException {
        super.write(exporter);
        var out = exporter.getCapsule(this);
        out.write(threshold, "threshold", 1f);
        out.write(knee, "knee", .5f);
        out.write(intensity, "intensity", .08f);
        out.write(scatter, "scatter", .7f);
    }

    @Override
    public void read(JmeImporter importer) throws IOException {
        super.read(importer);
        var in = importer.getCapsule(this);
        try {
            setThreshold(in.readFloat("threshold", 1f));
            setSoftKnee(in.readFloat("knee", .5f));
            setIntensity(in.readFloat("intensity", .08f));
            setScatter(in.readFloat("scatter", .7f));
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid Bloom settings", invalid);
        }
    }
}
