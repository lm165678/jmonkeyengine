#import "Common/ShaderLib/GLSLCompat.glsllib"
#import "Common/ShaderLib/MultiSample.glsllib"
uniform COLORTEXTURE m_Texture;
uniform vec4 m_Color;
varying vec2 texCoord;

void main() {
    gl_FragColor = getColor(m_Texture, texCoord) * m_Color;
}
