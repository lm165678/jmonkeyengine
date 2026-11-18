#import "Common/ShaderLib/GLSLCompat.glsllib"
#import "Common/ShaderLib/MultiSample.glsllib"
uniform COLORTEXTURE m_Texture;
varying vec2 texCoord;
uniform sampler2D m_Bloom;
uniform vec2 m_BloomSize;
uniform float m_Intensity;
void main() {
    vec2 pixel = texCoord * m_BloomSize - 0.5;
    vec2 a = fract(pixel);
    vec2 b = floor(pixel);
    vec3 bloom = vec3(0.0);
    for (int i = 0; i < 4; i++) {
        vec2 o = vec2(mod(float(i), 2.0), floor(float(i) / 2.0));
        vec2 w = mix(1.0 - a, a, o);
        vec2 uv = clamp((b + o + 0.5) / m_BloomSize, 0.5 / m_BloomSize, 1.0 - 0.5 / m_BloomSize);
        bloom += w.x * w.y * texture2D(m_Bloom, uv).rgb;
    }
    vec4 scene = getColor(m_Texture, texCoord);
    gl_FragColor = vec4(scene.rgb + m_Intensity * bloom, scene.a);
}
