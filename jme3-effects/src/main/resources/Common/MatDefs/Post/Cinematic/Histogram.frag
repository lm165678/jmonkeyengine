#import "Common/ShaderLib/GLSLCompat.glsllib"
#import "Common/ShaderLib/MultiSample.glsllib"
uniform COLORTEXTURE m_Texture;
varying vec2 texCoord;
void main() {
    float bin = floor(texCoord.x * 64.0);
    float count = 0.0;
    float sum = 0.0;
    // Portable fixed-budget GPU histogram: no atomics and no CPU readback.
    for (int y = 0; y < 32; y++) {
        for (int x = 0; x < 32; x++) {
            float logL = getColor(m_Texture, (vec2(float(x), float(y)) + 0.5) / 32.0).r;
            float index = clamp(floor((logL + 12.0) * (64.0 / 28.0)), 0.0, 63.0);
            float weight = abs(index - bin) < 0.5 ? 1.0 : 0.0;
            count += weight;
            sum += logL * weight;
        }
    }
    gl_FragColor = vec4(count / 1024.0, sum / 1024.0, 0.0, 1.0);
}
