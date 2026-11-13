// Material-owned full-surface model, referenced by CustomLambert.j3md.
// The engine associates this function with shadingId; application code loads only
// the .j3md. Raw record meanings are defined HERE and in CustomLambertGBuffer.frag:
//   gbuffer0.rgb = diffuse, .a = shared shadingId metadata
//   gbuffer1.rgb = ambient response
//   gbuffer2     = unused
//   gbuffer3.xyz = world-space normal (not the built-in layout!)
//   gbuffer4.a   = opacity
// This entry returns FINAL color/opacity and applies each light's attenuation.
// Unlike the legacy per-light function, no hidden ambient/opacity interpretation
// or attenuation is applied by the dispatcher.
// Call chain: this is a function library, not a standalone fragment program.
// Step 1: CustomLambert.j3md references this file using DeferredLightingShader.
// Step 2: ShadingModelRegistry.resolve assigns/validates an ID and renames this
// entry to deferredSurfaceModel<ID>() to avoid entry-point name collisions.
// Step 3: DeferredResources.assembleLightingSource inserts the function and a
// dispatch branch into Tiled/Lighting.frag. For example, ID 65535 generates:
//     if (shadingId == 65535) {
//         return deferredSurfaceModel65535();
//     }
// Step 4: the fullscreen lighting draw executes main -> getShadingId ->
// subfaceShading(shadingId) -> the renamed entry below, on the GPU.
// 65535 is only an example; application code must not depend on automatic IDs.
// Keep shader source ASCII-compatible for the LWJGL 2 shader upload path.
vec4 deferredSurfaceShading() {
    vec3 diffuse = getGBuffer0().rgb;
    vec3 ambient = getGBuffer1().rgb;
    vec3 normal = normalize(getGBuffer3().xyz);
    float opacity = getGBuffer4().a;

    vec3 position = getWorldPosition();
    DeferredLightList lights = getLightList();
    vec3 result = ambient * AmbientColor;
    for (int entry = 0; entry < lights.count; ++entry) {
        DeferredLight light = getLight(lights, entry, position);
        float lambert = max(dot(normal, light.direction), 0.0);
        result += diffuse * light.color * lambert * light.attenuation;
    }
    return vec4(result, opacity);
}
