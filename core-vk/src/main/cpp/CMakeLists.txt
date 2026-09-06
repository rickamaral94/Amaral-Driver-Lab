cmake_minimum_required(VERSION 3.22.1)
project(amaral_driver_lab_vk LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---------------------------------------------------------------------------
# Shaders
#
# GLSL is compiled to SPIR-V at build time and embedded as C arrays, so the APK
# ships no shader compiler and the bytes that run are the bytes that were
# reviewed. glslc comes from the NDK; ANDROID_NDK is set by AGP.
# ---------------------------------------------------------------------------
if(NOT DEFINED ANDROID_NDK)
    message(FATAL_ERROR "ANDROID_NDK is not set; this project builds through the Android Gradle plugin.")
endif()

file(GLOB GLSLC_CANDIDATES "${ANDROID_NDK}/shader-tools/*/glslc")
list(GET GLSLC_CANDIDATES 0 GLSLC)
if(NOT GLSLC)
    message(FATAL_ERROR "glslc not found under ${ANDROID_NDK}/shader-tools")
endif()

set(GENERATED_DIR "${CMAKE_CURRENT_BINARY_DIR}/generated")
file(MAKE_DIRECTORY "${GENERATED_DIR}")

set(SHADER_SOURCES
        shaders/baseline.vert
        shaders/baseline.frag
        shaders/tiling_geometry.vert
        shaders/tiling_geometry.frag
        shaders/fullscreen.vert
        shaders/tiling_resolve.frag)

set(SHADER_HEADERS "")
foreach(SHADER ${SHADER_SOURCES})
    get_filename_component(SHADER_NAME "${SHADER}" NAME)
    string(REPLACE "." "_" SYMBOL "${SHADER_NAME}")
    set(OUT_HEADER "${GENERATED_DIR}/${SYMBOL}.h")
    add_custom_command(
            OUTPUT "${OUT_HEADER}"
            COMMAND "${GLSLC}" --target-env=vulkan1.1 -O
            -mfmt=c -o "${OUT_HEADER}.body"
            "${CMAKE_CURRENT_SOURCE_DIR}/${SHADER}"
            COMMAND ${CMAKE_COMMAND}
            -DSYMBOL=${SYMBOL} -DBODY=${OUT_HEADER}.body -DHEADER=${OUT_HEADER}
            -P "${CMAKE_CURRENT_SOURCE_DIR}/wrap_spirv.cmake"
            DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/${SHADER}"
            "${CMAKE_CURRENT_SOURCE_DIR}/wrap_spirv.cmake"
            COMMENT "glslc ${SHADER_NAME}"
            VERBATIM)
    list(APPEND SHADER_HEADERS "${OUT_HEADER}")
endforeach()

add_custom_target(shaders DEPENDS ${SHADER_HEADERS})

# ---------------------------------------------------------------------------
# Optional: libadrenotools
#
# Rootless ICD loading needs the linker-namespace hook from libadrenotools. It is
# not vendored here; see docs/DRIVER_LOADING.md for the pinned commit and the
# fetch step. Without it the loader still builds and reports HOOK_UNAVAILABLE, so
# the system driver remains testable and no code path silently pretends to have
# loaded a package.
# ---------------------------------------------------------------------------
set(ADRENOTOOLS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../third_party/libadrenotools")
if(EXISTS "${ADRENOTOOLS_DIR}/CMakeLists.txt")
    message(STATUS "libadrenotools found; rootless ICD loading enabled")
    add_subdirectory("${ADRENOTOOLS_DIR}" adrenotools_build)
    set(ADRENOTOOLS_AVAILABLE ON)
else()
    message(WARNING "libadrenotools absent; imported driver packages cannot be loaded")
    set(ADRENOTOOLS_AVAILABLE OFF)
endif()

add_library(amaral_vk SHARED
        vk_api.cpp
        icd_loader.cpp
        vk_context.cpp
        offscreen_target.cpp
        workloads.cpp
        sha256.cpp
        jni_bridge.cpp)

add_dependencies(amaral_vk shaders)

target_include_directories(amaral_vk PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}"
        "${GENERATED_DIR}")

target_link_libraries(amaral_vk PRIVATE log android)

if(ADRENOTOOLS_AVAILABLE)
    target_compile_definitions(amaral_vk PRIVATE AMARAL_WITH_ADRENOTOOLS=1)
    target_link_libraries(amaral_vk PRIVATE adrenotools)
endif()
