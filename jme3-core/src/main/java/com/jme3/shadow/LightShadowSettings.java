package com.jme3.shadow;

/**
 * Serializable light shadow preferences, with optional viewport-local overrides. The light's serialized castShadow flag is
 * an additional gate: enabling these settings never overrides a disabled light.
 * Defaults: enabled, 512-pixel maps, three directional cascades, lambda 0.65,
 * 200-unit directional distance and continuous tent PCF (radius 1). Mutate on the render thread.
 * Resources are rebuilt by ShadowManager when resolution or cascade count changes.
 * Radius 0 retains hard nearest depth comparison; 1/2 use subtexel-weighted
 * visibility filtering (up to 16/36 depth fetches), without temporal history.
 * Atlas tiles are isolated. Filtering cannot recover detail absent from a low
 * resolution map; increase map size or shorten shadow distance if necessary.
 * These settings affect managed shadows, not legacy shadow filter processors.
 * <pre>{@code
 * // 第一步：在初始化或渲染线程配置光源，场景物体需要 CastAndReceive。
 * LightShadowSettings quality = sun.getShadowSettings();
 * quality.setMapSize(1024);
 * quality.setFilterRadius(2);
 * // 第二步：切换硬阴影用于对照；只改变采样，无需重建场景或重新烘焙实时阴影。
 * quality.setFilterRadius(0);
 * // 第三步：恢复默认软阴影；旧资产的 0/1/2 配置仍然有效。
 * quality.setFilterRadius(1);
 * // 第四步：关闭光源投影后可随时恢复，不会禁用其他灯的阴影。
 * sun.setCastShadow(false);
 * sun.setCastShadow(true);
 * }</pre>
 */
public final class LightShadowSettings implements com.jme3.export.Savable, Cloneable {
    private boolean enabled = true;
    private int mapSize = 512;
    private int cascadeCount = 3;
    private float splitLambda = 0.65f;
    private float shadowDistance = 200;
    private float depthBias = 0.001f;
    private float normalBias = 0.02f;
    private int filterRadius = 1;
    private float strength = 1f;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMapSize() {
        return mapSize;
    }

    /** Resolution of each cascade or point-light cube face, from 64 to 4096. */
    public void setMapSize(int size) {
        if (size < 64 || size > 4096) {
            throw new IllegalArgumentException("Shadow map size must be 64..4096");
        }
        mapSize = size;
    }

    public int getCascadeCount() {
        return cascadeCount;
    }

    /** Directional lights only; point and spot lights ignore this preference. */
    public void setCascadeCount(int count) {
        if (count < 1 || count > 4) {
            throw new IllegalArgumentException("Directional cascade count must be 1..4");
        }
        cascadeCount = count;
    }

    public float getSplitLambda() {
        return splitLambda;
    }

    public void setSplitLambda(float lambda) {
        if (!Float.isFinite(lambda) || lambda < 0 || lambda > 1) {
            throw new IllegalArgumentException("Split lambda must be 0..1");
        }
        splitLambda = lambda;
    }

    public float getShadowDistance() {
        return shadowDistance;
    }

    /** Maximum directional shadow distance in world units. */
    public void setShadowDistance(float distance) {
        if (!Float.isFinite(distance) || distance <= 0) {
            throw new IllegalArgumentException("Positive finite shadow distance required");
        }
        shadowDistance = distance;
    }

    public float getDepthBias() {
        return depthBias;
    }

    public void setDepthBias(float bias) {
        checkBias(bias);
        depthBias = bias;
    }

    public float getNormalBias() {
        return normalBias;
    }

    public void setNormalBias(float bias) {
        checkBias(bias);
        normalBias = bias;
    }

    private static void checkBias(float bias) {
        if (!Float.isFinite(bias) || bias < 0) {
            throw new IllegalArgumentException("Nonnegative finite bias required");
        }
    }

    /** Tent PCF radius: 0 hard, 1 default (up to 16 taps), 2 (up to 36 taps). */
    public int getFilterRadius() {
        return filterRadius;
    }

    public void setFilterRadius(int radius) {
        if (radius < 0 || radius > 2) {
            throw new IllegalArgumentException("PCF radius must be 0..2");
        }
        filterRadius = radius;
    }

    /** Returns direct-light shadow strength, default 1 (including old assets). */
    public float getStrength() {
        return strength;
    }

    /**
     * Sets the occlusion strength, 0..1. Zero leaves this light unshadowed;
     * one preserves full physical occlusion. Intermediate values deliberately
     * transmit part of the occluded direct light; they do not add ambient light
     * or change GI, emission, other lights, or shadow edge softness.
     * Changes apply on the render thread, without rebuilding shadow resources.
     * GI capture uses full strength independently, so diagnostic changes never
     * contaminate a later bake. Legacy shadow processors keep their own intensity.
     * <pre>{@code
     * // 第一步：渲染线程设置灯光自身参数，随场景 J3O 保存。
     * sun.getShadowSettings().setStrength(0.5f);
     * // 第二步：仅当前视口临时对照时，使用视口覆盖参数。
     * renderManager.getShadowManager(viewPort).getSettings(sun).setStrength(0f);
     * // 第三步：恢复完整阴影；0.5 不是 GI 修复，也不会更改烘焙结果。
     * renderManager.getShadowManager(viewPort).getSettings(sun).setStrength(1f);
     * sun.getShadowSettings().setStrength(1f);
     * }</pre>
     * @param value finite shadow strength in [0, 1]
     */
    public void setStrength(float value) {
        if (!Float.isFinite(value) || value < 0 || value > 1) {
            throw new IllegalArgumentException("Shadow strength must be finite and in 0..1");
        }
        strength = value;
    }

    /** Returns an independent copy for a temporary viewport override. */
    @Override
    public LightShadowSettings clone() {
        try {
            return (LightShadowSettings) super.clone();
        } catch (CloneNotSupportedException error) {
            throw new AssertionError(error);
        }
    }

    @Override
    public void write(com.jme3.export.JmeExporter exporter) throws java.io.IOException {
        com.jme3.export.OutputCapsule out = exporter.getCapsule(this);
        out.write(enabled, "enabled", true);
        out.write(mapSize, "mapSize", 512);
        out.write(cascadeCount, "cascadeCount", 3);
        out.write(splitLambda, "splitLambda", 0.65f);
        out.write(shadowDistance, "shadowDistance", 200);
        out.write(depthBias, "depthBias", 0.001f);
        out.write(normalBias, "normalBias", 0.02f);
        out.write(filterRadius, "filterRadius", 1);
        out.write(strength, "strength", 1f);
    }

    @Override
    public void read(com.jme3.export.JmeImporter importer) throws java.io.IOException {
        com.jme3.export.InputCapsule in = importer.getCapsule(this);
        try {
            setEnabled(in.readBoolean("enabled", true));
            setMapSize(in.readInt("mapSize", 512));
            setCascadeCount(in.readInt("cascadeCount", 3));
            setSplitLambda(in.readFloat("splitLambda", 0.65f));
            setShadowDistance(in.readFloat("shadowDistance", 200));
            setDepthBias(in.readFloat("depthBias", 0.001f));
            setNormalBias(in.readFloat("normalBias", 0.02f));
            setFilterRadius(in.readInt("filterRadius", 1));
            setStrength(in.readFloat("strength", 1f));
        } catch (IllegalArgumentException error) {
            throw new java.io.IOException("Invalid serialized shadow settings", error);
        }
    }
}
