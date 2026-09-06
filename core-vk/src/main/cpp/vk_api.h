#pragma once

#include <vulkan/vulkan.h>

#include <string>

// The ICD under test is opened at runtime, so nothing here may be linked against
// libvulkan directly: every entry point is resolved from the vkGetInstanceProcAddr
// belonging to the loader that was actually opened. Linking the system loader in
// would be exactly the bug section 5 of the spec is about — the app would measure
// whatever Android shipped while reporting the package the user selected.

#define AMARAL_GLOBAL_FUNCTIONS(X)                 \
    X(vkEnumerateInstanceExtensionProperties)      \
    X(vkEnumerateInstanceLayerProperties)          \
    X(vkCreateInstance)

#define AMARAL_INSTANCE_FUNCTIONS(X)               \
    X(vkDestroyInstance)                           \
    X(vkEnumeratePhysicalDevices)                  \
    X(vkGetPhysicalDeviceProperties)               \
    X(vkGetPhysicalDeviceProperties2)              \
    X(vkGetPhysicalDeviceFeatures2)                \
    X(vkGetPhysicalDeviceMemoryProperties)         \
    X(vkGetPhysicalDeviceQueueFamilyProperties)    \
    X(vkGetPhysicalDeviceFormatProperties)         \
    X(vkEnumerateDeviceExtensionProperties)        \
    X(vkCreateDevice)                              \
    X(vkGetDeviceProcAddr)

#define AMARAL_DEVICE_FUNCTIONS(X)                 \
    X(vkDestroyDevice)                             \
    X(vkGetDeviceQueue)                            \
    X(vkDeviceWaitIdle)                            \
    X(vkQueueSubmit)                               \
    X(vkQueueWaitIdle)                             \
    X(vkCreateCommandPool)                         \
    X(vkDestroyCommandPool)                        \
    X(vkResetCommandPool)                          \
    X(vkAllocateCommandBuffers)                    \
    X(vkFreeCommandBuffers)                        \
    X(vkBeginCommandBuffer)                        \
    X(vkEndCommandBuffer)                          \
    X(vkCreateImage)                               \
    X(vkDestroyImage)                              \
    X(vkCreateImageView)                           \
    X(vkDestroyImageView)                          \
    X(vkGetImageMemoryRequirements)                \
    X(vkBindImageMemory)                           \
    X(vkCreateBuffer)                              \
    X(vkDestroyBuffer)                             \
    X(vkGetBufferMemoryRequirements)               \
    X(vkBindBufferMemory)                          \
    X(vkAllocateMemory)                            \
    X(vkFreeMemory)                                \
    X(vkMapMemory)                                 \
    X(vkUnmapMemory)                               \
    X(vkInvalidateMappedMemoryRanges)              \
    X(vkCreateRenderPass)                          \
    X(vkDestroyRenderPass)                         \
    X(vkCreateFramebuffer)                         \
    X(vkDestroyFramebuffer)                        \
    X(vkCreateShaderModule)                        \
    X(vkDestroyShaderModule)                       \
    X(vkCreatePipelineLayout)                      \
    X(vkDestroyPipelineLayout)                     \
    X(vkCreateGraphicsPipelines)                   \
    X(vkDestroyPipeline)                           \
    X(vkCreatePipelineCache)                       \
    X(vkDestroyPipelineCache)                      \
    X(vkGetPipelineCacheData)                      \
    X(vkCreateDescriptorSetLayout)                 \
    X(vkDestroyDescriptorSetLayout)                \
    X(vkCreateDescriptorPool)                      \
    X(vkDestroyDescriptorPool)                     \
    X(vkAllocateDescriptorSets)                    \
    X(vkUpdateDescriptorSets)                      \
    X(vkCreateQueryPool)                           \
    X(vkDestroyQueryPool)                          \
    X(vkGetQueryPoolResults)                       \
    X(vkCreateFence)                               \
    X(vkDestroyFence)                              \
    X(vkWaitForFences)                             \
    X(vkResetFences)                               \
    X(vkCmdBeginRenderPass)                        \
    X(vkCmdNextSubpass)                            \
    X(vkCmdEndRenderPass)                          \
    X(vkCmdBindPipeline)                           \
    X(vkCmdBindDescriptorSets)                     \
    X(vkCmdDraw)                                   \
    X(vkCmdPushConstants)                          \
    X(vkCmdSetViewport)                            \
    X(vkCmdSetScissor)                             \
    X(vkCmdResetQueryPool)                         \
    X(vkCmdWriteTimestamp)                         \
    X(vkCmdPipelineBarrier)                        \
    X(vkCmdCopyImageToBuffer)

namespace amaral {

struct VulkanApi {
    PFN_vkGetInstanceProcAddr getInstanceProcAddr = nullptr;

    // Optional: absent on a 1.0 loader, which is not fatal — the instance is then
    // created at API 1.0 and the device is rejected later for being below 1.1.
    PFN_vkEnumerateInstanceVersion enumerateInstanceVersion = nullptr;

#define AMARAL_DECLARE(name) PFN_##name name = nullptr;
    AMARAL_GLOBAL_FUNCTIONS(AMARAL_DECLARE)
    AMARAL_INSTANCE_FUNCTIONS(AMARAL_DECLARE)
    AMARAL_DEVICE_FUNCTIONS(AMARAL_DECLARE)
#undef AMARAL_DECLARE

    // Name of the first entry point that failed to resolve, for the failure catalog.
    std::string missingFunction;

    bool loadGlobal(PFN_vkGetInstanceProcAddr proc);
    bool loadInstance(VkInstance instance);
    bool loadDevice(VkDevice device);
};

}  // namespace amaral
