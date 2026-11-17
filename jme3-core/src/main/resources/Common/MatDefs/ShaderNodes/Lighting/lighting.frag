#import "Common/ShaderLib/Lighting.glsllib"
#import "Common/ShaderLib/BlinnPhongLighting.glsllib"
void main(){
    vec3 normal = normalize(viewNormal);
    vec4 lightDir;
    vec3 lightVec;
    vec4 viewLightPosition = viewMatrix * vec4(lightPosition.xyz, clamp(lightColor.w, 0.0, 1.0));
    viewLightPosition.w = lightPosition.w;
    lightComputeDir(viewPosition, lightColor.w, viewLightPosition, lightDir, lightVec);
    float spotFalloff = computeSpotFalloff(lightDirection, lightVec);
    vec2 light = computeLighting(normal, normalize(viewDir), normalize(lightDir.xyz),
            lightDir.w * spotFalloff, max(shininess, 1.0));
    vec3 ambient = ambientLightColor.rgb * ambientColor.rgb * diffuseColor.rgb;
    vec3 direct = lightColor.rgb * (diffuseColor.rgb * light.x + specularColor.rgb * light.y);
    outColor = vec4(ambient + direct, diffuseColor.a);
}
