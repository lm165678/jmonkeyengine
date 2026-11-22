#define textureCubeLod textureLod
#define textureCube texture
#define texture2D texture
#define mat3_sub mat3

#import "Common/ShaderLib/Deferred/GBuffer.glsllib"
#import "Common/ShaderLib/Deferred/LightList.glsllib"
#import "Common/ShaderLib/GI/ProbeSampling.glsllib"
#import "Common/ShaderLib/Deferred/LightingModel.glsllib"
#import "Common/ShaderLib/Deferred/PbrModel.glsllib"
#import "Common/ShaderLib/Deferred/UnshadedModel.glsllib"
#import "Common/ShaderLib/IrradianceSH.glsllib"

#ifdef DEFERRED_GI_LIGHT
uniform sampler2D m_DeferredGiReceivers;
uniform vec3 m_DeferredGiSky[9];
uniform float m_DeferredGiSkyIntensity;

vec3 evaluateDeferredProbeGi() {
    vec4 receiver = texelFetch(m_DeferredGiReceivers, getGBufferPixel(), 0);
    if (receiver.a == 0.0) {
        return vec3(0.0);
    }
    float packedNormal = abs(receiver.a) - 1.0;
    vec2 octPlane = vec2(mod(packedNormal, 1024.0), floor(packedNormal / 1024.0));
    vec3 plane = giOctDecode(octPlane * (2.0 / 1023.0) - 1.0);
    vec3 normal = normalize(getGBuffer1().xyz);
    giReceiverCurvature = getGBuffer2().a;
    vec3 irradiance = giSampleIndirectOnPlane(getWorldPosition(), normal, plane).rgb;
    if (receiver.a < 0.0) {
        irradiance += shNativeIrradiance(m_DeferredGiSky, giSkyDirection(normal))
                * (giSkyDiffuseWeight * m_DeferredGiSkyIntensity);
    }
    return shLambertResponse(irradiance, receiver.rgb, 1.0);
}
#endif

layout(location = 0) out vec4 outFragColor;
layout(location = 1) out vec4 outVelocity;
uniform sampler2D m_TemporalVelocity;
uniform bool m_TemporalOutput;

// Material-owned functions are inserted after the common access/light APIs.
// CUSTOM_MODEL_FUNCTIONS

// One model owns decoding its surface, evaluating lights and returning opacity.
// Only the shadingId header and depth attachment have shared meanings.
vec4 subfaceShading(int shadingId) {
    if (shadingId == SHADING_MODEL_LIGHTING) {
        return shadeLightingSurface();
    }
    if (shadingId == SHADING_MODEL_PBR) {
        return shadePbrSurface();
    }
    if (shadingId == SHADING_MODEL_UNSHADED) {
        return shadeUnshadedSurface();
    }
    // DeferredResources.assembleLightingSource replaces the marker below before
    // shader compilation, e.g. if (shadingId == 65535) {
    //     return deferredSurfaceModel65535();
    // }
    // ShadingModelRegistry renames each material's deferredSurfaceShading entry
    // to deferredSurfaceModel<ID>; the ID is also written during its GBuffer pass.
    // CUSTOM_MODEL_DISPATCH
    return vec4(0.0);
}

void main() {
    int shadingId = getShadingId();
    if (shadingId == SHADING_MODEL_BACKGROUND) {
        discard;
    }
    outFragColor = subfaceShading(shadingId);
    #ifdef DEFERRED_GI_LIGHT
        outFragColor.rgb += evaluateDeferredProbeGi();
    #endif
    gl_FragDepth = getSurfaceDepth();
    outVelocity = m_TemporalOutput ? texelFetch(m_TemporalVelocity, ivec2(gl_FragCoord.xy), 0) : vec4(0.0);
}
