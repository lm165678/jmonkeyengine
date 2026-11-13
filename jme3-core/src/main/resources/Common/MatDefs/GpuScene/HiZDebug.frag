uniform sampler2D m_Depth;
uniform float m_Near;
uniform float m_Far;
uniform float m_DisplayRange;
in vec2 texCoord;
out vec4 color;
void main() {
    float depth = texture(m_Depth, texCoord).r;
    if (depth >= 1.0) {
        color = vec4(0.03, 0.03, 0.03, 1.0);
        return;
    }
    // Perspective standard-Z depth, displayed logarithmically for readable contrast.
    float distance = (2.0 * m_Near * m_Far)
            / (m_Far + m_Near - (2.0 * depth - 1.0) * (m_Far - m_Near));
    float value = clamp(log2(1.0 + distance) / log2(1.0 + m_DisplayRange), 0.0, 1.0);
    color = vec4(mix(vec3(0.1, 0.3, 1.0), vec3(1.0, 0.9, 0.1), value), 1.0);
}
