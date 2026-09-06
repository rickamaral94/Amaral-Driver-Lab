#pragma once

#include <cstdint>
#include <string>
#include <vector>

#include "icd_loader.h"
#include "vk_api.h"

namespace amaral {

/**
 * Everything section 5 requires the app to record before a single frame is timed.
 * Collected from the ICD that is actually in the process, never from meta.json.
 */
struct DriverIdentity {
    uint32_t driverId = 0;
    std::string driverName;
    std::string driverInfo;
    bool hasConformanceVersion = false;
    uint32_t conformanceMajor = 0;
    uint32_t conformanceMinor = 0;
    uint32_t conformanceSubminor = 0;
    uint32_t conformancePatch = 0;

    std::string deviceName;
    uint32_t vendorId = 0;
    uint32_t deviceId = 0;
    uint32_t apiVersion = 0;
    uint32_t driverVersion = 0;

    std::vector<std::string> instanceExtensions;
    std::vector<std::string> deviceExtensions;

    // Limits the workloads and the report actually reference.
    float timestampPeriod = 0.0f;
    uint32_t timestampValidBits = 0;
    uint32_t maxImageDimension2D = 0;
    uint32_t maxColorAttachments = 0;
    VkSampleCountFlags framebufferColorSampleCounts = 0;
    VkSampleCountFlags framebufferDepthSampleCounts = 0;
    uint64_t deviceLocalHeapBytes = 0;
};

struct VulkanContext {
    VulkanApi api;
    IcdHandle icd;

    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    uint32_t queueFamilyIndex = 0;

    VkPhysicalDeviceMemoryProperties memoryProperties{};
    DriverIdentity identity;
    bool validationEnabled = false;

    ~VulkanContext();

    /** Finds a memory type satisfying both a requirement mask and the desired properties. */
    bool findMemoryType(uint32_t typeBits, VkMemoryPropertyFlags properties, uint32_t* outIndex) const;

    /** First depth/stencil format from the candidates the device supports as an attachment. */
    VkFormat pickDepthStencilFormat() const;
};

struct ContextResult {
    bool ok = false;
    LoadStage stage = LoadStage::InstanceCreationFailed;
    std::string message;
    VkResult vulkanResult = VK_SUCCESS;
};

/**
 * Brings up a full instance and device on an already-opened ICD, and collects the
 * identity. Timing never starts until this has succeeded.
 */
ContextResult createContext(VulkanContext& context, IcdHandle handle, bool enableValidation);

/** Serialises the identity for the JNI boundary; the Kotlin side owns the schema. */
std::string identityToJson(const VulkanContext& context);

}  // namespace amaral
