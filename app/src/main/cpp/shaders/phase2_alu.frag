#version 450

layout(constant_id = 1) const uint PIPELINE_VARIANT = 0u;
layout(location = 0) in vec4 vertexColor;
layout(location = 0) out vec4 outputColor;

void main() {
    vec4 value = vertexColor + vec4(float(PIPELINE_VARIANT & 15u) * 0.0007);
    for (int index = 0; index < 12; ++index) {
        value.rgb = value.rgb * vec3(1.00031, 0.99973, 1.00017)
                + value.gbr * vec3(0.00019, 0.00023, 0.00029);
        value.rgb = fract(value.rgb + vec3(0.013, 0.017, 0.019));
    }
    outputColor = vec4(value.rgb, 1.0);
}
