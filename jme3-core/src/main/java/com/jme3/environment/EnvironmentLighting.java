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
            if (light instanceof LightProbe && ((LightProbe) light).isReady()
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
            if (!(light instanceof LightProbe) || !light.isEnabled()
                    || !((LightProbe) light).isReady()) {
                continue;
            }
            if (light instanceof SkyLight) {
                if (sky == null) {
                    sky = (SkyLight) light;
                }
            } else if (local == null) {
                local = (LightProbe) light;
            }
        }
        LightProbe reflection = local != null ? local : sky;
        if (reflection == null) {
            return;
        }
        // GLSL validates sampler types even in an inactive uniform branch.
        // Managed-shadow sampler2D uniforms must not alias the first cubemap.
        units.textureUnit = Math.max(1, units.textureUnit);
        shader.getUniform("g_ShadowAtlas").setValue(VarType.Int, 0);
        shader.getUniform("g_ShadowRecords").setValue(VarType.Int, 0);
        LightProbe diffuse = local != null && local.isDiffuseEnabled() ? local : sky;
        shader.getUniform("g_EnvironmentSh").setValue(VarType.Vector3Array,
                diffuse != null && diffuse.isDiffuseEnabled() ? diffuse.getShCoeffs() : ZERO_SH);
        shader.getUniform("g_SkySh").setValue(VarType.Vector3Array,
                sky != null && sky.isDiffuseEnabled() && (local == null || !local.isDiffuseEnabled())
                        ? sky.getShCoeffs() : ZERO_SH);
        shader.getUniform("g_EnvironmentData").setValue(VarType.Vector4,
                new Vector4f(reflection.getNbMipMaps(), local == null ? 1 : 0,
                        sky == null ? 0 : 1, local != null && local.isDiffuseEnabled() ? 1 : 0));
        try {
            renderer.setTexture(units.textureUnit, reflection.getPrefilteredEnvMap());
        } catch (com.jme3.renderer.TextureUnitException exception) {
            throw new IllegalStateException("No texture unit available for environment lighting", exception);
        }
        int reflectionUnit = units.textureUnit++;
        shader.getUniform("g_EnvironmentMap").setValue(VarType.Int, reflectionUnit);
        shader.getUniform("g_EnvironmentLocalData").setValue(VarType.Matrix4,
                local != null ? local.getUniformMatrix() : LightProbe.FALLBACK_MATRIX);
        int skyUnit = reflectionUnit;
        if (sky != null && sky != reflection) {
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
