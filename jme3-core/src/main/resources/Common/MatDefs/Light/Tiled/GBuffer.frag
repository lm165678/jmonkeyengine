in vec3 normal;
in vec2 uv;
uniform int m_DeferredShadingId;
uniform vec4 m_Diffuse, m_Specular, m_Ambient, m_BaseColor;
uniform float m_Shininess, m_Roughness, m_Metallic, m_AlphaDiscardThreshold;
uniform sampler2D m_DiffuseMap, m_BaseColorMap;
layout(location=0) out vec4 gAlbedo;
layout(location=1) out vec4 gNormal;
layout(location=2) out vec4 gSpecular;
layout(location=3) out vec4 gAmbient;
void main(){
 vec4 color=vec4(1.0); vec3 spec=vec3(0.0), ambient=vec3(1.0);
 float parameter=m_Shininess;
 #ifdef PBR
 color=m_BaseColor;
 #ifdef BASECOLORMAP
 color *= texture(m_BaseColorMap,uv);
 #endif
 parameter=max(m_Roughness,1e-4);
 spec=vec3(max(m_Metallic,0.0)); ambient=vec3(0.0);
 #else
 #ifdef MATERIAL_COLORS
 color=m_Diffuse; spec=m_Specular.rgb; ambient=m_Ambient.rgb;
 #endif
 #ifdef DIFFUSEMAP
 vec4 texel=texture(m_DiffuseMap,uv); color*=texel; ambient*=texel.rgb;
 #endif
 #endif
 #ifdef DISCARD_ALPHA
 if(color.a<m_AlphaDiscardThreshold) discard;
 #endif
 gAlbedo=vec4(color.rgb,float(m_DeferredShadingId));
 gNormal=vec4(normalize(normal),parameter);
 gSpecular=vec4(spec,0.0);
 gAmbient=vec4(ambient,1.0);
}
