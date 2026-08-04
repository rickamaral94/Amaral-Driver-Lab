#version 450

layout(push_constant) uniform PushConstants {
    float time_seconds;
    uint scene_kind;
    vec2 resolution;
} pc;

layout(location = 0) in vec2 in_uv;
layout(location = 1) flat in uint in_instance;
layout(location = 2) in float in_light;
layout(location = 0) out vec4 out_color;

vec3 palette(float t) {
    vec3 a = vec3(0.52, 0.50, 0.48);
    vec3 b = vec3(0.46, 0.42, 0.50);
    vec3 c = vec3(1.00, 0.82, 0.67);
    vec3 d = vec3(0.00, 0.18, 0.39);
    return a + b * cos(6.2831853 * (c * t + d));
}

void main() {
    vec2 p = in_uv * 2.0 - 1.0;
    float radius = length(p);
    float instance_phase = float(in_instance) * 0.071;
    vec3 color;

    if (pc.scene_kind == 0u) {
        float edge = smoothstep(1.02, 0.74, max(abs(p.x), abs(p.y)));
        float bevel = smoothstep(0.92, 0.62, radius);
        color = palette(instance_phase + pc.time_seconds * 0.035);
        color *= 0.38 + 0.78 * in_light;
        color += vec3(0.20, 0.34, 0.48) * bevel;
        color *= edge;
    } else if (pc.scene_kind == 1u) {
        float checker = mod(floor(in_uv.x * 8.0) + floor(in_uv.y * 8.0), 2.0);
        float rings = 0.5 + 0.5 * cos(radius * 36.0 - pc.time_seconds * 2.2
                                      + instance_phase * 8.0);
        float stripes = 0.5 + 0.5 * sin((in_uv.x + in_uv.y) * 42.0
                                        + pc.time_seconds * 1.5);
        vec3 base = palette(instance_phase + checker * 0.17);
        vec3 accent = palette(instance_phase + 0.42 + rings * 0.12);
        color = mix(base, accent, mix(checker, rings, 0.45));
        color *= 0.62 + 0.38 * stripes;
        color += pow(max(0.0, 1.0 - radius), 5.0) * vec3(0.55, 0.42, 0.24);
    } else {
        float wave = sin(p.x * 18.0 + pc.time_seconds * 2.0)
                   * cos(p.y * 21.0 - pc.time_seconds * 1.7);
        float core = exp(-radius * radius * 4.2);
        color = palette(instance_phase + wave * 0.08 + pc.time_seconds * 0.025);
        color = color * (0.56 + 0.44 * wave) + core * vec3(1.1, 0.55, 0.18);
    }

    out_color = vec4(clamp(color, 0.0, 1.0), 1.0);
}
