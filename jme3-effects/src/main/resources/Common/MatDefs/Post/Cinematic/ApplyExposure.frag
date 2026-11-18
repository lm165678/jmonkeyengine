#import "Common/ShaderLib/GLSLCompat.glsllib"
#import "Common/ShaderLib/MultiSample.glsllib"
uniform COLORTEXTURE m_Texture;
varying vec2 texCoord;
uniform sampler2D m_Exposure;
uniform float m_Compensation;
uniform bool m_Automatic;
void main() {
    vec4 color = getColor(m_Texture, texCoord);
    float ev = m_Compensation;
    if (m_Automatic) {
        ev += texture2D(m_Exposure, vec2(0.5)).r;
    }
    // Bound the exponent only; scene HDR values are not clamped here.
    gl_FragColor = vec4(color.rgb * exp2(clamp(ev, -24.0, 24.0)), color.a);
}
