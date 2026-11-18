#import "Common/ShaderLib/GLSLCompat.glsllib"
#import "Common/ShaderLib/MultiSample.glsllib"
uniform COLORTEXTURE m_Texture;
varying vec2 texCoord;
uniform sampler2D m_Detail;
uniform vec2 m_TexelSize;
uniform float m_Scatter;
vec3 source(vec2 uv) {
    vec2 pixel = uv / m_TexelSize - 0.5;
    vec2 a = fract(pixel);
    vec2 b = floor(pixel);
    vec3 c = vec3(0.0);
    for (int i = 0; i < 4; i++) {
        vec2 o = vec2(mod(float(i), 2.0), floor(float(i) / 2.0));
        vec2 w = mix(1.0 - a, a, o);
        vec2 q = clamp((b + o + 0.5) * m_TexelSize, m_TexelSize * 0.5, 1.0 - m_TexelSize * 0.5);
        c += w.x * w.y * getColor(m_Texture, q).rgb;
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
    // Normalized reconstruction preserves DC energy regardless of level count.
    gl_FragColor = vec4(mix(texture2D(m_Detail, texCoord).rgb, sum / 16.0, m_Scatter), 1.0);
}
