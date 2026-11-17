#import "Common/ShaderLib/GLSLCompat.glsllib"
#import "Common/ShaderLib/MultiSample.glsllib"
uniform COLORTEXTURE m_Texture;
uniform float m_ExposureMultiplier;
varying vec2 texCoord;
void main() {
    vec4 color = getColor(m_Texture, texCoord);
    // Scene-linear RGB only; preserve HDR values and alpha.
    gl_FragColor = vec4(color.rgb * m_ExposureMultiplier, color.a);
}
