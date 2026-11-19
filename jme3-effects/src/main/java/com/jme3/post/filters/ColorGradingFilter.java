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
 * implementation of UE's full ACES/color-management pipeline. UE4 adds AP1 Film,
 * glow/red shaping, blue correction, white balance and cached 32^3 CombineLUTs.
 * This SDR approximation omits HDR/PQ output and full
 * per-tonal-range correction vectors. Tint is a chromaticity approximation.
 * Cache generation runs only on parameter changes; input range is about 0..32.6.
 * Unsupported float/GLSL targets bypass graph grading. Defaults: Neutral,
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
public class ColorGradingFilter extends HdrGraphFilter {
    public enum Curve {
        /** Khronos PBR Neutral, preserving saturated colors before the shoulder. */
        Neutral,
        /** Filmic shoulder normalized by the configured white point. */
        Filmic,
        /** AP1 film response and cached SDR color LUT, compatible in intent with UE 4.27. */
        UE4
    }
    private com.jme3.math.Vector4f film = new com.jme3.math.Vector4f(.88f, .55f, .26f, 0f);
    private float whiteClip = .04f;
    private float temperature = 6500f;
    private float tint;
    private float blueCorrection = .6f;
    private float expandGamut = 1f;
    private Vector3f gamma = new Vector3f(1, 1, 1);
    private Vector3f offset = new Vector3f();
    private Vector3f shadowGain = new Vector3f(1, 1, 1);
    private Vector3f midtoneGain = new Vector3f(1, 1, 1);
    private Vector3f highlightGain = new Vector3f(1, 1, 1);
    private com.jme3.texture.Texture2D userLut;
    private float lutWeight;
    private Material combine;
    private Material apply;
    private com.jme3.renderer.framegraph.RenderTarget lut;
    private com.jme3.renderer.framegraph.RenderTargetDefinition lutDefinition;
    private long revision;
    private long bakedRevision = -1;
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
        revision++;
    }

    /** @param value saturation [0,2]; 0 is grayscale, 1 is neutral */
    public void setSaturation(float value) {
        revision++;
        saturation = HdrGraphFilter.bounded(value, 0f, 2f, "Saturation");
    }

    /** @param value log-domain contrast [.25,2], pivot .18, default 1 */
    public void setContrast(float value) {
        revision++;
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
        revision++;
    }

    /**
     * Film controls: slope [.05,1], toe/shoulder [0,.95], black [0,.2], white [0,.2].
     * Defaults .88/.55/.26/0/.04. Render-thread-only, invalidates cached LUT.
     */
    public void setFilm(float slope, float toe, float shoulder, float black, float white) {
        bounded(slope, .05f, 1f, "Slope");
        bounded(toe, 0f, .95f, "Toe");
        bounded(shoulder, 0f, .95f, "Shoulder");
        bounded(black, 0f, .2f, "Black clip");
        bounded(white, 0f, .2f, "White clip");
        film.set(slope, toe, shoulder, black);
        whiteClip = white;
        revision++;
    }

    /** Bradford temperature [2000,15000] K, tint [-1,1]; tint is an approximation. */
    public void setWhiteBalance(float kelvin, float value) {
        bounded(kelvin, 2000f, 15000f, "Temperature");
        bounded(value, -1f, 1f, "Tint");
        temperature = kelvin;
        tint = value;
        revision++;
    }

    /** Bright saturated-color gamut expansion [0,1], default 1. */
    public void setExpandGamut(float value) {
        expandGamut = bounded(value, 0f, 1f, "Gamut expansion");
        revision++;
    }

    public void setBlueCorrection(float value) {
        blueCorrection = bounded(value, 0f, 1f, "Blue correction");
        revision++;
    }

    private Vector3f checked(Vector3f value, float min, float max) {
        java.util.Objects.requireNonNull(value);
        bounded(value.x, min, max, "Red");
        bounded(value.y, min, max, "Green");
        bounded(value.z, min, max, "Blue");
        return value.clone();
    }

    /** Per-channel grading gamma [.1,4], not display encoding. */
    public void setGamma(Vector3f value) {
        gamma = checked(value, .1f, 4f);
        revision++;
    }

    /** Linear AP1 additive offset [-1,1]. */
    public void setOffset(Vector3f value) {
        offset = checked(value, -1f, 1f);
        revision++;
    }

    /** Tonal RGB gains [0,4], blended using AP1 luminance before grading. */
    public void setTonalGains(Vector3f shadows, Vector3f midtones, Vector3f highlights) {
        Vector3f s = checked(shadows, 0f, 4f);
        Vector3f m = checked(midtones, 0f, 4f);
        Vector3f h = checked(highlights, 0f, 4f);
        shadowGain = s;
        midtoneGain = m;
        highlightGain = h;
        revision++;
    }

    /**
     * Optional display-referred sRGB artist LUT, horizontal N*N by N strip, N=16/32/64.
     * RGB axes are R within each tile, G vertically, B by tile. Store it as Linear
     * data (no hardware sRGB decoding); values represent encoded display colors.
     * Null removes it. Weight [0,1]. Caller must resubmit after changing pixels.
     * <pre>{@code
     * // 第一步：设置 UE4 风格模式；生成 LUT 与采样自动接入当前 FrameGraph。
     * grade.setCurve(ColorGradingFilter.Curve.UE4);
     * grade.setFilm(.88f, .55f, .26f, 0f, .04f);
     * // 第二步：LUT 作为数据加载，关闭 sRGB 自动解码，避免双重转换。
     * Texture2D lut = (Texture2D) assetManager.loadTexture("Textures/Grade.png");
     * lut.getImage().setColorSpace(com.jme3.texture.image.ColorSpace.Linear);
     * grade.setColorLut(lut, .5f);
     * // 第三步：参数改变才重建 32^3 缓存；置 null 恢复程序调色。
     * grade.setColorLut(null, 0f);
     * // 第四步：旧资产可切回 Neutral/Filmic；只添加一个调色/色调映射 Filter。
     * }</pre>
     */
    public void setColorLut(com.jme3.texture.Texture2D texture, float weight) {
        bounded(weight, 0f, 1f, "LUT weight");
        if (texture != null) {
            var image = java.util.Objects.requireNonNull(texture.getImage());
            int n = image.getHeight();
            if ((n != 16 && n != 32 && n != 64) || image.getWidth() != n * n
                    || image.getColorSpace() != com.jme3.texture.image.ColorSpace.Linear) {
                throw new IllegalArgumentException("LUT requires Linear data, size N*N by N (16/32/64)");
            }
            texture = (com.jme3.texture.Texture2D) texture.clone();
            texture.setMinFilter(com.jme3.texture.Texture.MinFilter.BilinearNoMipMaps);
            texture.setMagFilter(com.jme3.texture.Texture.MagFilter.Bilinear);
            texture.setWrap(com.jme3.texture.Texture.WrapMode.EdgeClamp);
        }
        userLut = texture;
        lutWeight = weight;
        revision++;
    }

    @Override
    protected void initialize() {
        releaseLut();
        material = shader("ColorGrading");
        combine = shader("CombineLUTs");
        apply = shader("ApplyColorLUT");
    }

    @Override
    protected com.jme3.renderer.framegraph.ResourceHandle<com.jme3.renderer.framegraph.RenderTarget> build(
            com.jme3.post.FilterGraphContext context,
            com.jme3.renderer.framegraph.ResourceHandle<com.jme3.renderer.framegraph.RenderTarget> input) {
        if (format == null) {
            return input;
        }
        var output = context.createColor("Graded", 1f, format);
        if (curve != Curve.UE4) {
            releaseLut();
            material.setInt("Curve", curve.ordinal());
            material.setFloat("Saturation", saturation);
            material.setFloat("Contrast", contrast);
            material.setFloat("WhitePoint", whitePoint);
            material.setVector3("Gain", gain);
            context.draw("LegacyGrade", material, input, output);
            return output;
        }
        if (lut == null) {
            lutDefinition = new com.jme3.renderer.framegraph.RenderTargetDefinition(1f, 1, null, format);
            lut = lutDefinition.create(manager.getRenderer(), 1024, 32);
            lut.getColorTexture(0).setMinFilter(com.jme3.texture.Texture.MinFilter.BilinearNoMipMaps);
            lut.getColorTexture(0).setMagFilter(com.jme3.texture.Texture.MagFilter.Bilinear);
            lut.getColorTexture(0).setWrap(com.jme3.texture.Texture.WrapMode.EdgeClamp);
        }
        var graph = context.getGraph();
        var cached = graph.importResource(context.name("CombinedLUT"),
                com.jme3.renderer.framegraph.RenderTarget.class, () -> lut);
        if (bakedRevision != revision) {
            combine.setFloat("Saturation", saturation);
            combine.setFloat("Contrast", contrast);
            combine.setVector3("Gain", gain);
            combine.setVector3("Gamma", gamma);
            combine.setVector3("Offset", offset);
            combine.setVector3("ShadowGain", shadowGain);
            combine.setVector3("MidtoneGain", midtoneGain);
            combine.setVector3("HighlightGain", highlightGain);
            combine.setVector4("Film", film);
            combine.setFloat("WhiteClip", whiteClip);
            combine.setFloat("Temperature", temperature);
            combine.setFloat("Tint", tint);
            combine.setFloat("BlueCorrection", blueCorrection);
            combine.setFloat("ExpandGamut", expandGamut);
            combine.setFloat("LutWeight", userLut == null ? 0f : lutWeight);
            combine.setFloat("UserLutSize", userLut == null ? 16f : userLut.getImage().getHeight());
            if (userLut != null) {
                combine.setTexture("UserLut", userLut);
            } else {
                combine.clearParam("UserLut");
            }
            context.draw("CombineLUTs", combine, input, cached);
            long captured = revision;
            graph.addPass(context.name("CommitLUT"), pass -> bakedRevision = captured).read(cached).sideEffect();
        }
        context.draw("ApplyColorLUT", apply, input, output, java.util.Map.of("ColorLut", cached));
        return output;
    }

    private void releaseLut() {
        if (lut != null) {
            lutDefinition.destroy(lut);
            lut = null;
        }
        bakedRevision = -1;
    }

    @Override
    protected void cleanUpFilter(com.jme3.renderer.Renderer renderer) {
        releaseLut();
    }

    @Override
    public void write(JmeExporter exporter) throws IOException {
        super.write(exporter);
        var out = exporter.getCapsule(this);
        out.write(film, "film", new com.jme3.math.Vector4f(.88f,.55f,.26f,0f));
        out.write(whiteClip, "whiteClip", .04f);
        out.write(temperature, "temperature", 6500f);
        out.write(tint, "tint", 0f);
        out.write(expandGamut, "expandGamut", 1f);
        out.write(blueCorrection, "blueCorrection", .6f);
        out.write(gamma, "gradingGamma", new Vector3f(1,1,1));
        out.write(offset, "gradingOffset", new Vector3f());
        out.write(shadowGain, "shadowGain", new Vector3f(1,1,1));
        out.write(midtoneGain, "midtoneGain", new Vector3f(1,1,1));
        out.write(highlightGain, "highlightGain", new Vector3f(1,1,1));
        out.write(userLut, "userLut", null);
        out.write(lutWeight, "lutWeight", 0f);
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
            var f = (com.jme3.math.Vector4f) in.readSavable("film", new com.jme3.math.Vector4f(.88f,.55f,.26f,0f));
            setFilm(f.x,f.y,f.z,f.w,in.readFloat("whiteClip",.04f));
            setWhiteBalance(in.readFloat("temperature",6500f),in.readFloat("tint",0f));
            setExpandGamut(in.readFloat("expandGamut",1f));
            setBlueCorrection(in.readFloat("blueCorrection",.6f));
            setGamma((Vector3f) in.readSavable("gradingGamma",new Vector3f(1,1,1)));
            setOffset((Vector3f) in.readSavable("gradingOffset",new Vector3f()));
            setTonalGains((Vector3f) in.readSavable("shadowGain",new Vector3f(1,1,1)),
                    (Vector3f) in.readSavable("midtoneGain",new Vector3f(1,1,1)),
                    (Vector3f) in.readSavable("highlightGain",new Vector3f(1,1,1)));
            setColorLut((com.jme3.texture.Texture2D) in.readSavable("userLut",null),in.readFloat("lutWeight",0f));
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
