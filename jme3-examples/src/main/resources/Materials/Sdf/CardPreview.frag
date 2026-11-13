#import "Common/ShaderLib/GLSLCompat.glsllib"
#import "Common/ShaderLib/SurfaceCacheLookup.glsllib"
uniform sampler2D m_Attribute;
uniform sampler2D m_Depth;
uniform mat4 m_LocalToClip;
uniform mat4 m_ClipToLocal;
uniform vec3 m_TestPosition;
uniform int m_Mode;
varying vec2 texCoord1;
void main() {
    if (m_Mode == 3 || m_Mode == 4) {
        vec2 dimensions = vec2(textureSize(m_Depth, 0));
        vec2 sampleUv = (floor(texCoord1 * dimensions) + 0.5) / dimensions;
        float depth = texture(m_Depth, sampleUv).r;
        if (depth >= 1.0) {
            gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
            return;
        }
        float queryDepth = depth + (m_Mode == 4 ? 0.1 : 0.0);
        vec4 local = m_ClipToLocal * vec4(sampleUv * 2.0 - 1.0, queryDepth * 2.0 - 1.0, 1.0);
        vec2 uv;
        bool covered = projectSurfaceCard(local.xyz / local.w, m_LocalToClip, m_Depth, 0.005, uv);
        gl_FragColor = covered ? vec4(0.0, 1.0, 0.0, 1.0) : vec4(1.0, 0.0, 0.0, 1.0);
        return;
    }
    if (m_Mode == 2) {
        vec2 uv;
        bool covered = projectSurfaceCard(m_TestPosition, m_LocalToClip, m_Depth, 0.005, uv);
        gl_FragColor = covered ? vec4(0.0, 1.0, 0.0, 1.0) : vec4(1.0, 0.0, 0.0, 1.0);
        return;
    }
    vec4 value = texture(m_Attribute, texCoord1);
    vec3 color = value.rgb;
    if (m_Mode == 1) {
        color = color * 0.5 + 0.5;
    }
    gl_FragColor = vec4(value.a > 0.0 ? color : vec3(0.02), 1.0);
}
