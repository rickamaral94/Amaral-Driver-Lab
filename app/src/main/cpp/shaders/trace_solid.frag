#version 450

layout(location = 0) in vec4 vertexColor;
layout(location = 0) out vec4 outputColor;

void main() {
    // The trace uses black for both clear and draw output so rasterization edge
    // rules cannot create false cross-driver correctness mismatches.
    outputColor = vec4(0.0, 0.0, 0.0, 1.0);
}
