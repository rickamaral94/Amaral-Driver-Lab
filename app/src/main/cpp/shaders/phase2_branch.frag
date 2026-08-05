#version 450

layout(constant_id = 1) const uint PIPELINE_VARIANT = 0u;
layout(location = 0) in vec4 vertexColor;
layout(location = 0) out vec4 outputColor;

void main() {
    vec3 value = vertexColor.rgb;
    uint selector = (PIPELINE_VARIANT + uint(gl_FragCoord.x) + uint(gl_FragCoord.y)) & 3u;
    for (int index = 0; index < 8; ++index) {
        if (((selector + uint(index)) & 1u) == 0u) {
            value = value.yzx * vec3(0.971, 1.013, 0.997) + vec3(0.011, 0.007, 0.005);
        } else {
            value = value.zxy * vec3(1.009, 0.983, 1.017) + vec3(0.003, 0.009, 0.013);
        }
        value = fract(value);
    }
    outputColor = vec4(value, 1.0);
}
