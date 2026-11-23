#import "Common/ShaderLib/GLSLCompat.glsllib"
#define GI_PROBES
#import "Common/ShaderLib/GI/ProbeSampling.glsllib"
varying vec3 worldPosition;
varying vec3 worldNormal;
uniform int m_LightmapBand;
void main() {
    vec3 normal = normalize(worldNormal);
    giReceiverCurvature = giCurvature(worldPosition, normal);
    vec4 indirect = giSampleIndirect(worldPosition, normal);
    // Alpha zero in the irradiance band marks untouched atlas space.
    // Covered geometry can still have zero probe coverage.
    indirect.a += 1.0;
    gl_FragColor = m_LightmapBand == 2 ? vec4(giSkyBentMoment, giSkyDiffuseWeight) : indirect;
}
