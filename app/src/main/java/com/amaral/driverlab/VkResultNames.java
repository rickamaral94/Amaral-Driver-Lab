package com.amaral.driverlab;

/**
 * Maps the numeric VkResult reported by the native runners to its symbolic name.
 *
 * <p>The runners serialize {@code vk_result} as an integer because that is what
 * the Vulkan headers give them. An integer is useless in a report a human reads:
 * {@code -4} carries no meaning, {@code VK_ERROR_DEVICE_LOST} carries the whole
 * diagnosis. Keeping the translation on the Java side means the mapping can grow
 * without touching the native build.
 */
final class VkResultNames {
    private VkResultNames() {}

    /** Mesa Turnip, from VkPhysicalDeviceDriverProperties.driverID. */
    static final int DRIVER_ID_MESA_TURNIP = 18;

    static String of(int result) {
        switch (result) {
            case 0: return "VK_SUCCESS";
            case 1: return "VK_NOT_READY";
            case 2: return "VK_TIMEOUT";
            case 3: return "VK_EVENT_SET";
            case 4: return "VK_EVENT_RESET";
            case 5: return "VK_INCOMPLETE";
            case -1: return "VK_ERROR_OUT_OF_HOST_MEMORY";
            case -2: return "VK_ERROR_OUT_OF_DEVICE_MEMORY";
            case -3: return "VK_ERROR_INITIALIZATION_FAILED";
            case -4: return "VK_ERROR_DEVICE_LOST";
            case -5: return "VK_ERROR_MEMORY_MAP_FAILED";
            case -6: return "VK_ERROR_LAYER_NOT_PRESENT";
            case -7: return "VK_ERROR_EXTENSION_NOT_PRESENT";
            case -8: return "VK_ERROR_FEATURE_NOT_PRESENT";
            case -9: return "VK_ERROR_INCOMPATIBLE_DRIVER";
            case -10: return "VK_ERROR_TOO_MANY_OBJECTS";
            case -11: return "VK_ERROR_FORMAT_NOT_SUPPORTED";
            case -12: return "VK_ERROR_FRAGMENTED_POOL";
            case -13: return "VK_ERROR_UNKNOWN";
            case -1000069000: return "VK_ERROR_OUT_OF_POOL_MEMORY";
            case -1000072003: return "VK_ERROR_INVALID_EXTERNAL_HANDLE";
            case -1000161000: return "VK_ERROR_FRAGMENTATION";
            case -1000257000: return "VK_ERROR_INVALID_OPAQUE_CAPTURE_ADDRESS";
            case 1000297000: return "VK_PIPELINE_COMPILE_REQUIRED";
            case -1000000000: return "VK_ERROR_SURFACE_LOST_KHR";
            case -1000000001: return "VK_ERROR_NATIVE_WINDOW_IN_USE_KHR";
            case 1000001003: return "VK_SUBOPTIMAL_KHR";
            case -1000001004: return "VK_ERROR_OUT_OF_DATE_KHR";
            case -1000003001: return "VK_ERROR_INCOMPATIBLE_DISPLAY_KHR";
            case -1000011001: return "VK_ERROR_VALIDATION_FAILED_EXT";
            case -1000012000: return "VK_ERROR_INVALID_SHADER_NV";
            case -1000023000: return "VK_ERROR_IMAGE_USAGE_NOT_SUPPORTED_KHR";
            case -1000023001: return "VK_ERROR_VIDEO_PICTURE_LAYOUT_NOT_SUPPORTED_KHR";
            case -1000255000: return "VK_ERROR_FULL_SCREEN_EXCLUSIVE_MODE_LOST_EXT";
            case -1000338000: return "VK_ERROR_COMPRESSION_EXHAUSTED_EXT";
            default: return "VK_RESULT_" + result;
        }
    }

    /** Human name of a VkPhysicalDeviceDriverProperties.driverID value. */
    static String driverId(int driverId) {
        switch (driverId) {
            case 1: return "VK_DRIVER_ID_AMD_PROPRIETARY";
            case 2: return "VK_DRIVER_ID_AMD_OPEN_SOURCE";
            case 3: return "VK_DRIVER_ID_MESA_RADV";
            case 4: return "VK_DRIVER_ID_NVIDIA_PROPRIETARY";
            case 5: return "VK_DRIVER_ID_INTEL_PROPRIETARY_WINDOWS";
            case 6: return "VK_DRIVER_ID_INTEL_OPEN_SOURCE_MESA";
            case 7: return "VK_DRIVER_ID_IMAGINATION_PROPRIETARY";
            case 8: return "VK_DRIVER_ID_QUALCOMM_PROPRIETARY";
            case 9: return "VK_DRIVER_ID_ARM_PROPRIETARY";
            case 10: return "VK_DRIVER_ID_GOOGLE_SWIFTSHADER";
            case 11: return "VK_DRIVER_ID_GGP_PROPRIETARY";
            case 12: return "VK_DRIVER_ID_BROADCOM_PROPRIETARY";
            case 13: return "VK_DRIVER_ID_MESA_LLVMPIPE";
            case 14: return "VK_DRIVER_ID_MOLTENVK";
            case 15: return "VK_DRIVER_ID_COREAVI_PROPRIETARY";
            case 16: return "VK_DRIVER_ID_JUICE_PROPRIETARY";
            case 17: return "VK_DRIVER_ID_VERISILICON_PROPRIETARY";
            case DRIVER_ID_MESA_TURNIP: return "VK_DRIVER_ID_MESA_TURNIP";
            case 19: return "VK_DRIVER_ID_MESA_V3DV";
            case 20: return "VK_DRIVER_ID_MESA_PANVK";
            case 21: return "VK_DRIVER_ID_SAMSUNG_PROPRIETARY";
            case 22: return "VK_DRIVER_ID_MESA_VENUS";
            case 23: return "VK_DRIVER_ID_MESA_DOZEN";
            case 24: return "VK_DRIVER_ID_MESA_NVK";
            case 25: return "VK_DRIVER_ID_IMAGINATION_OPEN_SOURCE_MESA";
            case 26: return "VK_DRIVER_ID_MESA_HONEYKRISP";
            default: return "VK_DRIVER_ID_" + driverId;
        }
    }
}
