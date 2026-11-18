#import "Common/ShaderLib/GLSLCompat.glsllib"
#import "Common/ShaderLib/MultiSample.glsllib"
uniform COLORTEXTURE m_Texture;
varying vec2 texCoord;
uniform DEPTHTEXTURE m_DepthTexture;
#import "Common/MatDefs/Post/Cinematic/DepthView.glsllib"
uniform vec2 m_PixelSize;
uniform vec2 m_ProjectionScale;
uniform float m_Radius;
uniform float m_Bias;
uniform bool m_Orthographic;
void main() {
    if (sceneDepth(texCoord) >= 0.999999) {
        gl_FragColor = vec4(1.0, 65504.0, 0.0, 1.0);
        return;
    }
    vec3 p = viewPosition(texCoord);
    vec2 uv = clamp(texCoord, m_PixelSize, 1.0 - m_PixelSize);
    vec3 left = p - viewPosition(uv - vec2(m_PixelSize.x, 0.0));
    vec3 right = viewPosition(uv + vec2(m_PixelSize.x, 0.0)) - p;
    vec3 down = p - viewPosition(uv - vec2(0.0, m_PixelSize.y));
    vec3 up = viewPosition(uv + vec2(0.0, m_PixelSize.y)) - p;
    vec3 dx = abs(left.z) < abs(right.z) ? left : right;
    vec3 dy = abs(down.z) < abs(up.z) ? down : up;
    vec3 n = cross(dx, dy);
    n /= max(length(n), 0.000001);
    n *= dot(n, -p) < 0.0 ? -1.0 : 1.0;
    vec2 radiusUV = 0.5 * m_Radius * abs(m_ProjectionScale);
    if (!m_Orthographic) {
        radiusUV /= max(abs(p.z), 0.0001);
    }
    radiusUV = min(radiusUV, vec2(0.25));
    // Static interleaved gradient noise breaks coherent step rings without a
    // temporal history dependency. Bilateral passes remove the spatial noise.
    float noise = fract(52.9829189 * fract(dot(floor(gl_FragCoord.xy), vec2(0.06711056, 0.00583715))));
    float occlusion = 0.0;
    for (int direction = 0; direction < 8; direction++) {
        float angle = (float(direction) + noise) * 0.78539816339;
        vec2 ray = vec2(cos(angle), sin(angle)) * radiusUV;
        float horizon = 0.0;
        for (int stepIndex = 1; stepIndex <= 4; stepIndex++) {
            float fraction = (float(stepIndex) - 0.5 + 0.5 * noise) / 4.0;
            vec2 q = texCoord + ray * fraction * fraction;
            if (any(lessThan(q, m_PixelSize * 0.5)) || any(greaterThan(q, 1.0 - m_PixelSize * 0.5))) {
                continue;
            }
            if (sceneDepth(q) >= 0.999999) {
                continue;
            }
            vec3 delta = viewPosition(q) - p;
            float distanceSquared = dot(delta, delta);
            float attenuation = max(0.0, 1.0 - distanceSquared / (m_Radius * m_Radius));
            float cosine = dot(n, delta) * inversesqrt(max(distanceSquared, 0.0000001));
            horizon = max(horizon, max(0.0, cosine - m_Bias) / (1.0 - m_Bias) * attenuation);
        }
        occlusion += horizon;
    }
    gl_FragColor = vec4(clamp(1.0 - occlusion / 8.0, 0.0, 1.0), min(abs(p.z), 65504.0), 0.0, 1.0);
}
