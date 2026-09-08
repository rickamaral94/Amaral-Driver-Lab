# Turns glslc's -mfmt=c output (a bare "{0x07230203, ...}" initialiser) into a
# header declaring a static array, so the SPIR-V can be included directly.
file(READ "${BODY}" SPIRV_BODY)
string(STRIP "${SPIRV_BODY}" SPIRV_BODY)
file(WRITE "${HEADER}"
        "// Generated from GLSL at build time. Do not edit.\n"
        "#pragma once\n"
        "#include <cstdint>\n"
        "static const uint32_t ${SYMBOL}[] = ${SPIRV_BODY};\n")
file(REMOVE "${BODY}")
