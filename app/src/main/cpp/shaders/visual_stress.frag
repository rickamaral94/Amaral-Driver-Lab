#version 450

layout(push_constant) uniform PushConstants {
    float time_seconds;
    uint scene_kind;
    vec2 resolution;
} pc;

layout(location = 0) in vec3 in_normal;
layout(location = 1) in vec3 in_local_position;
layout(location = 2) flat in uint in_instance;
layout(location = 3) in float in_view_depth;
layout(location = 0) out vec4 out_color;

vec3 palette(float t) {
    vec3 a = vec3(0.48, 0.46, 0.52);
    vec3 b = vec3(0.52, 0.44, 0.48);
    vec3 c = vec3(1.00, 0.82, 0.68);
    vec3 d = vec3(0.02, 0.17, 0.37);
    return a + b * cos(6.283185307 * (c * t + d));
}

float hash31(vec3 p) {
    p = fract(p * vec3(0.1031, 0.11369, 0.13787));
    p += dot(p, p.yzx + 19.19);
    return fract((p.x + p.y) * p.z);
}

void main() {
    vec3 normal = normalize(in_normal);
    float phase = float(in_instance) * 0.0710678;
    vec3 base = palette(phase + pc.time_seconds * 0.018);

    vec3 procedural = abs(in_local_position);
    float micro = 0.0;
    vec3 sample_position = in_local_position * 2.7 + vec3(phase);
    for (int octave = 0; octave < 6; ++octave) {
        float frequency = exp2(float(octave));
        micro += sin(sample_position.x * frequency + pc.time_seconds * 0.31)
               * cos(sample_position.y * frequency - pc.time_seconds * 0.27)
               * sin(sample_position.z * frequency + phase) / frequency;
    }

    float largest_axis = max(procedural.x, max(procedural.y, procedural.z));
    float smallest_axis = min(procedural.x, min(procedural.y, procedural.z));
    float second_axis = procedural.x + procedural.y + procedural.z
                      - largest_axis - smallest_axis;
    float edge_distance = 1.0 - second_axis;
    float neon_edge = 1.0 - smoothstep(0.015, 0.11, edge_distance);
    float panel = step(0.72, fract((in_local_position.x + in_local_position.y
                                   + in_local_position.z + phase) * 5.0));
    float grain = hash31(in_local_position * 17.0 + vec3(float(in_instance))) - 0.5;

    vec3 view_direction = normalize(vec3(0.32, 0.46, -1.0));
    vec3 light_directions[4] = vec3[](
        normalize(vec3( 0.65,  0.82, -0.48)),
        normalize(vec3(-0.72,  0.26, -0.64)),
        normalize(vec3( 0.18, -0.91, -0.37)),
        normalize(vec3(-0.25,  0.61,  0.75))
    );
    vec3 light_colors[4] = vec3[](
        vec3(1.00, 0.72, 0.42),
        vec3(0.24, 0.63, 1.00),
        vec3(0.72, 0.28, 1.00),
        vec3(0.20, 1.00, 0.76)
    );

    vec3 lighting = vec3(0.055);
    for (int light = 0; light < 4; ++light) {
        float diffuse = max(dot(normal, light_directions[light]), 0.0);
        vec3 half_vector = normalize(light_directions[light] + view_direction);
        float specular = pow(max(dot(normal, half_vector), 0.0), 24.0 + float(light) * 12.0);
        lighting += light_colors[light] * (diffuse * 0.31 + specular * 0.58);
    }

    float fresnel = pow(1.0 - abs(dot(normal, view_direction)), 3.0);
    float fog = smoothstep(8.0, 23.0, in_view_depth);
    vec3 color = base * lighting;
    color *= 0.88 + micro * 0.12 + grain * 0.035;
    color += palette(phase + 0.42) * neon_edge * (0.72 + panel * 0.42);
    color += vec3(0.18, 0.45, 0.90) * fresnel * 0.34;
    color = mix(color, vec3(0.008, 0.012, 0.028), fog * 0.68);
    out_color = vec4(clamp(color, 0.0, 1.0), 1.0);
}
