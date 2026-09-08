#version 450

layout(location = 0) in vec3 vColor;
layout(location = 1) in vec3 vNormal;

layout(location = 0) out vec4 outColor;

void main() {
    vec3 light = normalize(vec3(0.4, 0.6, 0.7));
    float lambert = max(dot(normalize(vNormal), light), 0.0);
    outColor = vec4(vColor * (0.25 + 0.75 * lambert), 1.0);
}
