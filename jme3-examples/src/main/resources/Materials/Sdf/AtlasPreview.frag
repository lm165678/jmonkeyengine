#import "Common/ShaderLib/GLSLCompat.glsllib"
#import "Common/ShaderLib/SurfaceAtlasLookup.glsllib"
uniform sampler2D m_AttributeAtlas;
uniform sampler2D m_DepthAtlas;
uniform vec4 m_AtlasRect;
uniform mat4 m_WorldToCard;
uniform mat4 m_CardToWorld;
uniform float m_CardResolution;
uniform int m_Mode;
uniform bool m_Missing;
varying vec2 texCoord1;

void main() {
    if (m_Missing) {
        float checker = mod(floor(texCoord1.x * 12.0) + floor(texCoord1.y * 12.0), 2.0);
        gl_FragColor = vec4(vec3(0.08 + checker * 0.15), 1.0);
        return;
    }
    vec2 uv = texCoord1 * m_AtlasRect.zw + m_AtlasRect.xy;
    if (m_Mode == 4) {
        float depth = texture(m_DepthAtlas, uv).r;
        gl_FragColor = vec4(vec3(depth < 1.0 ? depth : 0.02), 1.0);
        return;
    }
    if (m_Mode == 2 || m_Mode == 3) {
        vec2 cardUv = (floor(texCoord1 * m_CardResolution) + 0.5) / m_CardResolution;
        uv = cardUv * m_AtlasRect.zw + m_AtlasRect.xy;
        float depth = texture(m_DepthAtlas, uv).r;
        if (depth >= 1.0) {
            gl_FragColor = vec4(0.02, 0.02, 0.02, 1.0);
            return;
        }
        float queryDepth = depth + (m_Mode == 3 ? 0.1 : 0.0);
        vec4 world = m_CardToWorld * vec4(cardUv * 2.0 - 1.0, queryDepth * 2.0 - 1.0, 1.0);
        vec2 checkedUv;
        bool covered = projectSurfaceAtlasCard(world.xyz / world.w, m_WorldToCard,
                m_DepthAtlas, m_AtlasRect, 0.005, checkedUv);
        gl_FragColor = covered ? vec4(0.0, 1.0, 0.0, 1.0) : vec4(1.0, 0.0, 0.0, 1.0);
        return;
    }
    vec4 value = texture(m_AttributeAtlas, uv);
    vec3 color = m_Mode == 1 ? value.rgb * 0.5 + 0.5 : value.rgb;
    gl_FragColor = vec4(value.a > 0.0 ? color : vec3(0.02), 1.0);
}
