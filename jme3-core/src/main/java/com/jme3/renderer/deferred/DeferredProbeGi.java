package com.jme3.renderer.deferred;

import com.jme3.environment.EnvironmentLighting;
import com.jme3.light.Light;
import com.jme3.light.LightProbe;
import com.jme3.light.SkyLight;
import com.jme3.renderer.Renderer;
import com.jme3.renderer.RendererException;
import com.jme3.renderer.TextureUnitException;
import com.jme3.scene.Geometry;
import com.jme3.shader.Shader;
import com.jme3.shader.VarType;
import com.jme3.texture.Texture;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import static com.jme3.renderer.deferred.DeferredRenderContext.uniform;

/** Snapshot of one shared probe atlas and sky for compatible draws in a deferred batch. */
final class DeferredProbeGi {
    private static final String[] TEXTURES = {"GiIrradiance", "GiMoments", "GiSkyVisibility", "GiVolumeData"};
    private static final String[] VALUES = {"GiBias", "GiOrigin", "GiSpacing", "GiCounts", "GiAtlas",
        "GiEncoding", "GiVolumeCount", "GiVolumeDataHeight", "GiTextureSize", "GiFadeDistance"};
    private static final VarType[] TYPES = {VarType.Float, VarType.Vector3, VarType.Vector3, VarType.Vector3,
        VarType.Vector4, VarType.Vector2, VarType.Int, VarType.Int, VarType.Vector4, VarType.Float};
    private final Set<Geometry> eligible = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Texture[] textures = new Texture[TEXTURES.length];
    private final Object[] values = new Object[VALUES.length];
    private SkyLight sky;

    static DeferredProbeGi select(DeferredBatch batch) {
        DeferredProbeGi selected = new DeferredProbeGi();
        for (Geometry geometry : batch.geometries) {
            var definition = geometry.getMaterial().getMaterialDef();
            if (definition.getMaterialParam("DeferredGiPayload") == null) {
                // Every draw must initialize the payload for the winning surface.
                return null;
            }
            if (!Boolean.TRUE.equals(GBufferPass.parameter(geometry, "GiEnabled"))
                    || !(GBufferPass.parameter(geometry, "GiIrradiance") instanceof Texture)
                    || !(GBufferPass.parameter(geometry, "GiMoments") instanceof Texture)
                    || Boolean.TRUE.equals(GBufferPass.parameter(geometry, "GiBakeBounce"))
                    || Boolean.TRUE.equals(GBufferPass.parameter(geometry, "VertexLighting"))
                    || GBufferPass.parameter(geometry, "DebugValuesMode") != null) {
                continue;
            }
            SkyLight sky = null;
            boolean local = false;
            for (Light light : geometry.getWorldLightList()) {
                if (light.isEnabled() && light instanceof LightProbe probe && probe.isReady()) {
                    local = true;
                    break;
                }
                if (light.isEnabled() && light instanceof SkyLight candidate && candidate.isReady() && sky == null) {
                    sky = candidate;
                }
            }
            if (!local && selected.accept(geometry, sky)) {
                selected.eligible.add(geometry);
            }
        }
        return selected.eligible.isEmpty() ? null : selected;
    }

    private boolean accept(Geometry geometry, SkyLight candidateSky) {
        if (eligible.isEmpty()) {
            sky = candidateSky;
            for (int i = 0; i < textures.length; ++i) {
                textures[i] = (Texture) GBufferPass.parameter(geometry, TEXTURES[i]);
            }
            for (int i = 0; i < values.length; ++i) {
                values[i] = GBufferPass.parameter(geometry, VALUES[i]);
            }
            return true;
        }
        if (sky != candidateSky) {
            return false;
        }
        for (int i = 0; i < textures.length; ++i) {
            if (textures[i] != GBufferPass.parameter(geometry, TEXTURES[i])) {
                return false;
            }
        }
        for (int i = 0; i < values.length; ++i) {
            if (!Objects.equals(values[i], GBufferPass.parameter(geometry, VALUES[i]))) {
                return false;
            }
        }
        return true;
    }

    boolean includes(Geometry geometry) {
        return eligible.contains(geometry);
    }

    void bind(Renderer renderer, Shader shader, Texture receivers, int unit) {
        try {
            renderer.setTexture(unit, receivers);
            uniform(shader, "DeferredGiReceivers", VarType.Int, unit++);
            for (int i = 0; i < textures.length; ++i) {
                renderer.setTexture(unit, textures[i] == null ? textures[0] : textures[i]);
                uniform(shader, TEXTURES[i], VarType.Int, unit++);
            }
        } catch (TextureUnitException exception) {
            throw new RendererException(exception);
        }
        for (int i = 0; i < values.length; ++i) {
            if (values[i] != null) {
                uniform(shader, VALUES[i], TYPES[i], values[i]);
            } else {
                shader.getUniform("m_" + VALUES[i]).clearValue();
            }
        }
        uniform(shader, "GiEnabled", VarType.Boolean, true);
        uniform(shader, "GiBakeBounce", VarType.Boolean, false);
        uniform(shader, "DeferredGiSky", VarType.Vector3Array,
                sky != null && sky.isDiffuseEnabled() ? sky.getShCoeffs() : EnvironmentLighting.ZERO_SH);
        uniform(shader, "DeferredGiSkyIntensity", VarType.Float, sky == null ? 0f : sky.getIntensity());
    }
}
