#define texture2D texture
#import "Common/ShaderLib/Deferred/GBuffer.glsllib"
#import "Common/ShaderLib/GI/ProbeSampling.glsllib"
#import "Common/ShaderLib/GI/DeferredProbe.glsllib"

uniform sampler2D m_GiReconstructionMask;
layout(location = 0) out vec4 outIrradiance;
layout(location = 1) out vec4 outSky;

void main() {
    if (texelFetch(m_GiReconstructionMask, getGBufferPixel(), 0).r < 0.5) {
        discard;
    }
    vec4 receiver = texelFetch(m_DeferredGiReceivers, getGBufferPixel(), 0);
    deferredGiQuery(receiver, outIrradiance, outSky);
}
