#version 450

// Fullscreen triangle for the second subpass. No attributes, no vertex buffer.

void main() {
    vec2 position = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2) * 2.0 - 1.0;
    gl_Position = vec4(position, 0.0, 1.0);
}
