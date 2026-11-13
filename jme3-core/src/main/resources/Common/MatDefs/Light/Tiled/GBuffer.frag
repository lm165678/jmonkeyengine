// Legacy built-in-layout writer; model-specific writers may choose different channels.
in vec3 normal;
in vec2 uv;
uniform int m_DeferredShadingId;
uniform vec4 m_Diffuse, m_Specular, m_Ambient, m_BaseColor;
uniform float m_Shininess, m_Roughness, m_Metallic, m_AlphaDiscardThreshold;
uniform sampler2D m_DiffuseMap, m_BaseColorMap;
layout(location = 0) out vec4 outGBuffer0;
layout(location = 1) out vec4 outGBuffer1;
layout(location = 2) out vec4 outGBuffer2;
layout(location = 3) out vec4 outGBuffer3;
void main() {
    vec4 color = vec4(1.0);
    vec3 spec = vec3(0.0), ambient = vec3(1.0);
    float parameter = m_Shininess;
#ifdef PBR
    color = m_BaseColor;
#ifdef BASECOLORMAP
    color *= texture(m_BaseColorMap, uv);
#endif
    parameter = max(m_Roughness, 1e-4);
    spec = vec3(max(m_Metallic, 0.0));
    ambient = vec3(0.0);
#else
#ifdef MATERIAL_COLORS
    color = m_Diffuse;
    spec = m_Specular.rgb;
    ambient = m_Ambient.rgb;
#endif
#ifdef DIFFUSEMAP
    vec4 texel = texture(m_DiffuseMap, uv);
    color *= texel;
    ambient *= texel.rgb;
#endif
#endif
#ifdef DISCARD_ALPHA
    if (color.a < m_AlphaDiscardThreshold) {
        discard;
    }
#endif
    outGBuffer0 = vec4(color.rgb, float(m_DeferredShadingId));
    outGBuffer1 = vec4(normalize(normal), parameter);
    outGBuffer2 = vec4(spec, 0.0);
    outGBuffer3 = vec4(ambient, 1.0);
}
