#import "Common/ShaderLib/GLSLCompat.glsllib"
#import "Common/ShaderLib/Skinning.glsllib"
#import "Common/ShaderLib/Instancing.glsllib"
#import "Common/ShaderLib/MorphAnim.glsllib"

attribute vec3 inPosition;
#ifdef GI_PROBES
attribute vec3 inNormal;
varying vec3 giWorldPosition;
varying vec3 giWorldNormal;
#endif
#ifdef SURFACE_CAPTURE
varying vec3 capturePosition;
#endif

#if defined(HAS_COLORMAP) || (defined(HAS_LIGHTMAP) && !defined(SEPARATE_TEXCOORD))
    #define NEED_TEXCOORD1
#endif

attribute vec2 inTexCoord;
attribute vec2 inTexCoord2;
attribute vec4 inColor;

varying vec2 texCoord1;
varying vec2 texCoord2;

varying vec4 vertColor;
#ifdef HAS_POINTSIZE
    uniform float m_PointSize;
#endif

void main(){
    #ifdef NEED_TEXCOORD1
        texCoord1 = inTexCoord;
    #endif

    #ifdef SEPARATE_TEXCOORD
        texCoord2 = inTexCoord2;
    #endif

    #ifdef HAS_VERTEXCOLOR
        vertColor = inColor;
    #endif

    #ifdef HAS_POINTSIZE
        gl_PointSize = m_PointSize;
    #endif

    vec4 modelSpacePos = vec4(inPosition, 1.0);

    #ifdef GI_PROBES
        vec3 giModelNormal = inNormal;
    #endif
    #ifdef NUM_MORPH_TARGETS
        #ifdef GI_PROBES
            Morph_Compute(modelSpacePos, giModelNormal);
        #else
            Morph_Compute(modelSpacePos);
        #endif
    #endif

    #ifdef NUM_BONES
        #ifdef GI_PROBES
            Skinning_Compute(modelSpacePos, giModelNormal);
        #else
            Skinning_Compute(modelSpacePos);
        #endif
    #endif

    #ifdef SURFACE_CAPTURE
        capturePosition = TransformWorld(modelSpacePos).xyz;
    #endif
    #ifdef GI_PROBES
        giWorldPosition = TransformWorld(modelSpacePos).xyz;
        giWorldNormal = TransformWorldNormal(giModelNormal);
    #endif
    gl_Position = TransformWorldViewProjection(modelSpacePos);
}