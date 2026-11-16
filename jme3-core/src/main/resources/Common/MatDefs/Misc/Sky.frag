#import "Common/ShaderLib/GLSLCompat.glsllib"
#import "Common/ShaderLib/Optics.glsllib"

uniform ENVMAP m_Texture;

varying vec3 direction;

void main() {
    vec3 dir = normalize(direction);
    gl_FragColor = Optics_GetEnvColor(m_Texture, dir);
    #ifdef CAPTURE_DECODE_SRGB
        vec3 value = gl_FragColor.rgb;
        gl_FragColor.rgb = mix(value / 12.92,
                pow((value + 0.055) / 1.055, vec3(2.4)), step(vec3(0.04045), value));
    #endif
}

