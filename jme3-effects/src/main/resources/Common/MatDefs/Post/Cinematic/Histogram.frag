#import "Common/ShaderLib/GLSLCompat.glsllib"
#import "Common/ShaderLib/MultiSample.glsllib"
uniform COLORTEXTURE m_Texture;
varying vec2 texCoord;
uniform vec2 m_LogRange;
uniform bool m_UE4Mode;
void main() {
    float bin = floor(texCoord.x * 64.0);
    float count = 0.0;
    float sum = 0.0;
    // Portable fixed-budget GPU histogram: no atomics and no CPU readback.
    for (int y = 0; y < 32; y++) {
        for (int x = 0; x < 32; x++) {
            float logL = getColor(m_Texture, (vec2(float(x), float(y)) + 0.5) / 32.0).r;
            float position = clamp((logL - m_LogRange.x) / (m_LogRange.y - m_LogRange.x), 0.0, 1.0);
            float index = min(floor(position * 64.0), 63.0);
            float weight = abs(index - bin) < 0.5 ? 1.0 : 0.0;
            if (m_UE4Mode) {
                // Linear bin weights avoid discrete exposure jumps.
                weight = max(0.0, 1.0 - abs(position * 63.0 - bin));
                index = bin;
            }
            count += weight;
            float measured = m_UE4Mode ? mix(m_LogRange.x, m_LogRange.y, index / 63.0) : logL;
            sum += measured * weight;
        }
    }
    gl_FragColor = vec4(count / 1024.0, sum / 1024.0, 0.0, 1.0);
}
