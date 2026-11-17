package com.jme3.post.filters;

import com.jme3.asset.AssetManager;
import com.jme3.export.JmeExporter;
import com.jme3.export.JmeImporter;
import com.jme3.material.Material;
import com.jme3.post.Filter;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import java.io.IOException;

/**
 * Manual scene-linear exposure: RGB *= 2^EV; alpha is preserved. Default 0 EV
 * is identity. No gamma, tone curve, automatic metering or temporal pre-exposure.
 * Install after scene compositing, before scene-color Bloom and tone mapping.
 * Separate glow-map captures are not exposed. Never install in GI bake captures.
 * Use RGBA16F (RGBA32F fallback); omit the HDR chain if neither is supported.
 * Mutate on the owning application thread; disable or restore 0 EV to bypass.
 * The owning FPP manages GPU resources and cleanup.
 * <pre>{@code
 * // 第一步：在 simpleInitApp 中创建 HDR 处理器，先检查浮点颜色目标能力。
 * FilterPostProcessor fpp = new FilterPostProcessor(assetManager);
 * if (renderer.getCaps().contains(Caps.HalfFloatColorBufferRGBA)) {
 *     fpp.setFrameBufferFormat(Image.Format.RGBA16F);
 * } else if (renderer.getCaps().contains(Caps.FloatColorBufferRGBA)) {
 *     fpp.setFrameBufferFormat(Image.Format.RGBA32F);
 * } else {
 *     return; // 当前设备不支持所需 HDR 目标，保留原始渲染。
 * }
 * // 第二步：曝光放在 Bloom、色调映射之前；+1 EV 为两倍亮度。
 * ExposureFilter exposure = new ExposureFilter();
 * exposure.setExposureCompensation(1f);
 * fpp.addFilter(exposure);
 * fpp.addFilter(new BloomFilter(BloomFilter.GlowMode.Scene));
 * fpp.addFilter(new ToneMapFilter());
 * viewPort.addProcessor(fpp);
 * // 第三步：UI 线程修改需排入应用线程；0 恢复原始亮度。
 * app.enqueue(() -> exposure.setExposureCompensation(0f));
 * // 第四步：结束使用时移除处理器，清理它拥有的资源。
 * // viewPort.removeProcessor(fpp);
 * }</pre>
 */
public class ExposureFilter extends Filter {
    private float exposureCompensation;

    /** Creates neutral (0 EV) exposure. */
    public ExposureFilter() {
        super("ExposureFilter");
    }

    /**
     * Sets compensation in stops. HDR overflow still depends on input and target format.
     * @param ev finite value in [-16,16]; positive brightens, negative darkens
     * @throws IllegalArgumentException if outside the supported range
     */
    public void setExposureCompensation(float ev) {
        if (!Float.isFinite(ev) || ev < -16f || ev > 16f) {
            throw new IllegalArgumentException("Exposure must be finite and within [-16,16] EV");
        }
        exposureCompensation = ev;
        if (material != null) {
            material.setFloat("ExposureMultiplier", (float) Math.pow(2, ev));
        }
    }

    /** @return exposure compensation in stops, default 0 */
    public float getExposureCompensation() {
        return exposureCompensation;
    }

    @Override
    protected void initFilter(AssetManager assets, RenderManager manager, ViewPort view, int width, int height) {
        material = new Material(assets, "Common/MatDefs/Post/Exposure.j3md");
        setExposureCompensation(exposureCompensation);
    }

    @Override
    protected Material getMaterial() {
        return material;
    }

    @Override
    public void write(JmeExporter exporter) throws IOException {
        super.write(exporter);
        exporter.getCapsule(this).write(exposureCompensation, "exposureCompensation", 0f);
    }

    @Override
    public void read(JmeImporter importer) throws IOException {
        super.read(importer);
        try {
            setExposureCompensation(importer.getCapsule(this).readFloat("exposureCompensation", 0f));
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid serialized exposure", invalid);
        }
    }
}
