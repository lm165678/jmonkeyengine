#import "Common/ShaderLib/GLSLCompat.glsllib"
#import "Common/ShaderLib/Instancing.glsllib"
#define TEMPORAL_VELOCITY
#import "Common/ShaderLib/TemporalVertex.glsllib"
attribute vec3 inPosition;
attribute vec2 inTexCoord;
varying vec2 texCoord;
#ifdef VERTEX_COLOR
    attribute vec4 inColor;
    varying float vertexAlpha;
#endif
void main() {
    vec4 position = vec4(inPosition, 1.0);
    gl_Position = TransformWorldViewProjection(position);
    temporalPosition(position);
    texCoord = inTexCoord;
    #ifdef VERTEX_COLOR
        vertexAlpha = inColor.a;
    #endif
}
