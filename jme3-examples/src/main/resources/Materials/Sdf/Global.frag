#import "Common/ShaderLib/GLSLCompat.glsllib"

uniform sampler3D m_Field0;
uniform sampler3D m_Field1;
uniform sampler3D m_Field2;
uniform vec3 m_Min0;
uniform vec3 m_Min1;
uniform vec3 m_Min2;
uniform vec3 m_Cells;
uniform float m_Resolution;
uniform mat4 m_ClipToWorld;
uniform bool m_CascadeColors;
varying vec2 texCoord;

vec3 levelMin(int level) {
    if (level == 0) {
        return m_Min0;
    }
    if (level == 1) {
        return m_Min1;
    }
    return m_Min2;
}

bool containsPoint(vec3 p, int level) {
    vec3 cell = vec3(m_Cells[level]);
    vec3 low = levelMin(level) + cell;
    vec3 high = levelMin(level) + cell * (m_Resolution - 2.0);
    return all(greaterThanEqual(p, low)) && all(lessThanEqual(p, high));
}

int chooseLevel(vec3 p) {
    if (containsPoint(p, 0)) {
        return 0;
    }
    if (containsPoint(p, 1)) {
        return 1;
    }
    return 2;
}

float distanceAt(vec3 p, int level) {
    vec3 uvw = ((p - levelMin(level)) / m_Cells[level] + vec3(0.5)) / m_Resolution;
    if (level == 0) {
        return texture(m_Field0, uvw).r;
    }
    if (level == 1) {
        return texture(m_Field1, uvw).r;
    }
    return texture(m_Field2, uvw).r;
}

float surfaceAt(vec3 p) {
    // Blend raw distance estimates across clipmap borders instead of making a hard
    // jump between different voxel sizes. This only smooths debug visualization.
    float result = distanceAt(p, 2);
    for (int level = 1; level >= 0; level--) {
        vec3 low = levelMin(level);
        vec3 high = low + vec3(m_Cells[level] * (m_Resolution - 1.0));
        vec3 border = min(p - low, high - p);
        float edge = min(border.x, min(border.y, border.z));
        float weight = smoothstep(m_Cells[level], m_Cells[level] * 5.0, edge);
        if (weight > 0.0) {
            float fine = distanceAt(p, level);
            result = mix(result, fine, weight);
        }
    }
    return result;
}

bool intersectVolume(vec3 origin, vec3 direction, out float start, out float finish) {
    start = 0.0;
    finish = 1e30;
    vec3 high = m_Min2 + vec3(m_Cells.z * (m_Resolution - 1.0));
    for (int axis = 0; axis < 3; axis++) {
        if (abs(direction[axis]) < 1e-8) {
            if (origin[axis] < m_Min2[axis] || origin[axis] > high[axis]) {
                return false;
            }
        } else {
            float a = (m_Min2[axis] - origin[axis]) / direction[axis];
            float b = (high[axis] - origin[axis]) / direction[axis];
            start = max(start, min(a, b));
            finish = min(finish, max(a, b));
        }
    }
    return finish >= start;
}

void main() {
    vec2 clip = texCoord * 2.0 - 1.0;
    vec4 a = m_ClipToWorld * vec4(clip, -1.0, 1.0);
    vec4 b = m_ClipToWorld * vec4(clip, 1.0, 1.0);
    vec3 origin = a.xyz / a.w;
    vec3 end = b.xyz / b.w;
    vec3 direction = normalize(end - origin);
    gl_FragColor = vec4(0.025, 0.035, 0.055, 1.0);
    float t;
    float finish;
    if (!intersectVolume(origin, direction, t, finish)) {
        return;
    }
    finish = min(finish, length(end - origin));
    // Three clipmaps span up to 4*(resolution-1) finest cells. Include diagonal
    // rays and slow convergence near unsigned surfaces in the diagnostic budget.
    for (int step = 0; step < 4096; step++) {
        if (t > finish) {
            return;
        }
        vec3 p = origin + direction * t;
        int level = chooseLevel(p);
        float cell = m_Cells[level];
        float d = surfaceAt(p);
        // Uncertainty-band preview, not a triangle-accurate DDGI intersection.
        if (d < cell * 0.5) {
            vec3 dx = vec3(cell * 0.5, 0.0, 0.0);
            vec3 dy = vec3(0.0, cell * 0.5, 0.0);
            vec3 dz = vec3(0.0, 0.0, cell * 0.5);
            vec3 gradient = vec3(surfaceAt(p + dx) - surfaceAt(p - dx),
                    surfaceAt(p + dy) - surfaceAt(p - dy),
                    surfaceAt(p + dz) - surfaceAt(p - dz));
            vec3 normal = dot(gradient, gradient) > 1e-12 ? normalize(gradient) : -direction;
            if (dot(normal, direction) > 0.0) {
                normal = -normal;
            }
            vec3 color = vec3(0.65, 0.75, 0.85);
            if (m_CascadeColors) {
                color = level == 0 ? vec3(0.3, 0.8, 0.95)
                        : (level == 1 ? vec3(0.45, 0.95, 0.45) : vec3(0.95, 0.65, 0.2));
            }
            float light = 0.25 + 0.75 * max(0.0, dot(normal, normalize(vec3(0.4, 0.8, 0.3))));
            gl_FragColor = vec4(color * light, 1.0);
            return;
        }
        // Limit steps to the finest cell to avoid skipping finer-level entry regions.
        t += min(max((d - cell * 0.5) * 0.7, cell * 0.025), m_Cells.x * 0.5);
    }
    gl_FragColor = vec4(0.8, 0.0, 0.6, 1.0);
}
