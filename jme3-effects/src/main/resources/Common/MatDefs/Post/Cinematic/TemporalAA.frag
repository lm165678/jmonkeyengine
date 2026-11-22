#import "Common/ShaderLib/GLSLCompat.glsllib"
uniform sampler2D m_Texture;
uniform sampler2D m_History;
uniform sampler2D m_HistoryDepth;
uniform sampler2D m_Velocity;
#import "Common/MatDefs/Post/Cinematic/TemporalSampling.glsllib"
uniform float m_CurrentWeight;
uniform bool m_Reset;
varying vec2 texCoord;
vec3 toYCoCg(vec3 c) {
    return vec3(dot(c, vec3(.25, .5, .25)), (c.r - c.b) * .5, (-c.r + 2.0 * c.g - c.b) * .25);
}
vec3 toRGB(vec3 c) {
    return vec3(c.x + c.y - c.z, c.x + c.z, c.x - c.y - c.z);
}
vec4 reconstructCurrent(vec2 outputUV) {
    // Reconstruct with bilinear taps here. Other passes use the scene
    // textures with nearest filtering, so leave their sampler state alone.
    vec2 p = (outputUV + m_CurrentJitter) / m_InverseSize - vec2(.5);
    ivec2 base = ivec2(floor(p));
    vec2 f = fract(p);
    vec4 a = texelFetch(m_Texture, temporalClampPixel(base), 0);
    vec4 b = texelFetch(m_Texture, temporalClampPixel(base + ivec2(1, 0)), 0);
    vec4 c = texelFetch(m_Texture, temporalClampPixel(base + ivec2(0, 1)), 0);
    vec4 d = texelFetch(m_Texture, temporalClampPixel(base + ivec2(1, 1)), 0);
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}
void main() {
    vec4 current = reconstructCurrent(texCoord);
    ivec2 centerPixel = temporalScenePixel(texCoord);
    ivec2 motionPixel = temporalClosestPixel(centerPixel);
    vec4 motion = texelFetch(m_Velocity, motionPixel, 0);
    // Velocity stores the previous depth in RGBA16F. The history depth has
    // full precision, so allow for quantization before rejecting a sample.
    float exponentScale = uintBitsToFloat(floatBitsToUint(abs(motion.z)) & 0x7f800000u);
    // Allow one half-float ULP because the render target may truncate on write.
    float halfStep = max(5.960464477539063e-8, exponentScale / 1024.0);
    float tolerance = max(halfStep + .000001,
            2.0 * max(abs(dFdx(motion.z)), abs(dFdy(motion.z))));
    vec2 historyUV = texCoord - motion.xy;
    if (m_Reset || motion.a < .5 || any(lessThan(historyUV, m_InverseSize * .5))
            || any(greaterThan(historyUV, vec2(1.0) - m_InverseSize * .5))) {
        gl_FragColor = current;
        return;
    }
    // Reproject the selected foreground sample for the depth test.
    // Color history still uses historyUV, the output pixel's UV.
    vec2 depthHistoryUV = (vec2(motionPixel) + vec2(.5)) * m_InverseSize
            - m_CurrentJitter - motion.xy;
    float oldDepth = texture(m_HistoryDepth, depthHistoryUV).r;
    // Relative device-depth slope tolerance; reject newly exposed surfaces.
    if (abs(oldDepth - motion.z) > tolerance) {
        gl_FragColor = current;
        return;
    }
    vec3 minimum = vec3(1e20);
    vec3 maximum = vec3(-1e20);
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            vec3 sampleColor = toYCoCg(texelFetch(m_Texture, temporalClampPixel(centerPixel + ivec2(x, y)), 0).rgb);
            minimum = min(minimum, sampleColor);
            maximum = max(maximum, sampleColor);
        }
    }
    vec3 history = toYCoCg(texture(m_History, historyUV).rgb);
    // Clip on the ray from the box center so the chroma channels move together.
    vec3 center = .5 * (minimum + maximum);
    // Half-float history can shift a constant Y/Co/Cg plane by one ULP.
    // A zero-width box amplifies that shift and drags valid colors inward.
    // Pad the extent by a relative allowance for half-float storage error.
    vec3 precisionPadding = max(vec3(.00001), max(abs(minimum), abs(maximum)) * .002);
    vec3 extent = .5 * (maximum - minimum) + precisionPadding;
    vec3 delta = history - center;
    vec3 normalized = abs(delta / extent);
    float scale = max(1.0, max(normalized.x, max(normalized.y, normalized.z)));
    history = toRGB(center + delta / scale);
    float speed = length(motion.xy / m_InverseSize);
    float weight = mix(m_CurrentWeight, max(m_CurrentWeight, .25), clamp(speed / 40.0, 0.0, 1.0));
    gl_FragColor = vec4(mix(history, current.rgb, weight), current.a);
}
