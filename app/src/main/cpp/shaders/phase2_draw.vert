#version 450

layout(constant_id = 0) const uint PIPELINE_VARIANT = 0u;

layout(push_constant) uniform DrawPush {
    vec4 transform;
    vec4 color;
} pushData;

layout(location = 0) out vec4 vertexColor;

const vec2 POSITIONS[3] = vec2[](
    vec2(-0.48, -0.42),
    vec2( 0.52, -0.38),
    vec2( 0.02,  0.56)
);

void main() {
    vec2 position = POSITIONS[gl_VertexIndex];
    float variantOffset = float(PIPELINE_VARIANT & 7u) * 0.0005;
    position = position * pushData.transform.xy + pushData.transform.zw;
    gl_Position = vec4(position.x + variantOffset, position.y, 0.25, 1.0);
    vertexColor = pushData.color;
}
