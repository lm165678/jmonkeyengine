package com.jme3.post.filters;

import com.jme3.asset.AssetManager;
import com.jme3.export.*;
import com.jme3.material.Material;
import com.jme3.math.Vector3f;
import com.jme3.post.Filter;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import java.io.IOException;

/**
 * SDR tone mapping and scene-linear grading, separate from exposure and display
 * encoding. Neutral uses the engine's Khronos PBR Neutral function; Filmic uses
 * the existing filmic shoulder with a configurable white point. Neither is an
 * implementation of UE's full ACES/color-management pipeline. Defaults: Neutral,
 * saturation/contrast/gain 1, white point 11.2. RGB gain is an artistic control,
 * not a Kelvin/chromatic-adaptation model. Alpha is preserved. No gamma power.
 * <pre>{@code
 * // 第一步：放在自动曝光、HDR Bloom 之后，避免重复添加 ToneMapFilter。
 * ColorGradingFilter grade = new ColorGradingFilter();
 * grade.setCurve(ColorGradingFilter.Curve.Neutral);
 * // 第二步：在渲染线程微调整体色调，默认参数为中性。
 * grade.setSaturation(1.05f);
 * grade.setContrast(1.02f);
 * grade.setGain(new Vector3f(1.02f, 1f, .98f));
 * fpp.addFilter(grade);
 * // 第三步：最终 framebuffer 负责一次 sRGB 编码，不再加 pow(color,1/2.2)。
 * // 第四步：setEnabled(false) 用于 HDR 调试，不代表适合直接显示的 SDR 结果。
 * }</pre>
 */
public class ColorGradingFilter extends Filter {
    public enum Curve {
        /** Khronos PBR Neutral, preserving saturated colors before the shoulder. */
        Neutral,
        /** Filmic shoulder normalized by the configured white point. */
        Filmic
    }
    private Curve curve = Curve.Neutral;
    private float saturation = 1f;
    private float contrast = 1f;
    private float whitePoint = 11.2f;
    private Vector3f gain = new Vector3f(1f, 1f, 1f);

    public ColorGradingFilter() {
        super("ColorGrading");
    }

    /** @param curve non-null curve, default Neutral; render thread only */
    public void setCurve(Curve curve) {
        this.curve = java.util.Objects.requireNonNull(curve);
    }

    /** @param value saturation [0,2]; 0 is grayscale, 1 is neutral */
    public void setSaturation(float value) {
        saturation = HdrGraphFilter.bounded(value, 0f, 2f, "Saturation");
    }

    /** @param value log-domain contrast [.25,2], pivot .18, default 1 */
    public void setContrast(float value) {
        contrast = HdrGraphFilter.bounded(value, .25f, 2f, "Contrast");
    }

    /** @param value filmic white point [1,100]; does not affect Neutral */
    public void setWhitePoint(float value) {
        whitePoint = HdrGraphFilter.bounded(value, 1f, 100f, "White point");
    }

    /** @param value RGB artistic gains in [0,4], copied, default (1,1,1) */
    public void setGain(Vector3f value) {
        java.util.Objects.requireNonNull(value);
        HdrGraphFilter.bounded(value.x, 0f, 4f, "Red gain");
        HdrGraphFilter.bounded(value.y, 0f, 4f, "Green gain");
        HdrGraphFilter.bounded(value.z, 0f, 4f, "Blue gain");
        gain.set(value);
    }

    @Override
    protected void initFilter(AssetManager assets, RenderManager manager, ViewPort view, int width, int height) {
        material = new Material(assets, "Common/MatDefs/Post/Cinematic/ColorGrading.j3md");
    }

    @Override
    protected Material getMaterial() {
        material.setInt("Curve", curve.ordinal());
        material.setFloat("Saturation", saturation);
        material.setFloat("Contrast", contrast);
        material.setFloat("WhitePoint", whitePoint);
        material.setVector3("Gain", gain);
        return material;
    }

    @Override
    public void write(JmeExporter exporter) throws IOException {
        super.write(exporter);
        var out = exporter.getCapsule(this);
        out.write(curve, "curve", Curve.Neutral);
        out.write(saturation, "saturation", 1f);
        out.write(contrast, "contrast", 1f);
        out.write(whitePoint, "whitePoint", 11.2f);
        out.write(gain, "gain", new Vector3f(1, 1, 1));
    }

    @Override
    public void read(JmeImporter importer) throws IOException {
        super.read(importer);
        var in = importer.getCapsule(this);
        try {
            setCurve(in.readEnum("curve", Curve.class, Curve.Neutral));
            setSaturation(in.readFloat("saturation", 1f));
            setContrast(in.readFloat("contrast", 1f));
            setWhitePoint(in.readFloat("whitePoint", 11.2f));
            setGain((Vector3f) in.readSavable("gain", new Vector3f(1, 1, 1)));
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid grading settings", invalid);
        }
    }
}
