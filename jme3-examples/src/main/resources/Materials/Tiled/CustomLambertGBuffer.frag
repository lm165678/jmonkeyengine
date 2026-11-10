// The material's GBuffer writer and lighting decoder must agree on these records.
// This deliberately uses a layout different from Lighting/PBRLighting to demonstrate
// that gbuffer1 is not globally a "Normal" texture and gbuffer3 is not "Ambient".
#import "Common/ShaderLib/DeferredSurface.glsllib"
in vec3 normal;
uniform vec4 m_Diffuse;
uniform vec4 m_Ambient;

void main() {
    vec4 record0 = vec4(m_Diffuse.rgb, 0.0);
    vec4 record1 = vec4(m_Ambient.rgb, 0.0);
    vec4 record2 = vec4(0.0);
    vec4 record3 = vec4(normalize(normal), 0.0);
    vec4 record4 = vec4(0.0, 0.0, 0.0, m_Diffuse.a);
    writeGBuffer(record0, record1, record2, record3, record4, m_DeferredShadingId);
}
