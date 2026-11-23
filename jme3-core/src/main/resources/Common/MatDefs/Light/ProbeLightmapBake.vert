#import "Common/ShaderLib/GLSLCompat.glsllib"
attribute vec3 inPosition;
attribute vec3 inNormal;
attribute vec2 inTexCoord2;
uniform mat4 g_WorldMatrix;
uniform mat3 g_WorldNormalMatrix;
varying vec3 worldPosition;
varying vec3 worldNormal;
void main() {
    worldPosition = (g_WorldMatrix * vec4(inPosition, 1.0)).xyz;
    worldNormal = g_WorldNormalMatrix * inNormal;
    gl_Position = vec4(inTexCoord2 * 2.0 - 1.0, 0.0, 1.0);
}
