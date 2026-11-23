#define texture2D texture
#import "Common/ShaderLib/Deferred/GBuffer.glsllib"
#import "Common/ShaderLib/GI/ProbeSampling.glsllib"
#import "Common/ShaderLib/GI/DeferredProbe.glsllib"

layout(location = 0) out vec4 outIrradiance;
layout(location = 1) out vec4 outSky;
// Full-resolution representative position and conservative selection radius.
layout(location = 2) out vec4 outGuide;

void main() {
    outIrradiance = vec4(0.0);
    outSky = vec4(0.0, 0.0, 0.0, 1.0);
    outGuide = vec4(0.0, 0.0, 0.0, -1.0);
    vec4 receiver = texelFetch(m_DeferredGiReceivers, getGBufferPixel(), 0);
    if (receiver.a == 0.0 || getShadingId() == SHADING_MODEL_BACKGROUND) {
        return;
    }
    deferredGiQuery(receiver, outIrradiance, outSky);
    vec3 position = getWorldPosition();
    outGuide = vec4(position, deferredGiBoundaryDistance(position));
}
