#import "Common/ShaderLib/GLSLCompat.glsllib"
#import "Common/ShaderLib/MultiSample.glsllib"
uniform COLORTEXTURE m_Texture;
varying vec2 texCoord;
uniform sampler2D m_History;
uniform vec4 m_Settings;
uniform vec2 m_Speeds;
uniform float m_DeltaTime;
uniform bool m_Reset;
uniform bool m_SceneHistogram;
uniform vec2 m_Brightness;
uniform float m_TransitionDistance;
void main() {
    float cumulative = 0.0;
    float sum = 0.0;
    float total = 0.0;
    for (int i = 0; i < 64; i++) {
        vec2 bin = getColor(m_Texture, vec2((float(i) + 0.5) / 64.0, 0.5)).rg;
        float count = bin.r;
        float weight = max(0.0, min(cumulative + count, m_Settings.w) - max(cumulative, m_Settings.z));
        // Retain the measured mean within each bin; using only bin centers
        // would quantize exposure in 0.4375-stop jumps during camera movement.
        float logL = bin.g / max(count, 0.0000001);
        sum += weight * logL;
        total += weight;
        cumulative += count;
    }
    float target = clamp(log2(0.18) - sum / max(total, 0.00001), m_Settings.x, m_Settings.y);
    if (m_SceneHistogram) {
        float average = clamp(exp2(sum / max(total, 0.00001)), m_Brightness.x, m_Brightness.y);
        target = log2(0.18 / average);
    }
    float result = target;
    if (!m_Reset) {
        float previous = texture2D(m_History, vec2(0.5)).r;
        float speed = target > previous ? m_Speeds.x : m_Speeds.y;
        if (m_SceneHistogram) {
            // Exposure multiplier changes opposite to the metered scene brightness.
            speed = target < previous ? m_Speeds.x : m_Speeds.y;
            float distance = abs(target - previous);
            float stepSize;
            if (distance > m_TransitionDistance) {
                stepSize = min(distance, speed * m_DeltaTime);
            } else {
                float epsilon = 1.0 / 60.0;
                float scale = epsilon * speed / ((1.0 - exp2(-epsilon * speed)) * m_TransitionDistance);
                stepSize = min(distance, distance * (1.0 - exp2(-m_DeltaTime * speed)) * scale);
            }
            result = previous + sign(target - previous) * stepSize;
            result = clamp(result, log2(0.18 / m_Brightness.y), log2(0.18 / m_Brightness.x));
        } else {
            result = mix(previous, target, 1.0 - exp(-speed * m_DeltaTime));
        }
    }
    gl_FragColor = vec4(result, target, 0.0, 1.0);
}
