#import "Common/ShaderLib/GLSLCompat.glsllib"
#define GI_PROBES
#import "Common/ShaderLib/GI/ProbeSampling.glsllib"
#ifdef BAKE_DIRECT
    #import "Common/ShaderLib/GI/BakedDirectionalShadow.glsllib"
    uniform sampler2D m_BakedShadowDepth;
    uniform mat4 m_BakedShadowProjection;
    uniform vec2 m_BakedShadowSize;
    uniform vec4 m_BakedLightColor;
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
        float visibility = bakedDirectionalVisibility(m_BakedShadowDepth, m_BakedShadowProjection,
                m_BakedShadowSize, vec4(0.0, 0.0, 1.0, 1.0), worldPosition, meshNormal);
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
