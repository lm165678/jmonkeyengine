#import "Common/ShaderLib/GLSLCompat.glsllib"
#import "Common/ShaderLib/MultiSample.glsllib"
uniform COLORTEXTURE m_Texture;
varying vec2 texCoord;
uniform vec2 m_Direction;
uniform float m_Sharpness;
void main() {
    vec2 center = getColor(m_Texture, texCoord).rg;
    float sum = 0.0;
    float total = 0.0;
    for (int i = -3; i <= 3; i++) {
        vec2 uv = clamp(texCoord + float(i) * m_Direction, vec2(0.000001), vec2(0.999999));
        vec2 sampleValue = getColor(m_Texture, uv).rg;
        float weight = exp(-float(i * i) / 4.5 - abs(sampleValue.y - center.y) * m_Sharpness);
        sum += sampleValue.x * weight;
        total += weight;
    }
    gl_FragColor = vec4(sum / max(total, 0.00001), center.y, 0.0, 1.0);
}
