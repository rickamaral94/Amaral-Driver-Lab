#include "vk_api.h"

namespace amaral {

bool VulkanApi::loadGlobal(PFN_vkGetInstanceProcAddr proc) {
    if (proc == nullptr) {
        missingFunction = "vkGetInstanceProcAddr";
        return false;
    }
    getInstanceProcAddr = proc;

    enumerateInstanceVersion = reinterpret_cast<PFN_vkEnumerateInstanceVersion>(
            proc(VK_NULL_HANDLE, "vkEnumerateInstanceVersion"));

#define AMARAL_LOAD(name)                                                     \
    name = reinterpret_cast<PFN_##name>(proc(VK_NULL_HANDLE, #name));         \
    if (name == nullptr) {                                                    \
        missingFunction = #name;                                              \
        return false;                                                         \
    }
    AMARAL_GLOBAL_FUNCTIONS(AMARAL_LOAD)
#undef AMARAL_LOAD
    return true;
}

bool VulkanApi::loadInstance(VkInstance instance) {
#define AMARAL_LOAD(name)                                                              \
    name = reinterpret_cast<PFN_##name>(getInstanceProcAddr(instance, #name));         \
    if (name == nullptr) {                                                             \
        missingFunction = #name;                                                       \
        return false;                                                                  \
    }
    AMARAL_INSTANCE_FUNCTIONS(AMARAL_LOAD)
#undef AMARAL_LOAD
    return true;
}

bool VulkanApi::loadDevice(VkDevice device) {
    // Device-level functions go through vkGetDeviceProcAddr so calls skip the
    // loader's dispatch trampoline. On a benchmark that submits thousands of
    // command buffers this is the difference between measuring the driver and
    // measuring the loader.
#define AMARAL_LOAD(name)                                                          \
    name = reinterpret_cast<PFN_##name>(vkGetDeviceProcAddr(device, #name));       \
    if (name == nullptr) {                                                         \
        missingFunction = #name;                                                   \
        return false;                                                              \
    }
    AMARAL_DEVICE_FUNCTIONS(AMARAL_LOAD)
#undef AMARAL_LOAD
    return true;
}

}  // namespace amaral
