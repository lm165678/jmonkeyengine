// The material's ShadingId selects this lighting function. A custom GBuffer
// technique can encode different surface attributes in n.w, s and extra.
vec3 deferredModel7(vec4 a, vec4 n, vec4 s, vec4 extra,
                    vec3 viewDir, vec3 lightDir, vec3 lightColor) {
    return a.rgb * lightColor * max(dot(normalize(n.xyz), lightDir), 0.0);
}
