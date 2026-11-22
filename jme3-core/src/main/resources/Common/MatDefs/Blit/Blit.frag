#import "Common/ShaderLib/GLSLCompat.glsllib"
#import "Common/ShaderLib/MultiSample.glsllib"

uniform COLORTEXTURE m_Texture;
varying vec2 texCoord;

vec3 linearToSrgb(vec3 color) {
    vec3 linear = max(color, vec3(0.0));
    vec3 encodedLow = linear * 12.92;
    vec3 encodedHigh = 1.055 * pow(linear, vec3(1.0 / 2.4)) - 0.055;
    return mix(encodedLow, encodedHigh, step(vec3(0.0031308), linear));
}

#if defined(SRGB) && defined(OUTPUT_DITHERING)
// 4x4 Bayer ranks in output pixel coordinates. Center the threshold within one
// 8-bit code so the pattern preserves the average and leaves exact black alone.
float outputDither(vec2 pixel) {
    vec2 low = mod(pixel, 2.0);
    vec2 high = mod(floor(pixel * 0.5), 2.0);
    float lowRank = 2.0 * abs(low.x - low.y) + low.y;
    float highRank = 2.0 * abs(high.x - high.y) + high.y;
    return (4.0 * lowRank + highRank - 7.5) / (16.0 * 255.0);
}
#endif

#ifdef HDR_DISPLAY_OUTPUT
uniform float m_HdrHeadroom;

vec3 mapHdrHighlights(vec3 color) {
    vec3 rgb = max(color, vec3(0.0));
    float peak = max(max(rgb.r, rgb.g), rgb.b);
    float available = m_HdrHeadroom - 1.0;
    float aboveWhite = max(peak - 1.0, 0.0);
    // Preserve SDR white and the shadow ramp. Roll highlights into the
    // display's headroom with a common RGB scale to preserve their hue.
    float mappedPeak = min(peak, 1.0) + available * (aboveWhite / (available + aboveWhite));
    return rgb * (mappedPeak / max(peak, 0.00000001));
}
#endif

void main() {
    vec4 color = getColor(m_Texture, texCoord);
    #ifdef HDR_DISPLAY_OUTPUT
        gl_FragColor = vec4(mapHdrHighlights(color.rgb), 1.0);
    #elif defined(SRGB)
        vec3 encoded = linearToSrgb(color.rgb);
        #ifdef OUTPUT_DITHERING
            encoded = clamp(encoded + outputDither(floor(gl_FragCoord.xy)), 0.0, 1.0);
        #endif
        gl_FragColor = vec4(encoded, color.a);
    #else
        gl_FragColor = color;
    #endif
}
