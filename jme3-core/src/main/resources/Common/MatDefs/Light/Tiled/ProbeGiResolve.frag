#define texture2D texture
#import "Common/ShaderLib/Deferred/GBuffer.glsllib"
#import "Common/ShaderLib/GI/ProbeSampling.glsllib"
#import "Common/ShaderLib/GI/DeferredProbe.glsllib"

uniform sampler2D m_GiHalfIrradiance;
uniform sampler2D m_GiHalfSky;
uniform sampler2D m_GiHalfGuide;
layout(location = 0) out vec4 outIrradiance;
layout(location = 1) out vec4 outSky;
// Diagnostic R=executed fallback, G=receiver, B=rejected. Never read synchronously in production.
layout(location = 2) out vec4 outStatistics;

bool reconstructGi(vec4 receiver, out vec4 irradiance, out vec4 sky) {
    ivec2 pixel = getGBufferPixel();
    // The sampled pixel is exact: do not reject it for curvature or a boundary.
    if (all(equal(pixel & ivec2(1), ivec2(0)))) {
        ivec2 halfPixel = pixel / 2;
        irradiance = texelFetch(m_GiHalfIrradiance, halfPixel, 0);
        sky = texelFetch(m_GiHalfSky, halfPixel, 0);
        return true;
    }
    vec3 position = getWorldPosition();
    vec3 normal = normalize(getGBuffer1().xyz);
    vec3 plane = deferredGiPlane(receiver.a);
    float curvature = getGBuffer2().a;
    vec4 projected = InverseViewProjection * vec4(gl_FragCoord.xy / Resolution * 2.0 - 1.0,
            getSurfaceDepth() * 2.0 - 1.0, 1.0);
    vec4 stepX = InverseViewProjection[0] * (2.0 / Resolution.x);
    vec4 stepY = InverseViewProjection[1] * (2.0 / Resolution.y);
    float footprint = max(length((projected.xyz + stepX.xyz) / (projected.w + stepX.w) - position),
            length((projected.xyz + stepY.xyz) / (projected.w + stepY.w) - position));
    float planeTolerance = max(0.0005, footprint * 0.15);
    ivec2 halfSize = textureSize(m_GiHalfIrradiance, 0);
    vec2 coordinate = vec2(pixel) * 0.5;
    ivec2 base = ivec2(floor(coordinate));
    vec2 alpha = fract(coordinate);
    irradiance = vec4(0.0);
    sky = vec4(0.0);
    vec4 lowIrradiance = vec4(1.0e20);
    vec4 highIrradiance = vec4(-1.0e20);
    vec4 lowSky = vec4(1.0e20);
    vec4 highSky = vec4(-1.0e20);
    for (int tap = 0; tap < 4; tap++) {
        ivec2 offset = ivec2(tap & 1, tap >> 1);
        vec2 weights = mix(1.0 - alpha, alpha, vec2(offset));
        float weight = weights.x * weights.y;
        if (weight == 0.0) {
            continue;
        }
        ivec2 halfPixel = clamp(base + offset, ivec2(0), halfSize - 1);
        ivec2 sourcePixel = min(halfPixel * 2, ivec2(Resolution) - 1);
        vec4 guide = texelFetch(m_GiHalfGuide, halfPixel, 0);
        vec3 delta = position - guide.xyz;
        if (guide.w < 0.0 || (any(notEqual(sourcePixel, pixel)) && length(delta) + footprint > guide.w)) {
            return false;
        }
        vec4 sampleReceiver = texelFetch(m_DeferredGiReceivers, sourcePixel, 0);
        vec3 sampleNormal = normalize(texelFetch(gbuffer1, sourcePixel, 0).xyz);
        vec3 samplePlane = deferredGiPlane(sampleReceiver.a);
        float sampleCurvature = texelFetch(gbuffer2, sourcePixel, 0).a;
        if (sampleReceiver.a == 0.0 || dot(normal, sampleNormal) < 0.999
                || dot(plane, samplePlane) < 0.9995
                || abs(dot(delta, plane)) > planeTolerance
                || abs(dot(delta, samplePlane)) > planeTolerance
                || abs(curvature - sampleCurvature) > max(0.001, 0.1 * max(curvature, sampleCurvature))) {
            return false;
        }
        vec4 sampleIrradiance = texelFetch(m_GiHalfIrradiance, halfPixel, 0);
        vec4 sampleSky = texelFetch(m_GiHalfSky, halfPixel, 0);
        if (any(isnan(sampleIrradiance)) || any(isinf(sampleIrradiance))
                || any(isnan(sampleSky)) || any(isinf(sampleSky))) {
            return false;
        }
        irradiance += sampleIrradiance * weight;
        sky += sampleSky * weight;
        lowIrradiance = min(lowIrradiance, sampleIrradiance);
        highIrradiance = max(highIrradiance, sampleIrradiance);
        lowSky = min(lowSky, sampleSky);
        highSky = max(highSky, sampleSky);
    }
    // Do not smooth a strong lighting/coverage discontinuity even on a flat plane.
    vec3 allowance = max(vec3(0.005), highIrradiance.rgb * 0.05);
    return all(lessThanEqual(highIrradiance.rgb - lowIrradiance.rgb, allowance))
            && highIrradiance.a - lowIrradiance.a <= 0.02
            && all(lessThanEqual(highSky - lowSky, vec4(0.02)));
}

#ifndef GI_FULL_FALLBACK
// Check selection boundaries only when the cached conservative radius is exceeded.
// Tangential motion along a boundary is allowed; crossing volumes/bricks is not.
bool sameGiRegion(vec3 position, vec3 samplePosition, float radius) {
    if (length(position - samplePosition) <= radius) {
        return true;
    }
    int count = m_GiVolumeCount == 0 ? 1 : m_GiVolumeCount;
    for (int volume = 0; volume < 16; volume++) {
        if (volume >= count) {
            break;
        }
        vec3 origin = m_GiOrigin;
        vec3 counts = m_GiCounts;
        vec3 spacing = m_GiSpacing;
        if (m_GiVolumeCount > 0) {
            origin = giVolumeField(float(volume), 0.0).xyz;
            spacing = giVolumeField(float(volume), 1.0).xyz;
            counts = giVolumeField(float(volume), 2.0).xyz;
        }
        if (any(lessThan(counts, vec3(2.0)))) {
            continue;
        }
        vec3 size = (counts - 1.0) * spacing;
        vec3 local = position - origin;
        vec3 sampleLocal = samplePosition - origin;
        bool inside = all(greaterThanEqual(local, vec3(0.0))) && all(lessThanEqual(local, size));
        bool sampleInside = all(greaterThanEqual(sampleLocal, vec3(0.0)))
                && all(lessThanEqual(sampleLocal, size));
        if (inside != sampleInside) {
            return false;
        }
        if (inside && m_GiVolumeCount > 0 && m_GiVolumeDataHeight > 0) {
            vec3 blocks = giVolumeField(float(volume), 4.0).xyz;
            if (all(greaterThan(blocks, vec3(0.0)))) {
                vec3 blockSize = giVolumeField(float(volume), 5.0).xyz;
                vec3 cell = min(floor(local / blockSize), blocks - 1.0);
                vec3 sampleCell = min(floor(sampleLocal / blockSize), blocks - 1.0);
                if (any(notEqual(cell, sampleCell))) {
                    return false;
                }
            }
        }
    }
    return true;
}

// Gather incident lighting only. Never blur material response or scene color.
void gatherGi(ivec2 halfPixel, float spatialWeight, vec4 receiver, vec3 position,
        vec3 normal, vec3 plane, float footprint, inout vec4 irradiance,
        inout vec4 sky, inout float weightSum, inout float support) {
    ivec2 size = textureSize(m_GiHalfGuide, 0);
    if (spatialWeight <= 0.0 || any(lessThan(halfPixel, ivec2(0)))
            || any(greaterThanEqual(halfPixel, size))) {
        return;
    }
    vec4 guide = texelFetch(m_GiHalfGuide, halfPixel, 0);
    if (guide.w < 0.0) {
        return;
    }
    ivec2 sourcePixel = min(halfPixel * 2, ivec2(Resolution) - 1);
    vec4 sampleReceiver = texelFetch(m_DeferredGiReceivers, sourcePixel, 0);
    if (sampleReceiver.a == 0.0 || (sampleReceiver.a < 0.0) != (receiver.a < 0.0)) {
        return;
    }
    vec3 samplePlane = deferredGiPlane(sampleReceiver.a);
    float alignment = dot(plane, samplePlane);
    if (alignment < 0.9) {
        return;
    }
    vec3 delta = position - guide.xyz;
    // Curved neighboring surfaces deviate quadratically from either tangent.
    // Parallel separated surfaces receive no curvature allowance.
    float tolerance = max(0.0005, footprint * 0.75)
            + 0.55 * length(delta) * length(plane - samplePlane);
    float planeError = max(abs(dot(delta, plane)), abs(dot(delta, samplePlane)));
    if (planeError > tolerance || !sameGiRegion(position, guide.xyz, guide.w)) {
        return;
    }
    vec3 sampleNormal = normalize(texelFetch(gbuffer1, sourcePixel, 0).xyz);
    // Normal-map detail must not remove every same-surface cache sample.
    // The geometric tests above remain responsible for wall separation.
    float normalWeight = 0.05 + 0.95 * pow(max(dot(normal, sampleNormal), 0.0), 16.0);
    float weight = spatialWeight * normalWeight * exp2(-2.0 * planeError / tolerance)
            * smoothstep(0.9, 0.999, alignment);
    if (weight <= 0.00001) {
        return;
    }
    vec4 value = texelFetch(m_GiHalfIrradiance, halfPixel, 0);
    vec4 visibility = texelFetch(m_GiHalfSky, halfPixel, 0);
    if (any(isnan(value)) || any(isinf(value))
            || any(isnan(visibility)) || any(isinf(visibility))) {
        return;
    }
    irradiance += value * weight;
    sky += visibility * weight;
    weightSum += weight;
    support += spatialWeight;
}

// Approximate only rejected pixels, without any new probe/occlusion queries.
// Missing compatible samples remain dark rather than borrowing across a wall.
void approximateRejectedGi(vec4 receiver, out vec4 irradiance, out vec4 sky) {
    irradiance = vec4(0.0);
    sky = vec4(0.0);
    vec3 position = getWorldPosition();
    vec3 normal = normalize(getGBuffer1().xyz);
    vec3 plane = deferredGiPlane(receiver.a);
    vec4 projected = InverseViewProjection * vec4(gl_FragCoord.xy / Resolution * 2.0 - 1.0,
            getSurfaceDepth() * 2.0 - 1.0, 1.0);
    vec4 stepX = InverseViewProjection[0] * (2.0 / Resolution.x);
    vec4 stepY = InverseViewProjection[1] * (2.0 / Resolution.y);
    float footprint = max(length((projected.xyz + stepX.xyz) / (projected.w + stepX.w) - position),
            length((projected.xyz + stepY.xyz) / (projected.w + stepY.w) - position));
    vec2 coordinate = vec2(getGBufferPixel()) * 0.5;
    ivec2 base = ivec2(floor(coordinate));
    vec2 alpha = fract(coordinate);
    float weightSum = 0.0;
    float support = 0.0;
    for (int tap = 0; tap < 4; tap++) {
        ivec2 offset = ivec2(tap & 1, tap >> 1);
        vec2 weights = mix(1.0 - alpha, alpha, vec2(offset));
        gatherGi(base + offset, weights.x * weights.y, receiver, position, normal,
                plane, footprint, irradiance, sky, weightSum, support);
    }
    if (support < 0.99 || weightSum < 0.05) {
        // A small outer ring fills one-sided support at silhouettes and curves.
        // It samples the existing cache, never runs the full GI evaluator.
        for (int y = -1; y <= 2; y++) {
            for (int x = -1; x <= 2; x++) {
                if (x >= 0 && x <= 1 && y >= 0 && y <= 1) {
                    continue;
                }
                vec2 delta = vec2(base + ivec2(x, y)) - coordinate;
                float spatialWeight = 0.25 * exp2(-0.5 * dot(delta, delta));
                gatherGi(base + ivec2(x, y), spatialWeight, receiver, position, normal,
                        plane, footprint, irradiance, sky, weightSum, support);
            }
        }
    }
    if (weightSum > 0.00001) {
        irradiance /= weightSum;
        sky /= weightSum;
    }
}

#endif

void main() {
    outIrradiance = vec4(0.0);
    outSky = vec4(0.0, 0.0, 0.0, 1.0);
    outStatistics = vec4(0.0, 0.0, 0.0, 1.0);
    vec4 receiver = texelFetch(m_DeferredGiReceivers, getGBufferPixel(), 0);
    if (receiver.a == 0.0 || getShadingId() == SHADING_MODEL_BACKGROUND) {
        return;
    }
    outStatistics.g = 1.0;
    if (!reconstructGi(receiver, outIrradiance, outSky)) {
        outStatistics.b = 1.0;
        #ifdef GI_FULL_FALLBACK
        outStatistics.r = 1.0;
        #else
        approximateRejectedGi(receiver, outIrradiance, outSky);
        #endif
    }
}
