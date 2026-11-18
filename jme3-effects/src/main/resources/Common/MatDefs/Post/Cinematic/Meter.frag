#import "Common/ShaderLib/GLSLCompat.glsllib"
#import "Common/ShaderLib/MultiSample.glsllib"
uniform COLORTEXTURE m_Texture;
varying vec2 texCoord;
void main() {
    // Four stratified samples per metering cell. Store log luminance, not RGB.
    float value = 0.0;
    for (int y = 0; y < 2; y++) {
        for (int x = 0; x < 2; x++) {
            vec2 uv = texCoord + (vec2(float(x), float(y)) - 0.5) / 64.0;
            vec3 color = max(getColor(m_Texture, uv).rgb, vec3(0.0));
            value += clamp(log2(max(dot(color, vec3(0.2126, 0.7152, 0.0722)), 0.000244140625)), -12.0, 16.0);
        }
    }
    gl_FragColor = vec4(value * 0.25, 0.0, 0.0, 1.0);
}
