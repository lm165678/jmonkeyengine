#import "Common/ShaderLib/GLSLCompat.glsllib"
varying vec4 temporalCurrent;
varying vec4 temporalPrevious;
varying vec2 texCoord;
#ifdef VERTEX_COLOR
    varying float vertexAlpha;
#endif
uniform bool m_TemporalValid;
#ifdef TEMPORAL_GPU_INSTANCES
varying float temporalInstanceValid;
#endif
uniform vec4 m_Color;
uniform float m_AlphaThreshold;
uniform sampler2D m_ColorMap;
void main() {
    float alpha = m_Color.a;
    #ifdef HAS_COLORMAP
        alpha *= texture(m_ColorMap, texCoord).a;
    #endif
    #ifdef VERTEX_COLOR
        alpha *= vertexAlpha;
    #endif
    if (alpha <= m_AlphaThreshold) {
        discard;
    }
    #ifdef TEMPORAL_GPU_INSTANCES
        if (temporalInstanceValid < .5) {
            gl_FragColor = vec4(0.0);
            return;
        }
    #endif
    if (!m_TemporalValid || temporalPrevious.w <= 0.0 || temporalCurrent.w <= 0.0) {
        gl_FragColor = vec4(0.0);
    } else {
        vec3 previous = temporalPrevious.xyz / temporalPrevious.w;
        gl_FragColor = vec4((temporalCurrent.xy / temporalCurrent.w - previous.xy) * .5,
                previous.z * .5 + .5, 1.0);
    }
}
