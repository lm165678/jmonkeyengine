#define textureCubeLod textureLod
#define textureCube texture
#define texture2D texture
#define mat3_sub mat3

#import "Common/ShaderLib/Deferred/GBuffer.glsllib"
#import "Common/ShaderLib/Deferred/LightList.glsllib"
#import "Common/ShaderLib/Deferred/LightingModel.glsllib"
#import "Common/ShaderLib/Deferred/PbrModel.glsllib"
#import "Common/ShaderLib/Deferred/UnshadedModel.glsllib"

layout(location = 0) out vec4 outFragColor;

// Material-owned functions are inserted after the common access/light APIs.
// CUSTOM_MODEL_FUNCTIONS

// One model owns decoding its surface, evaluating lights and returning opacity.
// Only the shadingId header and depth attachment have shared meanings.
vec4 subfaceShading(int shadingId) {
    if (shadingId == SHADING_MODEL_LIGHTING) {
        return shadeLightingSurface();
    }
    if (shadingId == SHADING_MODEL_PBR) {
        return shadePbrSurface();
    }
    if (shadingId == SHADING_MODEL_UNSHADED) {
        return shadeUnshadedSurface();
    }
    // DeferredResources.assembleLightingSource replaces the marker below before
    // shader compilation, e.g. if (shadingId == 65535) {
    //     return deferredSurfaceModel65535();
    // }
    // ShadingModelRegistry renames each material's deferredSurfaceShading entry
    // to deferredSurfaceModel<ID>; the ID is also written during its GBuffer pass.
    // CUSTOM_MODEL_DISPATCH
    return vec4(0.0);
}

void main() {
    int shadingId = getShadingId();
    if (shadingId == SHADING_MODEL_BACKGROUND) {
        discard;
    }
    outFragColor = subfaceShading(shadingId);
    gl_FragDepth = getSurfaceDepth();
}
