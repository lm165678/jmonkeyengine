package com.jme3.light;

import com.jme3.bounding.BoundingBox;
import com.jme3.bounding.BoundingSphere;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.scene.Spatial;
import com.jme3.util.TempVars;

/**
 * Distant, direction-only environment light. Position is irrelevant; no local
 * parallax correction or distance falloff is applied. The inherited SH contains
 * diffuse irradiance/pi and the prefiltered cubemap contains specular radiance.
 * This is an unoccluded fallback, NOT a replacement for local GI visibility.
 * New GI volumes add bounced radiance and modulate this sky by local sky
 * visibility. Legacy total-irradiance volumes retain replacement semantics. Specular
 * falls back to this sky when no local reflection probe covers the geometry.
 * One enabled ready sky per light scope is supported; the first wins.
 * Use SkyLightControl on the sky for automatic baking and lifecycle management.
 * <pre>{@code
 * // 第一步：创建天空；SkyFactory 自动添加 SkyLightControl。
 * Spatial sky = SkyFactory.createSky(assetManager, "Textures/Sky/Path.hdr",
 *         SkyFactory.EnvMapType.EquirectMap);
 * rootNode.attachChild(sky);
 * // 第二步：天空变化后，在渲染线程请求重新捕捉。
 * sky.getControl(SkyLightControl.class).requestRebake();
 * // 第三步：仅显示背景时禁用该 control；重新启用可恢复照明。
 * sky.getControl(SkyLightControl.class).setEnabled(false);
 * }</pre>
 * Sky changes require rebaking existing GI volumes too. No automatic indoor
 * shadowing, dynamic occlusion, or multi-bounce solution is implied.
 */
public class SkyLight extends LightProbe {
    private float intensity = 1f;

    /**
     * Returns the environment lighting multiplier, default 1 (unscaled).
     * @return nonnegative lighting intensity
     */
    public float getIntensity() {
        return intensity;
    }

    /**
     * Scales sky diffuse and fallback specular lighting, independently of the
     * visible background and local reflection probes. No environment recapture
     * is needed. Existing baked GI is not recomputed. Call on the render thread.
     * <pre>{@code
     * // 第一步：获取 SkyFactory 创建的天空上的天光。
     * SkyLight light = sky.getControl(SkyLightControl.class).getLight();
     * // 第二步：调节天光；0 关闭贡献，2 加倍，允许大于 1。
     * light.setIntensity(2f);
     * // 第三步：恢复默认；保存 J3O 会保留强度，旧文件默认 1。
     * light.setIntensity(1f);
     * }</pre>
     * @param intensity finite, nonnegative multiplier
     * @throws IllegalArgumentException for negative or non-finite values
     */
    public void setIntensity(float intensity) {
        if (!Float.isFinite(intensity) || intensity < 0f) {
            throw new IllegalArgumentException("Sky intensity must be finite and nonnegative");
        }
        this.intensity = intensity;
    }

    @Override
    public void write(com.jme3.export.JmeExporter exporter) throws java.io.IOException {
        super.write(exporter);
        exporter.getCapsule(this).write(intensity, "intensity", 1f);
    }

    @Override
    public void read(com.jme3.export.JmeImporter importer) throws java.io.IOException {
        super.read(importer);
        float stored = importer.getCapsule(this).readFloat("intensity", 1f);
        intensity = Float.isFinite(stored) && stored >= 0f ? stored : 1f;
    }

    public SkyLight() {
        setName("SkyLight");
        getArea().setRadius(Float.MAX_VALUE);
    }

    @Override
    public boolean intersectsBox(BoundingBox box, TempVars vars) {
        return true;
    }

    @Override
    public boolean intersectsSphere(BoundingSphere sphere, TempVars vars) {
        return true;
    }

    @Override
    public boolean intersectsFrustum(Camera camera, TempVars vars) {
        return true;
    }

    @Override
    protected void computeLastDistance(Spatial owner) {
        lastDistance = Float.MAX_VALUE;
    }
}
