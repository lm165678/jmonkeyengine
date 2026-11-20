// jMonkeyEngine input adapter; AMD reconstruction kernels remain unchanged.
#version 430
layout(local_size_x=8, local_size_y=8) in;
uniform sampler2D r_user_motion;
uniform sampler2D r_user_reactive;
layout(r8) writeonly uniform image2D rw_reactive_mask;
layout(rg16f) writeonly uniform image2D rw_resolved_motion;
void main() {
    ivec2 position = ivec2(gl_GlobalInvocationID.xy);
    if (any(greaterThanEqual(position, imageSize(rw_reactive_mask)))) {
        return;
    }
    vec4 motion = texelFetch(r_user_motion, position, 0);
    bool invalid = motion.a < 0.5 || any(isnan(motion)) || any(isinf(motion));
    float reactive = clamp(texelFetch(r_user_reactive, position, 0).r, 0.0, 1.0);
    imageStore(rw_reactive_mask, position, vec4(invalid ? 1.0 : reactive));
    imageStore(rw_resolved_motion, position, vec4(invalid ? vec2(0.0) : motion.rg, 0.0, 0.0));
}
