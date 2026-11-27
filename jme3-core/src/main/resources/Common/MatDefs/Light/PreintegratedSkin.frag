// Skin owns its light loop; shared PBR and Lightmap sources remain unchanged.
#import "Common/ShaderLib/GLSLCompat.glsllib"
#import "Common/ShaderLib/ManagedShadows.glsllib"
#import "Common/ShaderLib/GI/ProbeSampling.glsllib"
#import "Common/ShaderLib/EnvironmentLighting.glsllib"
#import "Common/ShaderLib/DeferredSurface.glsllib"
#import "Common/ShaderLib/SurfaceCapture.glsllib"

// enable apis and import PBRLightingUtils
#define ENABLE_PBRLightingUtils_getWorldPosition 1
//#define ENABLE_PBRLightingUtils_getLocalPosition 1
#define ENABLE_PBRLightingUtils_getWorldNormal 1
#define ENABLE_PBRLightingUtils_getWorldTangent 1
#define ENABLE_PBRLightingUtils_getTexCoord 1
#define ENABLE_PBRLightingUtils_readPBRSurface 1
#define ENABLE_PBRLightingUtils_computeDirectLight 1
#define ENABLE_PBRLightingUtils_newLight 1
#define ENABLE_PBRLightingUtils_computeLightInWorldSpace 1
#define ENABLE_PBRLightingUtils_computeProbesContribution 1

#import "Common/ShaderLib/module/pbrlighting/PBRLightingUtils.glsllib"
#import "Common/ShaderLib/Deferred/PbrIndirect.glsllib"
#import "Common/ShaderLib/SkinLighting.glsllib"

#ifdef DEBUG_VALUES_MODE
    uniform int m_DebugValuesMode;
#endif

uniform vec4 g_LightData[NB_LIGHTS];
uniform vec4 g_ShadowIndices[NB_LIGHTS];
uniform int g_LightCount;
uniform bool g_IsFirstLightPass;
uniform vec3 g_CameraPosition;

#ifdef USE_FOG
    #import "Common/ShaderLib/MaterialFog.glsllib"
#endif

#import "Common/ShaderLib/TemporalFragment.glsllib"

void main(){
    #ifdef TEMPORAL_VELOCITY
        outTemporalVelocity = temporalMotion();
    #endif
    vec3 wpos = PBRLightingUtils_getWorldPosition();
    vec3 worldViewDir = normalize(g_CameraPosition - wpos);

    // Create a blank PBRSurface.
    PBRSurface surface = PBRLightingUtils_createPBRSurface(worldViewDir);

    // Read surface data from standard PBR matParams. (note: matParams are declared in 'PBRLighting.j3md' and initialized as uniforms in 'PBRLightingUtils.glsllib')
    PBRLightingUtils_readPBRSurface(surface);

    //Calculate necessary variables from pbr surface prior to applying lighting. Ensure all texture/param reading and blending occurrs prior to this being called!
    PBRLightingUtils_calculatePreLightingValues(surface);

    SkinInputs skin = skinReadInputs(surface);

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
        #ifdef SKIN_TRANSMISSION
            vec4 incident = lightData0;
        #endif
        lightData0.rgb *= managedShadow(g_ShadowIndices[i / 3].x, wpos, surface.normal, g_ShadowReceive);
        vec4 lightData1 = g_LightData[i+1];
        vec4 lightData2 = g_LightData[i+2];
        skinAccumulateLight(
          lightData0, lightData1, lightData2,
          skin, surface
        );
        #ifdef SKIN_TRANSMISSION
            skinAccumulateTransmission(incident, lightData1, lightData2, g_ShadowIndices[i / 3].x, skin, surface);
        #endif
    }


    #endif

    prepareDeferredPbrIndirect(surface);

    vec3 volumeIrradiance = vec3(0.0);
    #if (defined(GI_PROBES) || defined(STATIC_PROBE_LIGHTMAP)) && !defined(DEFERRED_GI_LIGHT)
        volumeIrradiance = giSampleIrradiance(wpos, surface.normal, surface.geometryNormal);
    #endif
    // Calculate env probes
    PBRLightingUtils_computeProbesContribution(surface);
    #ifdef ENVIRONMENT_LIGHTING
        #ifdef GI_CAPTURE_SKY
            float skyHemisphere;
            vec3 skyMask = giCaptureSkyMask(wpos, surface.geometryNormal, surface.normal, skyHemisphere);
        #endif
        #ifndef DEFERRED_GI_LIGHT
        surface.envLightContribution += shLambertResponse(shNativeIrradiance(g_SkySh, giSkyDirection(surface.normal)),
                surface.diffuseColor, surface.ao.r) * giSkyDiffuseWeight * g_SkyIntensity
                #ifdef GI_CAPTURE_SKY
                    * skyMask
                #endif
                ;
        #endif
        // Local reflections replace distant sky specular; they do not add energy.
        if (g_EnvironmentData.z > 0.5) {
            vec3 reflected = getSpecularDominantDir(surface.normal,
                    reflect(-surface.viewDir, surface.normal), surface.roughness * surface.roughness);
            surface.envLightContribution += ApproximateSpecularIBLPolynomial(g_SkyMap,
                    surface.specularColor, surface.roughness, surface.NdotV, reflected,
                    g_SkyMipCount) * g_SkyIntensity * (1.0 - environmentReflectionCoverage) * computeSpecularAO(clamp(surface.ao.r, 0.0, 1.0),
                    surface.roughness, surface.NdotV)
                    #ifdef GI_CAPTURE_SKY
                        * skyHemisphere
                    #endif
                    ;
        }
    #endif

    // Put it all together
    gl_FragColor.rgb = surface.directLightContribution;
    // Additional passes accumulate only their batch of direct lights.
    if (g_IsFirstLightPass) {
        gl_FragColor.rgb += surface.bakedLightContribution;
        #if defined(GI_PROBES) || defined(STATIC_PROBE_LIGHTMAP)
            gl_FragColor.rgb += shLambertResponse(volumeIrradiance, surface.diffuseColor, surface.ao.r);
        #endif
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
}
