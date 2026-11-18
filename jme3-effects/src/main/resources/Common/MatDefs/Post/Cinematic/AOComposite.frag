#import "Common/ShaderLib/GLSLCompat.glsllib"
#import "Common/ShaderLib/MultiSample.glsllib"
uniform COLORTEXTURE m_Texture;
varying vec2 texCoord;
uniform DEPTHTEXTURE m_DepthTexture;
#import "Common/MatDefs/Post/Cinematic/DepthView.glsllib"
uniform sampler2D m_Occlusion;
uniform vec2 m_AOSize;
uniform float m_Sharpness;
uniform float m_Intensity;
uniform bool m_ShowOnly;
void main() {
    vec4 scene = getColor(m_Texture, texCoord);
    float depth = min(abs(viewPosition(texCoord).z), 65504.0);
    vec2 pixel = texCoord * m_AOSize - 0.5;
    vec2 a = fract(pixel);
    vec2 b = floor(pixel);
    float sum = 0.0;
    float total = 0.0;
    for (int i = 0; i < 4; i++) {
        vec2 o = vec2(mod(float(i), 2.0), floor(float(i) / 2.0));
        vec2 w = mix(1.0 - a, a, o);
        vec2 uv = clamp((b + o + 0.5) / m_AOSize, 0.5 / m_AOSize, 1.0 - 0.5 / m_AOSize);
        vec2 sampleValue = texture2D(m_Occlusion, uv).rg;
        float weight = w.x * w.y * exp(-abs(sampleValue.y - depth) * m_Sharpness);
        sum += weight * sampleValue.x;
        total += weight;
    }
    float ao = total > 0.00001 ? sum / total : 1.0;
    if (sceneDepth(texCoord) >= 0.999999) {
        ao = 1.0;
    }
    ao = m_Intensity == 0.0 ? 1.0 : pow(clamp(ao, 0.0, 1.0), m_Intensity);
    gl_FragColor = vec4(m_ShowOnly ? vec3(ao) : scene.rgb * ao, scene.a);
}
