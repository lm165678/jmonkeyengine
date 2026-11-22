#import "Common/ShaderLib/GLSLCompat.glsllib"
#define A_GPU 1
#define A_GLSL 1
#import "Common/ShaderLib/FidelityFX/FSR1/ffx_a.glsllib"
#define FSR_RCAS_F 1
#import "Common/ShaderLib/FidelityFX/FSR1/ffx_fsr1.glsllib"
uniform sampler2D m_Texture;
uniform float m_Sharpness;
varying vec2 texCoord;
AF4 FsrRcasLoadF(ASU2 position) {
    return texelFetch(m_Texture, clamp(position, ivec2(0), textureSize(m_Texture, 0) - 1), 0);
}
void FsrRcasInputF(inout AF1 r, inout AF1 g, inout AF1 b) {
}
void main() {
    AF3 color;
    AU4 config = AU4(floatBitsToUint(exp2(-(2.0 - 2.0 * m_Sharpness))), 0, 0, 0);
    FsrRcasF(color.r, color.g, color.b, AU2(gl_FragCoord.xy), config);
    // Convert back to display-linear for UI blending and final sRGB encoding.
    gl_FragColor = vec4(color * color, texture(m_Texture, texCoord).a);
}
