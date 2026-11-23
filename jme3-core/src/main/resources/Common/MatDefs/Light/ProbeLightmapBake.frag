#import "Common/ShaderLib/GLSLCompat.glsllib"
#define GI_PROBES
#import "Common/ShaderLib/GI/ProbeSampling.glsllib"
varying vec3 worldPosition;
varying vec3 worldNormal;
void main() {
    vec3 normal = normalize(worldNormal);
    giReceiverCurvature = giCurvature(worldPosition, normal);
    gl_FragColor = giSampleIndirect(worldPosition, normal);
}
