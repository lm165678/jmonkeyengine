#import "Common/ShaderLib/GLSLCompat.glsllib"
#import "Common/ShaderLib/MultiSample.glsllib"
uniform COLORTEXTURE m_Texture;
varying vec2 texCoord;
uniform vec2 m_TexelSize;
uniform float m_Threshold;
uniform float m_Knee;
uniform bool m_Extract;
vec3 source(vec2 uv) {
    vec3 c = max(getColor(m_Texture, clamp(uv, m_TexelSize * 0.5, 1.0 - m_TexelSize * 0.5)).rgb, vec3(0.0));
    if (m_Extract && m_Threshold > 0.0) {
        float brightness = max(c.r, max(c.g, c.b));
        float knee = max(m_Threshold * m_Knee, 0.00001);
        float soft = clamp(brightness - m_Threshold + knee, 0.0, 2.0 * knee);
        soft = soft * soft / (4.0 * knee);
        c *= max(brightness - m_Threshold, soft) / max(brightness, 0.00001);
    }
    return c;
}
void main() {
    vec3 sum = vec3(0.0);
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            float weight = (x == 0 ? 2.0 : 1.0) * (y == 0 ? 2.0 : 1.0);
            sum += weight * source(texCoord + vec2(float(x), float(y)) * m_TexelSize);
        }
    }
    gl_FragColor = vec4(sum / 16.0, 1.0);
}
