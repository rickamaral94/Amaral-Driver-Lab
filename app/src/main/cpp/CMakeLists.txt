cmake_minimum_required(VERSION 3.22.1)
project(amaral_driver_lab LANGUAGES C CXX)

include(FetchContent)
find_package(Python3 COMPONENTS Interpreter REQUIRED)

# Pinned: https://github.com/bylaws/libadrenotools/commit/8fae8ce254dfc1344527e05301e43f37dea2df80
FetchContent_Declare(
        adrenotools_source
        GIT_REPOSITORY https://github.com/bylaws/libadrenotools.git
        GIT_TAG 8fae8ce254dfc1344527e05301e43f37dea2df80
        GIT_SHALLOW FALSE
        GIT_SUBMODULES_RECURSE TRUE)
FetchContent_MakeAvailable(adrenotools_source)

set(GLSLC_SEARCH_PATHS
        "${CMAKE_ANDROID_NDK}/shader-tools/${ANDROID_HOST_TAG}"
        "${CMAKE_ANDROID_NDK}/shader-tools/linux-x86_64"
        "${CMAKE_ANDROID_NDK}/shader-tools/darwin-x86_64"
        "${CMAKE_ANDROID_NDK}/shader-tools/windows-x86_64")
find_program(GLSLC_EXECUTABLE NAMES glslc glslc.exe PATHS ${GLSLC_SEARCH_PATHS} NO_DEFAULT_PATH)
if (NOT GLSLC_EXECUTABLE)
    find_program(GLSLC_EXECUTABLE NAMES glslc glslc.exe)
endif ()
if (NOT GLSLC_EXECUTABLE)
    message(FATAL_ERROR "glslc not found. Install the NDK shader tools or place glslc on PATH.")
endif ()

set(GENERATED_SHADER_DIR "${CMAKE_CURRENT_BINARY_DIR}/generated-shaders")
set(SPIRV_EXTRACTOR "${CMAKE_CURRENT_SOURCE_DIR}/extract_spirv_initializer.py")

function(driverlab_compile_shader SOURCE OUTPUT_NAME)
    set(SPIRV_C "${GENERATED_SHADER_DIR}/${OUTPUT_NAME}.c")
    set(SPIRV_INC "${GENERATED_SHADER_DIR}/${OUTPUT_NAME}.inc")
    add_custom_command(
            OUTPUT "${SPIRV_INC}"
            BYPRODUCTS "${SPIRV_C}"
            COMMAND "${CMAKE_COMMAND}" -E make_directory "${GENERATED_SHADER_DIR}"
            COMMAND "${GLSLC_EXECUTABLE}" -c --target-env=vulkan1.0 -mfmt=c
                    "${CMAKE_CURRENT_SOURCE_DIR}/${SOURCE}" -o "${SPIRV_C}"
            COMMAND "${Python3_EXECUTABLE}" "${SPIRV_EXTRACTOR}"
                    "${SPIRV_C}" "${SPIRV_INC}"
            DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/${SOURCE}" "${SPIRV_EXTRACTOR}"
            VERBATIM)
    set(DRIVERLAB_SHADER_OUTPUTS ${DRIVERLAB_SHADER_OUTPUTS} "${SPIRV_INC}" PARENT_SCOPE)
endfunction()

driverlab_compile_shader("shaders/render_correctness.vert" "render_correctness_vert")
driverlab_compile_shader("shaders/render_correctness.frag" "render_correctness_frag")
driverlab_compile_shader("shaders/phase2_draw.vert" "phase2_draw_vert")
driverlab_compile_shader("shaders/phase2_alu.frag" "phase2_alu_frag")
driverlab_compile_shader("shaders/phase2_branch.frag" "phase2_branch_frag")
driverlab_compile_shader("shaders/phase2_compute.comp" "phase2_compute_comp")
driverlab_compile_shader("shaders/trace_solid.frag" "trace_solid_frag")
driverlab_compile_shader("shaders/trace_integer.comp" "trace_integer_comp")
driverlab_compile_shader("shaders/visual_scene.vert" "visual_scene_vert")
driverlab_compile_shader("shaders/visual_scene.frag" "visual_scene_frag")
driverlab_compile_shader("shaders/visual_post.vert" "visual_post_vert")
driverlab_compile_shader("shaders/visual_post.frag" "visual_post_frag")

add_custom_target(driverlab_shaders DEPENDS ${DRIVERLAB_SHADER_OUTPUTS})

add_library(driverlab SHARED benchmark.cpp render_correctness.cpp phase2_workloads.cpp
        visual_scenes.cpp)
add_dependencies(driverlab driverlab_shaders)
target_include_directories(driverlab PRIVATE "${GENERATED_SHADER_DIR}")
target_compile_features(driverlab PRIVATE cxx_std_20)
target_compile_definitions(driverlab PRIVATE VK_NO_PROTOTYPES)

find_library(android-lib android REQUIRED)
find_library(dl-lib dl REQUIRED)
find_library(log-lib log REQUIRED)

target_link_libraries(
        driverlab
        PRIVATE
        adrenotools
        ${android-lib}
        ${dl-lib}
        ${log-lib})
