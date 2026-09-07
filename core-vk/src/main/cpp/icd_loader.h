#pragma once

#include <string>

#include "vk_api.h"

namespace amaral {

enum class LoadStage {
    HookUnavailable,
    DlopenFailed,
    InstanceCreationFailed,
    NoPhysicalDevice,
    DeviceCreationFailed,
    IdentityUnavailable,
};

const char* describe(LoadStage stage);

struct IcdHandle {
    void* library = nullptr;
    PFN_vkGetInstanceProcAddr getInstanceProcAddr = nullptr;
    bool usedAdrenotools = false;

    bool valid() const { return getInstanceProcAddr != nullptr; }
};

struct IcdLoadResult {
    IcdHandle handle;
    bool ok = false;
    LoadStage stage = LoadStage::DlopenFailed;
    std::string message;
};

/** Opens Android's own Vulkan loader. Always available; never rankable. */
IcdLoadResult openSystemLoader();

/**
 * Opens an imported driver package without root.
 *
 * @param libraryDirectory directory the package was unpacked into. Must be on internal
 *   storage: dlopen refuses a library any other app could have tampered with.
 * @param libraryName file name of the ICD inside it
 * @param nativeLibraryDirectory the app's own nativeLibraryDir, where the hook libraries
 *   shipped in the APK were extracted. The hook is dlopened out of a linker namespace
 *   created over this path, so anything else silently disables it.
 */
IcdLoadResult openPackagedDriver(const std::string& libraryDirectory,
                                 const std::string& libraryName,
                                 const std::string& nativeLibraryDirectory);

void closeIcd(IcdHandle& handle);

}  // namespace amaral
