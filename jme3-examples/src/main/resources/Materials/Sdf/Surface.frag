#import "Common/ShaderLib/GLSLCompat.glsllib"

uniform sampler3D m_DistanceField;
uniform vec3 m_BoundsMin;
uniform vec3 m_BoundsMax;
uniform vec3 m_Resolution;
uniform vec3 m_CellSize;
uniform mat4 m_ClipToLocal;
uniform float m_HitDistance;
uniform bool m_StepHeatmap;
varying vec2 texCoord;

float distanceAt(vec3 position) {
    vec3 grid = (position - m_BoundsMin) / (m_BoundsMax - m_BoundsMin);
    vec3 uvw = (grid * (m_Resolution - vec3(1.0)) + vec3(0.5)) / m_Resolution;
    return texture(m_DistanceField, uvw).r;
}

bool intersectBounds(vec3 origin, vec3 direction, out float nearT, out float farT) {
    nearT = 0.0;
    farT = 1e30;
    for (int axis = 0; axis < 3; axis++) {
        if (abs(direction[axis]) < 1e-8) {
            if (origin[axis] < m_BoundsMin[axis] || origin[axis] > m_BoundsMax[axis]) {
                return false;
            }
        } else {
            float a = (m_BoundsMin[axis] - origin[axis]) / direction[axis];
            float b = (m_BoundsMax[axis] - origin[axis]) / direction[axis];
            nearT = max(nearT, min(a, b));
            farT = min(farT, max(a, b));
        }
    }
    return farT >= nearT;
}

vec3 surfaceNormal(vec3 position, vec3 rayDirection) {
    vec3 dx = vec3(m_CellSize.x * 0.5, 0.0, 0.0);
    vec3 dy = vec3(0.0, m_CellSize.y * 0.5, 0.0);
    vec3 dz = vec3(0.0, 0.0, m_CellSize.z * 0.5);
    vec3 gradient = vec3(
            (distanceAt(position + dx) - distanceAt(position - dx)) / m_CellSize.x,
            (distanceAt(position + dy) - distanceAt(position - dy)) / m_CellSize.y,
            (distanceAt(position + dz) - distanceAt(position - dz)) / m_CellSize.z);
    vec3 normal = dot(gradient, gradient) > 1e-12 ? normalize(gradient) : -rayDirection;
    return dot(normal, rayDirection) > 0.0 ? -normal : normal;
}

void main() {
    vec2 clip = texCoord * 2.0 - 1.0;
    vec4 nearPoint = m_ClipToLocal * vec4(clip, -1.0, 1.0);
    vec4 farPoint = m_ClipToLocal * vec4(clip, 1.0, 1.0);
    vec3 origin = nearPoint.xyz / nearPoint.w;
    vec3 end = farPoint.xyz / farPoint.w;
    vec3 direction = normalize(end - origin);
    gl_FragColor = vec4(0.025, 0.035, 0.055, 1.0);
    float nearT;
    float farT;
    if (!intersectBounds(origin, direction, nearT, farT)) {
        return;
    }
    farT = min(farT, length(end - origin));
    float t = nearT;
    float epsilon = min(m_CellSize.x, min(m_CellSize.y, m_CellSize.z)) * 0.02;
    for (int step = 0; step < 512; step++) {
        if (t > farT) {
            return;
        }
        vec3 position = origin + direction * t;
        float distance = abs(distanceAt(position));
        if (distance <= m_HitDistance + epsilon) {
            vec3 normal = surfaceNormal(position, direction);
            float diffuse = max(0.0, dot(normal, normalize(vec3(0.4, 0.8, 0.3))));
            float rim = pow(1.0 - max(0.0, dot(normal, -direction)), 3.0);
            vec3 color = vec3(0.65, 0.75, 0.85) * (0.2 + 0.8 * diffuse) + 0.15 * rim;
            if (m_StepHeatmap) {
                float cost = min(1.0, float(step) / 128.0);
                color = mix(vec3(0.05, 0.2, 0.8), vec3(1.0, 0.25, 0.05), cost);
            }
            gl_FragColor = vec4(color, 1.0);
            return;
        }
        // Debug reconstruction only: interpolated samples are not exact distances.
        // The relaxed step and finite hit band are NOT a certified DDGI ray query.
        t += max((distance - m_HitDistance) * 0.7, epsilon * 0.25);
    }
    // Magenta distinguishes iteration exhaustion from a real background miss.
    gl_FragColor = vec4(0.8, 0.0, 0.6, 1.0);
}
