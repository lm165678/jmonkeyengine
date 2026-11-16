#import "Common/ShaderLib/GLSLCompat.glsllib"
#define g_ShadowAtlas m_Atlas
#define g_ShadowAtlasSize m_AtlasSize
#import "Common/ShaderLib/ManagedShadows.glsllib"
uniform vec4 m_Rect;
uniform float m_Radius;
#ifdef PLANE
uniform vec2 m_PlaneSlope;
#endif
void main() {
    vec2 uv = vec2(gl_FragCoord.x / 256.0, 0.5);
    float visibility = ShadowFilter_Tent(g_ShadowAtlas, uv, g_ShadowAtlasSize, m_Rect, 0.5, m_Radius);
#ifdef PLANE
    vec3 normal = normalize(vec3(-m_PlaneSlope, 1.0));
    vec2 gradient = managedShadowDepthGradient(mat4(1.0), vec4(0.0, 0.0, 0.0, 1.0), normal, m_Rect);
    float reference = 0.45 + dot(m_PlaneSlope, uv) - 0.00001;
    visibility = ShadowFilter_TentPlane(g_ShadowAtlas, uv, g_ShadowAtlasSize,
            m_Rect, reference, m_Radius, gradient);
    float uncorrected = ShadowFilter_Tent(g_ShadowAtlas, uv, g_ShadowAtlasSize,
            m_Rect, reference, m_Radius);
    gl_FragColor = vec4(visibility, uncorrected, 0.0, 1.0);
#else
    gl_FragColor = vec4(vec3(visibility), 1.0);
#endif
}
