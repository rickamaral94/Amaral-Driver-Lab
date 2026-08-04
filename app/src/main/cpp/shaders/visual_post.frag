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
    vec3 color = sample_scene(uv);

    if (pc.scene_kind == 1u) {
        float shift = 1.2 + 0.8 * sin(pc.time_seconds * 0.7);
        color.r = sample_scene(uv + vec2(texel.x * shift, 0.0)).r;
        color.b = sample_scene(uv - vec2(texel.x * shift, 0.0)).b;
        float grid = 0.96 + 0.04 * sin(uv.y * pc.resolution.y * 0.35);
        color *= grid;
    } else if (pc.scene_kind == 2u) {
        vec3 blur = vec3(0.0);
        blur += sample_scene(uv + texel * vec2(-2.0, 0.0));
        blur += sample_scene(uv + texel * vec2(2.0, 0.0));
        blur += sample_scene(uv + texel * vec2(0.0, -2.0));
        blur += sample_scene(uv + texel * vec2(0.0, 2.0));
        blur += sample_scene(uv + texel * vec2(-1.0, -1.0));
        blur += sample_scene(uv + texel * vec2(1.0, -1.0));
        blur += sample_scene(uv + texel * vec2(-1.0, 1.0));
        blur += sample_scene(uv + texel * vec2(1.0, 1.0));
        blur *= 0.125;
        vec3 bloom = max(blur - vec3(0.48), vec3(0.0)) * 1.75;
        color = color * 0.86 + bloom;
        color = color / (color + vec3(0.82));
    }

    vec2 centered = uv * 2.0 - 1.0;
    float vignette = smoothstep(1.42, 0.28, dot(centered, centered));
    color *= 0.56 + 0.44 * vignette;
    color = pow(max(color, vec3(0.0)), vec3(1.0 / 2.2));
    out_color = vec4(clamp(color, 0.0, 1.0), 1.0);
}
