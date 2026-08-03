cmake_minimum_required(VERSION 3.22.1)
project(amaral_driver_lab LANGUAGES C CXX)

include(FetchContent)

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
set(RENDER_VERT_INC "${GENERATED_SHADER_DIR}/render_correctness_vert.inc")
set(RENDER_FRAG_INC "${GENERATED_SHADER_DIR}/render_correctness_frag.inc")

add_custom_command(
        OUTPUT "${RENDER_VERT_INC}"
        COMMAND "${CMAKE_COMMAND}" -E make_directory "${GENERATED_SHADER_DIR}"
        COMMAND "${GLSLC_EXECUTABLE}" -c --target-env=vulkan1.0 -mfmt=c
                "${CMAKE_CURRENT_SOURCE_DIR}/shaders/render_correctness.vert"
                -o "${RENDER_VERT_INC}"
        DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/shaders/render_correctness.vert"
        VERBATIM)

add_custom_command(
        OUTPUT "${RENDER_FRAG_INC}"
        COMMAND "${CMAKE_COMMAND}" -E make_directory "${GENERATED_SHADER_DIR}"
        COMMAND "${GLSLC_EXECUTABLE}" -c --target-env=vulkan1.0 -mfmt=c
                "${CMAKE_CURRENT_SOURCE_DIR}/shaders/render_correctness.frag"
                -o "${RENDER_FRAG_INC}"
        DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/shaders/render_correctness.frag"
        VERBATIM)

add_custom_target(driverlab_shaders DEPENDS "${RENDER_VERT_INC}" "${RENDER_FRAG_INC}")

add_library(driverlab SHARED benchmark.cpp render_correctness.cpp)
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
