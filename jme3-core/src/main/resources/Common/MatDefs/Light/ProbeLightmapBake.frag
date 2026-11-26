#import "Common/ShaderLib/GLSLCompat.glsllib"
#define GI_PROBES
#import "Common/ShaderLib/GI/ProbeSampling.glsllib"
#ifdef BAKE_DIRECT
    #import "Common/ShaderLib/GI/BakedDirectionalShadow.glsllib"
    uniform sampler2D m_BakedShadowDepth;
    uniform mat4 m_BakedShadowProjection;
    uniform vec2 m_BakedShadowSize;
    uniform int m_BakedShadowRadius;
    uniform int m_BakedShadowSamples;
    uniform vec4 m_BakedLightColor;

    float directVisibility(vec3 position, vec3 normal) {
        vec3 projected = bakedShadowCoordinates(m_BakedShadowProjection, position, normal);
        vec4 rect = vec4(0.0, 0.0, 1.0, 1.0);
        if (m_BakedShadowRadius == 0) {
            return bakedProjectedVisibility(m_BakedShadowDepth, m_BakedShadowSize, rect, projected);
        }

        // Compare each tap at the receiver plane to avoid widening self-shadow on slopes.
        vec3 dx = dFdx(projected);
        vec3 dy = dFdy(projected);
        float det = dx.x * dy.y - dx.y * dy.x;
        vec2 gradient = vec2(0.0);
        if (abs(det) > 1e-12) {
            gradient = vec2(dx.z * dy.y - dy.z * dx.y, dx.x * dy.z - dy.x * dx.z) / det;
        }
        float visibility = 0.0;
        for (int y = 0; y < m_BakedShadowSamples; y++) {
            for (int x = 0; x < m_BakedShadowSamples; x++) {
                // Stratified cell centers cover the same footprint at every quality level.
                vec2 cell = (vec2(x, y) + 0.5) / float(m_BakedShadowSamples);
                vec2 offset = (2.0 * cell - 1.0) * float(m_BakedShadowRadius) / m_BakedShadowSize;
                float dz = clamp(dot(gradient, offset), -0.02, 0.02);
                visibility += bakedProjectedVisibility(m_BakedShadowDepth, m_BakedShadowSize, rect,
                        projected + vec3(offset, dz));
            }
        }
        return visibility / float(m_BakedShadowSamples * m_BakedShadowSamples);
    }
#endif
varying vec3 worldPosition;
varying vec3 worldNormal;
uniform int m_LightmapBand;
#ifdef NORMALMAP
    uniform sampler2D m_NormalMap;
    uniform float m_NormalType;
    uniform float m_NormalScale;
    varying vec2 materialUv;
    varying vec4 worldTangent;
#endif

vec3 getShadingNormal(vec3 meshNormal) {
    #ifdef NORMALMAP
        // Match the PBR tangent basis and normal-map convention. UV2 only
        // places the output texel; the material texture is sampled with UV0.
        vec3 tangent = worldTangent.xyz - meshNormal * dot(worldTangent.xyz, meshNormal);
        float lengthSquared = dot(tangent, tangent);
        if (lengthSquared > 1e-8) {
            tangent *= inversesqrt(lengthSquared);
            vec3 bitangent = normalize(cross(meshNormal, tangent)) * worldTangent.w;
            vec2 dx = dFdx(materialUv);
            vec2 dy = dFdy(materialUv);
            vec3 local = textureGrad(m_NormalMap, materialUv, dx, dy).xyz * 2.0 - 1.0;
            local.xy *= vec2(1.0, m_NormalType) * m_NormalScale;
            return normalize(mat3(tangent, bitangent, meshNormal) * local);
        }
    #endif
    return meshNormal;
}

void main() {
    vec3 meshNormal = normalize(worldNormal);
    #ifdef BAKE_DIRECT
        float visibility = directVisibility(worldPosition, meshNormal);
        // One unit is reserved for raster occupancy by the common resolve.
        gl_FragColor = vec4(m_BakedLightColor.rgb * visibility / 3.14159265, 2.0);
    #else
        vec3 normal = getShadingNormal(meshNormal);
        giReceiverCurvature = giCurvature(worldPosition, meshNormal);
        vec4 indirect = giSampleIndirectOnPlane(worldPosition, normal, giReceiverPlane(worldPosition, meshNormal));
        // Alpha zero in the irradiance band marks untouched atlas space.
        // Covered geometry can still have zero probe coverage.
        indirect.a += 1.0;
        gl_FragColor = m_LightmapBand == 2 ? vec4(giSkyBentMoment, giSkyDiffuseWeight) : indirect;
    #endif
}
