uniform sampler2D m_Previous;
out vec4 color;
void main() {
    ivec2 origin = ivec2(gl_FragCoord.xy) * 2;
    ivec2 maximum = textureSize(m_Previous, 0) - 1;
    float depth = 0.0;
    for (int y = 0; y < 2; y++) {
        for (int x = 0; x < 2; x++) {
            depth = max(depth, texelFetch(m_Previous, min(origin + ivec2(x, y), maximum), 0).r);
        }
    }
    color = vec4(depth);
}
