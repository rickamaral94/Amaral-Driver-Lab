#pragma once

#include <cstdint>
#include <string>
#include <vector>

#include "vk_context.h"

namespace amaral {

/** An image plus the memory and view that go with it. */
struct GpuImage {
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkImageView view = VK_NULL_HANDLE;
    VkFormat format = VK_FORMAT_UNDEFINED;
    VkSampleCountFlagBits samples = VK_SAMPLE_COUNT_1_BIT;
    VkDeviceSize allocatedBytes = 0;
};

/** Host-visible staging buffer used to read a finished frame back for hashing. */
struct HostBuffer {
    VkBuffer buffer = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    void* mapped = nullptr;
    VkDeviceSize size = 0;
};

bool createImage(VulkanContext& context,
                 uint32_t width,
                 uint32_t height,
                 VkFormat format,
                 VkSampleCountFlagBits samples,
                 VkImageUsageFlags usage,
                 VkImageAspectFlags aspect,
                 GpuImage* out,
                 std::string* error);

void destroyImage(VulkanContext& context, GpuImage& image);

bool createHostBuffer(VulkanContext& context, VkDeviceSize size, HostBuffer* out, std::string* error);

void destroyHostBuffer(VulkanContext& context, HostBuffer& buffer);

bool createShaderModule(VulkanContext& context,
                        const uint32_t* code,
                        size_t byteLength,
                        VkShaderModule* out,
                        std::string* error);

/**
 * Highest sample count the device supports for both colour and depth, capped at
 * the requested value. Workload 2 asks for 4x; a device that cannot do 4x runs at
 * what it can and records the number, because the two are not comparable.
 */
VkSampleCountFlagBits pickSampleCount(const VulkanContext& context, VkSampleCountFlagBits wanted);

const char* describeVkResult(VkResult result);

}  // namespace amaral
