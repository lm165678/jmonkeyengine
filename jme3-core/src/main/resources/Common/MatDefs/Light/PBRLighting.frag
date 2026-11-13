#import "Common/ShaderLib/GLSLCompat.glsllib"
#import "Common/ShaderLib/DeferredSurface.glsllib"
#import "Common/ShaderLib/SurfaceCapture.glsllib"

// enable apis and import PBRLightingUtils
#define ENABLE_PBRLightingUtils_getWorldPosition 1
//#define ENABLE_PBRLightingUtils_getLocalPosition 1
#define ENABLE_PBRLightingUtils_getWorldNormal 1
#define ENABLE_PBRLightingUtils_getWorldTangent 1
#define ENABLE_PBRLightingUtils_getTexCoord 1
#define ENABLE_PBRLightingUtils_readPBRSurface 1
#define ENABLE_PBRLightingUtils_computeDirectLightContribution 1
#define ENABLE_PBRLightingUtils_computeProbesContribution 1

#import "Common/ShaderLib/module/pbrlighting/PBRLightingUtils.glsllib"
#import "Common/ShaderLib/Deferred/PbrIndirect.glsllib"

#ifdef DEBUG_VALUES_MODE
    uniform int m_DebugValuesMode;
#endif

uniform vec4 g_LightData[NB_LIGHTS];
uniform int g_LightCount;
uniform bool g_IsFirstLightPass;
uniform vec3 g_CameraPosition;

#ifdef USE_FOG
    #import "Common/ShaderLib/MaterialFog.glsllib"
#endif

void main(){
    vec3 wpos = PBRLightingUtils_getWorldPosition();
    vec3 worldViewDir = normalize(g_CameraPosition - wpos);
    
    // Create a blank PBRSurface.
    PBRSurface surface = PBRLightingUtils_createPBRSurface(worldViewDir);
    
    // Read surface data from standard PBR matParams. (note: matParams are declared in 'PBRLighting.j3md' and initialized as uniforms in 'PBRLightingUtils.glsllib')
    PBRLightingUtils_readPBRSurface(surface);          

    //Calculate necessary variables from pbr surface prior to applying lighting. Ensure all texture/param reading and blending occurrs prior to this being called!
    PBRLightingUtils_calculatePreLightingValues(surface);
    
    // Capture uses the latest material evaluation, without direct or probe lighting.
    #ifdef SURFACE_CAPTURE
        writeSurfaceAttributes(surface.diffuseColor, surface.normal, surface.emission);
        return;
    #endif

    #ifndef DEFERRED_GBUFFER
    // Calculate direct lights
    for(int i = 0;i < NB_LIGHTS; i+=3){
        #if (defined(GL_ES) && __VERSION__ >= 300) || (!defined(GL_ES) && __VERSION__ >= 150)
        if(i >= g_LightCount * 3){
            break;
        }
        #endif
        vec4 lightData0 = g_LightData[i];
        vec4 lightData1 = g_LightData[i+1];
        vec4 lightData2 = g_LightData[i+2];    
        PBRLightingUtils_computeDirectLightContribution(
          lightData0, lightData1, lightData2, 
          surface
        );
    }


    #endif

    prepareDeferredPbrIndirect(surface);

    // Calculate env probes
    PBRLightingUtils_computeProbesContribution(surface);

    // Put it all together
    gl_FragColor.rgb = surface.directLightContribution;
    // Additional passes accumulate only their batch of direct lights.
    if (g_IsFirstLightPass) {
        gl_FragColor.rgb += surface.bakedLightContribution;
        gl_FragColor.rgb += surface.envLightContribution;
        gl_FragColor.rgb += surface.emission;
    }
    gl_FragColor.a = surface.alpha;    

    #ifdef USE_FOG
        gl_FragColor = MaterialFog_calculateFogColor(vec4(gl_FragColor));
    #endif
    
   //outputs the final value of the selected layer as a color for debug purposes. 
    #ifdef DEBUG_VALUES_MODE
        gl_FragColor = PBRLightingUtils_getColorOutputForDebugMode(m_DebugValuesMode, vec4(gl_FragColor.rgba), surface);
    #endif   
    #ifdef DEFERRED_GBUFFER
        // Upstream specular AA depends only on normal derivatives. Filter here,
        // before storing the normal, so deferred lighting keeps geometry derivatives.
        float deferredRoughness = surface.roughness;
        #ifdef SPECULAR_AA
            float sigma = 1.0;
            float kappa = 0.18;
            #ifdef SPECULAR_AA_SCREEN_SPACE_VARIANCE
                sigma = m_SpecularAASigma;
            #endif
            #ifdef SPECULAR_AA_THRESHOLD
                kappa = m_SpecularAAKappa;
            #endif
            deferredRoughness = sqrt(Specular_Anti_Aliasing(surface.normal, vec3(0.0),
                    surface.roughness * surface.roughness, sigma, kappa));
        #endif
        float transmission = 1.0;
        #ifdef USE_FOG
            #ifdef FOG_LINEAR
                transmission *= clamp((m_LinearFog.y - fogDistance) / (m_LinearFog.y - m_LinearFog.x), 0.0, 1.0);
            #endif
            #ifdef FOG_EXP
                transmission *= clamp(exp(-fogDistance * m_ExpFog), 0.0, 1.0);
            #endif
            #ifdef FOG_EXPSQ
                transmission *= clamp(exp(-pow(fogDistance * m_ExpSqFog, 2.0)), 0.0, 1.0);
            #endif
        #endif
        int model = m_DeferredShadingId;
        #ifdef DEBUG_VALUES_MODE
            model = 3;
        #endif
        // PBR decoder: record4 holds fog transmission, sun exposure, and flag 1.
        writeDeferredSurface(surface.diffuseColor, surface.normal, deferredRoughness,
                surface.fZero, gl_FragColor.rgb, surface.alpha,
                vec3(transmission, surface.exposure, 0.0), 1.0, model);
    #endif
}
