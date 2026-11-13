in vec3 inPosition;
in vec2 inTexCoord;
uniform mat4 g_WorldViewProjectionMatrix;
out vec2 texCoord;
void main() {
    texCoord = inTexCoord;
    gl_Position = g_WorldViewProjectionMatrix * vec4(inPosition, 1.0);
}
