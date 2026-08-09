#version 450

layout(set = 0, binding = 0) uniform sampler2D scene_texture;
layout(push_constant) uniform PushConstants {
    float time_seconds;
    uint scene_kind;
    vec2 resolution;
} pc;

layout(location = 0) in vec2 in_uv;
layout(location = 0) out vec4 out_color;

vec3 sample_scene(vec2 uv) {
    return texture(scene_texture, clamp(uv, vec2(0.0), vec2(1.0))).rgb;
}

void main() {
    vec2 uv = in_uv;
    vec2 texel = 1.0 / pc.resolution;
    vec2 centered = uv * 2.0 - 1.0;
    float radius = length(centered);

    vec3 color;
    float chroma = 1.25 + radius * 1.8;
    color.r = sample_scene(uv + vec2(texel.x * chroma, 0.0)).r;
    color.g = sample_scene(uv).g;
    color.b = sample_scene(uv - vec2(texel.x * chroma, 0.0)).b;

    vec3 bloom = vec3(0.0);
    const vec2 offsets[16] = vec2[](
        vec2(-4,  0), vec2( 4,  0), vec2( 0, -4), vec2( 0,  4),
        vec2(-3, -3), vec2( 3, -3), vec2(-3,  3), vec2( 3,  3),
        vec2(-2, -1), vec2( 2, -1), vec2(-2,  1), vec2( 2,  1),
        vec2(-1, -2), vec2( 1, -2), vec2(-1,  2), vec2( 1,  2)
    );
    for (int tap = 0; tap < 16; ++tap) {
        vec3 value = sample_scene(uv + offsets[tap] * texel);
        bloom += max(value - vec3(0.42), vec3(0.0));
    }
    bloom *= 0.0875;

    float scanline = 0.975 + 0.025 * sin(uv.y * pc.resolution.y * 1.5707963);
    float vignette = smoothstep(1.38, 0.22, dot(centered, centered));
    color = color * 0.84 + bloom;
    color *= scanline * (0.54 + 0.46 * vignette);
    color = color / (color + vec3(0.78));
    color = pow(max(color, vec3(0.0)), vec3(1.0 / 2.2));
    out_color = vec4(clamp(color, 0.0, 1.0), 1.0);
}
