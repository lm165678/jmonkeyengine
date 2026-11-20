#import "Common/ShaderLib/GLSLCompat.glsllib"
#import "Common/MatDefs/Post/Cinematic/TemporalSampling.glsllib"
varying vec2 texCoord;
void main() {
    ivec2 p = temporalClosestPixel(temporalScenePixel(texCoord));
    gl_FragColor = vec4(texelFetch(m_MotionDepth, p, 0).rrr, 1.0);
}
