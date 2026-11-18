package com.jme3.environment;

import com.jme3.light.Light;
import com.jme3.light.LightList;
import com.jme3.light.LightProbe;
import com.jme3.light.SkyLight;
import com.jme3.material.Material;
import com.jme3.math.Vector3f;
import com.jme3.math.Vector4f;
import com.jme3.renderer.Renderer;
import com.jme3.shader.Shader;
import com.jme3.shader.VarType;

/** Internal per-draw environment binding shared by Phong and PBR materials. */
public final class EnvironmentLighting {
    public static final Vector3f[] ZERO_SH = new Vector3f[9];
    static {
        for (int i = 0; i < ZERO_SH.length; i++) {
            ZERO_SH[i] = new Vector3f();
        }
    }

    private EnvironmentLighting() {
    }

    /**
     * Tests whether a draw has a ready environment light. A null list means
     * material preloading without a scene light context, not a rendering error.
     * Actual draws select their environment shader variant again with real lights.
     */
    public static boolean available(LightList lights) {
        if (lights == null) {
            return false;
        }
        for (Light light : lights) {
            if (((light instanceof LightProbe && ((LightProbe) light).isReady())
                    || (light instanceof SkyLight && ((SkyLight) light).isReady()))
                    && light.isEnabled()) {
                return true;
            }
        }
        return false;
    }

    public static void bind(Shader shader, Material material, LightList lights,
            Renderer renderer, Material.BindUnits units) {
        if (lights == null || material.getMaterialDef().getMaterialParam("EnvironmentLighting") == null) {
            return;
        }
        SkyLight sky = null;
        LightProbe local = null;
        for (Light light : lights) {
            if (!light.isEnabled()) {
                continue;
            }
            if (light instanceof SkyLight) {
                if (sky == null && ((SkyLight) light).isReady()) {
                    sky = (SkyLight) light;
                }
            } else if (local == null && light instanceof LightProbe && ((LightProbe) light).isReady()) {
                local = (LightProbe) light;
            }
        }
        if (local == null && sky == null) {
            return;
        }
        // GLSL validates sampler types even in an inactive uniform branch.
        // Managed-shadow sampler2D uniforms must not alias the first cubemap.
        units.textureUnit = Math.max(1, units.textureUnit);
        shader.getUniform("g_ShadowAtlas").setValue(VarType.Int, 0);
        shader.getUniform("g_ShadowRecords").setValue(VarType.Int, 0);
        SkyLight diffuse = sky;
        // Separate sky scaling preserves local probe radiance and stored SH data.
        shader.getUniform("g_SkyIntensity").setValue(VarType.Float,
                sky == null ? 1f : sky.getIntensity());
        shader.getUniform("g_EnvironmentSh").setValue(VarType.Vector3Array,
                diffuse != null && diffuse.isDiffuseEnabled() ? diffuse.getShCoeffs() : ZERO_SH);
        shader.getUniform("g_SkySh").setValue(VarType.Vector3Array,
                sky != null && sky.isDiffuseEnabled()
                        ? sky.getShCoeffs() : ZERO_SH);
        shader.getUniform("g_EnvironmentData").setValue(VarType.Vector4,
                new Vector4f((local != null ? local.getNbMipMaps() : sky.getNbMipMaps()), local == null ? 1 : 0,
                        sky == null ? 0 : 1, 0));
        try {
            renderer.setTexture(units.textureUnit, (local != null ? local.getPrefilteredEnvMap() : sky.getPrefilteredEnvMap()));
        } catch (com.jme3.renderer.TextureUnitException exception) {
            throw new IllegalStateException("No texture unit available for environment lighting", exception);
        }
        int reflectionUnit = units.textureUnit++;
        shader.getUniform("g_EnvironmentMap").setValue(VarType.Int, reflectionUnit);
        shader.getUniform("g_EnvironmentLocalData").setValue(VarType.Matrix4,
                local != null ? local.getUniformMatrix() : LightProbe.FALLBACK_MATRIX);
        int skyUnit = reflectionUnit;
        if (sky != null && local != null) {
            try {
                renderer.setTexture(units.textureUnit, sky.getPrefilteredEnvMap());
            } catch (com.jme3.renderer.TextureUnitException exception) {
                throw new IllegalStateException("No texture unit available for sky reflection", exception);
            }
            skyUnit = units.textureUnit++;
        }
        shader.getUniform("g_SkyMap").setValue(VarType.Int, skyUnit);
        shader.getUniform("g_SkyMipCount").setValue(VarType.Float,
                sky == null ? 1f : (float) sky.getNbMipMaps());
    }
}
