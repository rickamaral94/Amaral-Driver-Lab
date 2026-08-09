#version 450

layout(push_constant) uniform PushConstants {
    float time_seconds;
    uint scene_kind;
    vec2 resolution;
} pc;

layout(location = 0) out vec3 out_normal;
layout(location = 1) out vec3 out_local_position;
layout(location = 2) flat out uint out_instance;
layout(location = 3) out float out_view_depth;

const vec3 kCubePositions[36] = vec3[](
    vec3( 1, -1, -1), vec3( 1,  1, -1), vec3( 1, -1,  1),
    vec3( 1, -1,  1), vec3( 1,  1, -1), vec3( 1,  1,  1),
    vec3(-1, -1,  1), vec3(-1,  1, -1), vec3(-1, -1, -1),
    vec3(-1,  1,  1), vec3(-1,  1, -1), vec3(-1, -1,  1),
    vec3(-1,  1, -1), vec3(-1,  1,  1), vec3( 1,  1, -1),
    vec3( 1,  1, -1), vec3(-1,  1,  1), vec3( 1,  1,  1),
    vec3(-1, -1,  1), vec3(-1, -1, -1), vec3( 1, -1,  1),
    vec3( 1, -1,  1), vec3(-1, -1, -1), vec3( 1, -1, -1),
    vec3(-1, -1,  1), vec3( 1, -1,  1), vec3(-1,  1,  1),
    vec3(-1,  1,  1), vec3( 1, -1,  1), vec3( 1,  1,  1),
    vec3( 1, -1, -1), vec3(-1, -1, -1), vec3( 1,  1, -1),
    vec3( 1,  1, -1), vec3(-1, -1, -1), vec3(-1,  1, -1)
);

const vec3 kCubeNormals[36] = vec3[](
    vec3( 1, 0, 0), vec3( 1, 0, 0), vec3( 1, 0, 0),
    vec3( 1, 0, 0), vec3( 1, 0, 0), vec3( 1, 0, 0),
    vec3(-1, 0, 0), vec3(-1, 0, 0), vec3(-1, 0, 0),
    vec3(-1, 0, 0), vec3(-1, 0, 0), vec3(-1, 0, 0),
    vec3(0,  1, 0), vec3(0,  1, 0), vec3(0,  1, 0),
    vec3(0,  1, 0), vec3(0,  1, 0), vec3(0,  1, 0),
    vec3(0, -1, 0), vec3(0, -1, 0), vec3(0, -1, 0),
    vec3(0, -1, 0), vec3(0, -1, 0), vec3(0, -1, 0),
    vec3(0, 0,  1), vec3(0, 0,  1), vec3(0, 0,  1),
    vec3(0, 0,  1), vec3(0, 0,  1), vec3(0, 0,  1),
    vec3(0, 0, -1), vec3(0, 0, -1), vec3(0, 0, -1),
    vec3(0, 0, -1), vec3(0, 0, -1), vec3(0, 0, -1)
);

mat3 rotation_x(float angle) {
    float c = cos(angle);
    float s = sin(angle);
    return mat3(1, 0, 0, 0, c, s, 0, -s, c);
}

mat3 rotation_y(float angle) {
    float c = cos(angle);
    float s = sin(angle);
    return mat3(c, 0, -s, 0, 1, 0, s, 0, c);
}

mat3 rotation_z(float angle) {
    float c = cos(angle);
    float s = sin(angle);
    return mat3(c, s, 0, -s, c, 0, 0, 0, 1);
}

void main() {
    uint index = gl_InstanceIndex;
    uint gx = index % 12u;
    uint gy = (index / 12u) % 8u;
    uint gz = (index / 96u) % 8u;

    float instance_phase = float(index) * 0.17320508;
    vec3 lattice = vec3(float(gx) - 5.5, float(gy) - 3.5, float(gz) - 3.5);
    lattice *= vec3(0.82, 0.82, 1.06);
    lattice.y += sin(float(gx) * 0.61 + float(gz) * 0.47
                     + pc.time_seconds * 0.72) * 0.34;
    lattice.x += cos(float(gy) * 0.53 + float(gz) * 0.31
                     - pc.time_seconds * 0.48) * 0.22;

    float angle_a = instance_phase + pc.time_seconds * (0.31 + float(index % 7u) * 0.017);
    float angle_b = instance_phase * 0.47 - pc.time_seconds * 0.23;
    mat3 model_rotation = rotation_y(angle_a) * rotation_x(angle_b) * rotation_z(angle_a * 0.37);
    float pulse = 0.24 + 0.075 * (0.5 + 0.5 * sin(instance_phase * 1.9
                                                 + pc.time_seconds * 1.11));
    vec3 local_position = kCubePositions[gl_VertexIndex] * pulse;
    vec3 world_position = lattice + model_rotation * local_position;
    vec3 world_normal = normalize(model_rotation * kCubeNormals[gl_VertexIndex]);

    float camera_angle = pc.time_seconds * 0.105;
    vec3 camera_position = vec3(sin(camera_angle) * 5.8,
                                2.7 + sin(camera_angle * 1.7) * 0.8,
                                -12.8 + cos(camera_angle) * 2.2);
    vec3 target = vec3(0.0, 0.0, 0.2);
    vec3 forward = normalize(target - camera_position);
    vec3 right = normalize(cross(forward, vec3(0.0, 1.0, 0.0)));
    vec3 up = cross(right, forward);
    vec3 relative = world_position - camera_position;
    vec3 view_position = vec3(dot(relative, right), dot(relative, up), dot(relative, forward));

    float near_plane = 0.1;
    float far_plane = 48.0;
    float focal = 1.0 / tan(radians(58.0) * 0.5);
    float aspect = pc.resolution.x / pc.resolution.y;
    gl_Position = vec4(view_position.x * focal / aspect,
                       -view_position.y * focal,
                       view_position.z * far_plane / (far_plane - near_plane)
                           - near_plane * far_plane / (far_plane - near_plane),
                       view_position.z);

    out_normal = world_normal;
    out_local_position = kCubePositions[gl_VertexIndex];
    out_instance = index;
    out_view_depth = view_position.z;
}
