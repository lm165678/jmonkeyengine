#import "Common/ShaderLib/GLSLCompat.glsllib"
attribute vec3 inPosition;
attribute vec3 inNormal;
attribute vec2 inTexCoord2;
uniform mat4 g_WorldMatrix;
uniform mat3 g_WorldNormalMatrix;
uniform int m_LightmapBand;
varying vec3 worldPosition;
varying vec3 worldNormal;
void main() {
    worldPosition = (g_WorldMatrix * vec4(inPosition, 1.0)).xyz;
    worldNormal = g_WorldNormalMatrix * inNormal;
    vec2 position = inTexCoord2 * 2.0 - 1.0;
    if (m_LightmapBand != 0) {
        position.y = inTexCoord2.y - (m_LightmapBand == 1 ? 1.0 : 0.0);
    }
    gl_Position = vec4(position, 0.0, 1.0);
}
