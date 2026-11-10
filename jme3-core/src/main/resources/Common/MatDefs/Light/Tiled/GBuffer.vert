uniform mat4 g_WorldViewProjectionMatrix;
uniform mat4 g_WorldMatrix;
uniform mat3 g_NormalMatrix;
uniform mat4 g_ViewMatrix;
in vec3 inPosition;
in vec3 inNormal;
in vec2 inTexCoord;
out vec3 normal;
out vec2 uv;
void main() {
    gl_Position = g_WorldViewProjectionMatrix * vec4(inPosition, 1.0);
#ifdef PBR
    normal = normalize(mat3(g_WorldMatrix) * inNormal);
#else
    normal = normalize(transpose(mat3(g_ViewMatrix)) * g_NormalMatrix * inNormal);
#endif
    uv = inTexCoord;
}
