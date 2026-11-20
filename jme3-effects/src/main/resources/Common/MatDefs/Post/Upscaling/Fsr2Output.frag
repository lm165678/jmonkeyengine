#import "Common/ShaderLib/GLSLCompat.glsllib"
uniform sampler2D m_Texture;
uniform sampler2D m_SourceAlpha;
varying vec2 texCoord;
void main() {
    gl_FragColor = vec4(texture2D(m_Texture, texCoord).rgb, texture2D(m_SourceAlpha, texCoord).a);
}
