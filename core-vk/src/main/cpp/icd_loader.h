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
 * @param libraryDirectory directory the package was unpacked into
 * @param libraryName file name of the ICD inside it
 * @param temporaryDirectory writable scratch the hook may use
 */
IcdLoadResult openPackagedDriver(const std::string& libraryDirectory,
                                 const std::string& libraryName,
                                 const std::string& temporaryDirectory);

void closeIcd(IcdHandle& handle);

}  // namespace amaral
