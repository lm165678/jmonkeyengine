#import "Common/ShaderLib/GLSLCompat.glsllib"
#define A_GPU 1
#define A_GLSL 1
#import "Common/ShaderLib/FidelityFX/FSR1/ffx_a.glsllib"
#define FSR_EASU_F 1
#import "Common/ShaderLib/FidelityFX/FSR1/ffx_fsr1.glsllib"
uniform sampler2D m_Texture;
uniform vec4 m_Con0;
uniform vec4 m_Con1;
uniform vec4 m_Con2;
uniform vec4 m_Con3;
varying vec2 texCoord;
// EASU expects display-like gamma values; the surrounding pipeline stays linear.
AF4 FsrEasuRF(AF2 uv) {
    return sqrt(max(textureGather(m_Texture, uv, 0), AF4(0.0)));
}
AF4 FsrEasuGF(AF2 uv) {
    return sqrt(max(textureGather(m_Texture, uv, 1), AF4(0.0)));
}
AF4 FsrEasuBF(AF2 uv) {
    return sqrt(max(textureGather(m_Texture, uv, 2), AF4(0.0)));
}
void main() {
    AF3 color;
    FsrEasuF(color, AU2(gl_FragCoord.xy), floatBitsToUint(m_Con0), floatBitsToUint(m_Con1),
            floatBitsToUint(m_Con2), floatBitsToUint(m_Con3));
    gl_FragColor = vec4(color, texture(m_Texture, texCoord).a);
}
