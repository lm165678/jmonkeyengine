package com.jme3.light;

import com.jme3.bounding.BoundingBox;
import com.jme3.bounding.BoundingSphere;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.scene.Spatial;
import com.jme3.util.TempVars;

/**
 * Distant, direction-only environment light. Position is irrelevant; no local
 * parallax correction or distance falloff is applied. The sky SH contains
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
public class SkyLight extends Light {
    private float intensity = 1f;
    private Vector3f[] shCoefficients;
    private com.jme3.texture.TextureCubeMap prefilteredEnvMap;
    private int nbMipMaps;
    private boolean ready;
    private boolean diffuseEnabled = true;
    private String captureRevision = "legacy";

    /**
     * Migrates a legacy global environment asset into an independent sky.
     * Shares immutable captured textures; copies SH and never copies local bounds.
     * Use on the render thread before attaching the light.
     * <pre>{@code
     * // 第一步：读取旧预览环境中的 LightProbe。
     * LightProbe legacy = (LightProbe) environment.getLocalLightList().get(0);
     * // 第二步：转换全局环境；局部反射探针不要执行此转换。
     * SkyLight sky = SkyLight.fromEnvironment(legacy);
     * preview.addLight(sky);
     * // 第三步：关闭预览时移除；共享纹理由资源所有者释放。
     * preview.removeLight(sky);
     * }</pre>
     * @param source legacy global environment capture
     * @return independent sky with default intensity one
     */
    public static SkyLight fromEnvironment(LightProbe source) {
        SkyLight result = new SkyLight();
        result.setPrefilteredMap(source.getPrefilteredEnvMap());
        result.setNbMipMaps(source.getNbMipMaps());
        if (source.getShCoeffs() != null) {
            Vector3f[] copy = new Vector3f[source.getShCoeffs().length];
            for (int i = 0; i < copy.length; i++) {
                copy[i] = source.getShCoeffs()[i].clone();
            }
            result.setShCoeffs(copy);
        }
        result.setReady(source.isReady());
        return result;
    }

    @Override
    public SkyLight clone() {
        SkyLight result = (SkyLight) super.clone();
        if (shCoefficients != null) {
            result.shCoefficients = new Vector3f[shCoefficients.length];
            for (int i = 0; i < shCoefficients.length; i++) {
                result.shCoefficients[i] = shCoefficients[i].clone();
            }
        }
        return result;
    }

    @Override
    public Type getType() {
        return Type.Sky;
    }

    /** Captured diffuse irradiance/pi, in native polynomial SH order. */
    public Vector3f[] getShCoeffs() {
        return shCoefficients;
    }

    /** Install captured SH on the render thread, then mark ready after all maps. */
    public void setShCoeffs(Vector3f[] coefficients) {
        shCoefficients = coefficients;
        invalidateCaptureRevision();
    }

    public com.jme3.texture.TextureCubeMap getPrefilteredEnvMap() {
        return prefilteredEnvMap;
    }

    /** Install captured specular radiance; see SkyLightControl for full capture usage. */
    public void setPrefilteredMap(com.jme3.texture.TextureCubeMap map) {
        prefilteredEnvMap = map;
        invalidateCaptureRevision();
    }

    public int getNbMipMaps() {
        return nbMipMaps;
    }

    public void setNbMipMaps(int count) {
        nbMipMaps = count;
    }

    public boolean isReady() {
        return ready;
    }

    public void setReady(boolean value) {
        ready = value;
    }

    public boolean isDiffuseEnabled() {
        return diffuseEnabled;
    }

    /** Disable only sky diffuse; specular remains available. Render thread only. */
    public void setDiffuseEnabled(boolean value) {
        diffuseEnabled = value;
    }

    /** Stable across save/load; changes when capture is requested or replaced. */
    public String getCaptureRevision() {
        return captureRevision;
    }

    /** Notify after in-place environment edits; normally called by SkyLightControl. */
    public void invalidateCaptureRevision() {
        captureRevision = java.util.UUID.randomUUID().toString();
    }


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
        com.jme3.export.OutputCapsule out = exporter.getCapsule(this);
        out.write(intensity, "intensity", 1f);
        out.write(shCoefficients, "shCoeffs", null);
        out.write(prefilteredEnvMap, "prefilteredEnvMap", null);
        out.write(nbMipMaps, "nbMipMaps", 0);
        out.write(ready, "ready", false);
        out.write(diffuseEnabled, "diffuseEnabled", true);
        out.write(captureRevision, "captureRevision", "legacy");
    }

    @Override
    public void read(com.jme3.export.JmeImporter importer) throws java.io.IOException {
        super.read(importer);
        com.jme3.export.InputCapsule in = importer.getCapsule(this);
        com.jme3.export.Savable[] values = in.readSavableArray("shCoeffs", null);
        if (values != null) {
            shCoefficients = new Vector3f[values.length];
            for (int i = 0; i < values.length; i++) {
                shCoefficients[i] = (Vector3f) values[i];
            }
        }
        prefilteredEnvMap = (com.jme3.texture.TextureCubeMap) in.readSavable("prefilteredEnvMap", null);
        nbMipMaps = in.readInt("nbMipMaps", 0);
        ready = in.readBoolean("ready", false) && values != null && values.length == 9;
        diffuseEnabled = in.readBoolean("diffuseEnabled", true);
        captureRevision = in.readString("captureRevision", "legacy");
        float stored = in.readFloat("intensity", 1f);
        intensity = Float.isFinite(stored) && stored >= 0f ? stored : 1f;
    }

    public SkyLight() {
        setName("SkyLight");
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
