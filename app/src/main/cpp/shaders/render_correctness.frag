#version 450

layout(location = 0) in vec3 interpolated_color;
layout(location = 0) out vec4 out_color;

void main() {
    vec3 curved = interpolated_color * interpolated_color
            * (vec3(3.0) - vec3(2.0) * interpolated_color);
    out_color = vec4(curved, 1.0);
}
