#import "Common/ShaderLib/GLSLCompat.glsllib"
#import "Common/ShaderLib/MultiSample.glsllib"
uniform COLORTEXTURE m_Texture;
varying vec2 texCoord;
#import "Common/ShaderLib/Hdr.glsllib"
uniform int m_Curve;
uniform float m_Saturation;
uniform float m_Contrast;
uniform float m_WhitePoint;
uniform vec3 m_Gain;
vec3 filmic(vec3 x) {
    return ((x * (0.22 * x + 0.03) + 0.002) / (x * (0.22 * x + 0.3) + 0.06)) - 0.01 / 0.3;
}
void main() {
    vec4 source = getColor(m_Texture, texCoord);
    vec3 c = max(source.rgb * m_Gain, vec3(0.0));
    c = 0.18 * pow(c / 0.18, vec3(m_Contrast));
    float luminance = dot(c, vec3(0.2126, 0.7152, 0.0722));
    c = max(mix(vec3(luminance), c, m_Saturation), vec3(0.0));
    if (m_Curve == 0) {
        c = HDR_KHRToneMap(c, vec3(0.0), vec3(1.0));
    } else {
        c = filmic(c) / filmic(vec3(m_WhitePoint));
    }
    // Linear SDR output: framebuffer performs the one display encoding.
    gl_FragColor = vec4(clamp(c, 0.0, 1.0), source.a);
}
