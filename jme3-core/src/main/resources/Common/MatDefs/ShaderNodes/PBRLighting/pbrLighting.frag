#import "Common/ShaderLib/Lighting.glsllib"
#import "Common/ShaderLib/PBR.glsllib"
void main(){
    vec3 normal = normalize(worldNormal);
    vec3 viewDirection = normalize(viewDir);
    vec4 lightDir;
    vec3 lightVec;
    lightComputeDir(worldPosition, lightColor.w, lightPosition, lightDir, lightVec);
    vec3 worldSpotDirection = (viewMatrixInverse * vec4(lightDirection.xyz, 0.0)).xyz;
    vec4 worldLightDirection = vec4(worldSpotDirection, lightDirection.w);
    float attenuation = lightDir.w * computeSpotFalloff(worldLightDirection, lightVec);
    float metal = clamp(metallic, 0.0, 1.0);
    float rough = clamp(roughness, 0.03, 1.0);
    vec3 fZero = mix(vec3(0.04), baseColor.rgb, metal);
    vec3 diffuseColor = baseColor.rgb * (1.0 - metal);
    vec3 directDiffuse;
    vec3 directSpecular;
    float ndotv = clamp(abs(dot(normal, viewDirection)), 0.001, 1.0);
    PBR_ComputeDirectLight(normal, normalize(lightDir.xyz), viewDirection, lightColor.rgb,
            fZero, rough, ndotv, directDiffuse, directSpecular);
    vec3 direct = (diffuseColor * directDiffuse + directSpecular) * attenuation;
    vec3 ambient = ambientLightColor.rgb * baseColor.rgb * clamp(ao, 0.0, 1.0);
    outColor = vec4(direct + ambient, baseColor.a);
}
