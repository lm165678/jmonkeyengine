#import "Common/ShaderLib/GLSLCompat.glsllib"
#define g_ShadowAtlas m_Atlas
#define g_ShadowAtlasSize m_AtlasSize
#import "Common/ShaderLib/ManagedShadows.glsllib"
uniform vec4 m_Rect;
uniform float m_Radius;
void main() {
    vec2 uv = vec2(gl_FragCoord.x / 256.0, 0.5);
    float visibility = managedShadowPcf(uv, m_Rect, 0.5, m_Radius);
    gl_FragColor = vec4(vec3(visibility), 1.0);
}
