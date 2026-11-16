#import "Common/ShaderLib/GLSLCompat.glsllib"
#define SHADOWMAP_SIZE 16.0
#define PCFEDGE 1.0
#import "Common/ShaderLib/Shadows.glsllib"
void main() {
    float visibility = GETSHADOW(m_ShadowMap0, vec4(gl_FragCoord.x / 256.0, 0.5, 0.5, 1.0));
    gl_FragColor = vec4(vec3(visibility), 1.0);
}
