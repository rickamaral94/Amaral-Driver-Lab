#include "offscreen_target.h"

namespace amaral {

const char* describeVkResult(VkResult result) {
    switch (result) {
        case VK_SUCCESS: return "VK_SUCCESS";
        case VK_NOT_READY: return "VK_NOT_READY";
        case VK_TIMEOUT: return "VK_TIMEOUT";
        case VK_INCOMPLETE: return "VK_INCOMPLETE";
        case VK_ERROR_OUT_OF_HOST_MEMORY: return "VK_ERROR_OUT_OF_HOST_MEMORY";
        case VK_ERROR_OUT_OF_DEVICE_MEMORY: return "VK_ERROR_OUT_OF_DEVICE_MEMORY";
        case VK_ERROR_INITIALIZATION_FAILED: return "VK_ERROR_INITIALIZATION_FAILED";
        case VK_ERROR_DEVICE_LOST: return "VK_ERROR_DEVICE_LOST";
        case VK_ERROR_MEMORY_MAP_FAILED: return "VK_ERROR_MEMORY_MAP_FAILED";
        case VK_ERROR_LAYER_NOT_PRESENT: return "VK_ERROR_LAYER_NOT_PRESENT";
        case VK_ERROR_EXTENSION_NOT_PRESENT: return "VK_ERROR_EXTENSION_NOT_PRESENT";
        case VK_ERROR_FEATURE_NOT_PRESENT: return "VK_ERROR_FEATURE_NOT_PRESENT";
        case VK_ERROR_INCOMPATIBLE_DRIVER: return "VK_ERROR_INCOMPATIBLE_DRIVER";
        case VK_ERROR_TOO_MANY_OBJECTS: return "VK_ERROR_TOO_MANY_OBJECTS";
        case VK_ERROR_FORMAT_NOT_SUPPORTED: return "VK_ERROR_FORMAT_NOT_SUPPORTED";
        case VK_ERROR_FRAGMENTED_POOL: return "VK_ERROR_FRAGMENTED_POOL";
        case VK_ERROR_UNKNOWN: return "VK_ERROR_UNKNOWN";
        case VK_ERROR_OUT_OF_POOL_MEMORY: return "VK_ERROR_OUT_OF_POOL_MEMORY";
        default: return "VK_ERROR_UNMAPPED";
    }
}

bool createImage(VulkanContext& context,
                 uint32_t width,
                 uint32_t height,
                 VkFormat format,
                 VkSampleCountFlagBits samples,
                 VkImageUsageFlags usage,
                 VkImageAspectFlags aspect,
                 GpuImage* out,
                 std::string* error) {
    VkImageCreateInfo imageInfo{};
    imageInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    imageInfo.imageType = VK_IMAGE_TYPE_2D;
    imageInfo.format = format;
    imageInfo.extent = {width, height, 1};
    imageInfo.mipLevels = 1;
    imageInfo.arrayLayers = 1;
    imageInfo.samples = samples;
    imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imageInfo.usage = usage;
    imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;

    VkResult result = context.api.vkCreateImage(context.device, &imageInfo, nullptr, &out->image);
    if (result != VK_SUCCESS) {
        *error = std::string("vkCreateImage: ") + describeVkResult(result);
        return false;
    }

    VkMemoryRequirements requirements{};
    context.api.vkGetImageMemoryRequirements(context.device, out->image, &requirements);

    uint32_t memoryType = 0;
    if (!context.findMemoryType(requirements.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                                &memoryType)) {
        *error = "no device-local memory type for the render target";
        return false;
    }

    VkMemoryAllocateInfo allocateInfo{};
    allocateInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocateInfo.allocationSize = requirements.size;
    allocateInfo.memoryTypeIndex = memoryType;

    result = context.api.vkAllocateMemory(context.device, &allocateInfo, nullptr, &out->memory);
    if (result != VK_SUCCESS) {
        *error = std::string("vkAllocateMemory: ") + describeVkResult(result);
        return false;
    }
    result = context.api.vkBindImageMemory(context.device, out->image, out->memory, 0);
    if (result != VK_SUCCESS) {
        *error = std::string("vkBindImageMemory: ") + describeVkResult(result);
        return false;
    }

    VkImageViewCreateInfo viewInfo{};
    viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    viewInfo.image = out->image;
    viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
    viewInfo.format = format;
    viewInfo.subresourceRange.aspectMask = aspect;
    viewInfo.subresourceRange.levelCount = 1;
    viewInfo.subresourceRange.layerCount = 1;

    result = context.api.vkCreateImageView(context.device, &viewInfo, nullptr, &out->view);
    if (result != VK_SUCCESS) {
        *error = std::string("vkCreateImageView: ") + describeVkResult(result);
        return false;
    }

    out->format = format;
    out->samples = samples;
    out->allocatedBytes = requirements.size;
    return true;
}

void destroyImage(VulkanContext& context, GpuImage& image) {
    if (image.view != VK_NULL_HANDLE) {
        context.api.vkDestroyImageView(context.device, image.view, nullptr);
        image.view = VK_NULL_HANDLE;
    }
    if (image.image != VK_NULL_HANDLE) {
        context.api.vkDestroyImage(context.device, image.image, nullptr);
        image.image = VK_NULL_HANDLE;
    }
    if (image.memory != VK_NULL_HANDLE) {
        context.api.vkFreeMemory(context.device, image.memory, nullptr);
        image.memory = VK_NULL_HANDLE;
    }
}

bool createHostBuffer(VulkanContext& context,
                      VkDeviceSize size,
                      HostBuffer* out,
                      std::string* error) {
    VkBufferCreateInfo bufferInfo{};
    bufferInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bufferInfo.size = size;
    bufferInfo.usage = VK_BUFFER_USAGE_TRANSFER_DST_BIT;
    bufferInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;

    VkResult result = context.api.vkCreateBuffer(context.device, &bufferInfo, nullptr, &out->buffer);
    if (result != VK_SUCCESS) {
        *error = std::string("vkCreateBuffer: ") + describeVkResult(result);
        return false;
    }

    VkMemoryRequirements requirements{};
    context.api.vkGetBufferMemoryRequirements(context.device, out->buffer, &requirements);

    uint32_t memoryType = 0;
    // Coherent memory keeps the readback path short; without it the invalidate
    // below is what makes the mapped bytes valid, so both are handled.
    const VkMemoryPropertyFlags wanted =
            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
    if (!context.findMemoryType(requirements.memoryTypeBits, wanted, &memoryType) &&
        !context.findMemoryType(requirements.memoryTypeBits, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT,
                                &memoryType)) {
        *error = "no host-visible memory type for readback";
        return false;
    }

    VkMemoryAllocateInfo allocateInfo{};
    allocateInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocateInfo.allocationSize = requirements.size;
    allocateInfo.memoryTypeIndex = memoryType;

    result = context.api.vkAllocateMemory(context.device, &allocateInfo, nullptr, &out->memory);
    if (result != VK_SUCCESS) {
        *error = std::string("vkAllocateMemory (readback): ") + describeVkResult(result);
        return false;
    }
    result = context.api.vkBindBufferMemory(context.device, out->buffer, out->memory, 0);
    if (result != VK_SUCCESS) {
        *error = std::string("vkBindBufferMemory: ") + describeVkResult(result);
        return false;
    }
    result = context.api.vkMapMemory(context.device, out->memory, 0, VK_WHOLE_SIZE, 0, &out->mapped);
    if (result != VK_SUCCESS) {
        *error = std::string("vkMapMemory: ") + describeVkResult(result);
        return false;
    }
    out->size = size;
    return true;
}

void destroyHostBuffer(VulkanContext& context, HostBuffer& buffer) {
    if (buffer.mapped != nullptr) {
        context.api.vkUnmapMemory(context.device, buffer.memory);
        buffer.mapped = nullptr;
    }
    if (buffer.buffer != VK_NULL_HANDLE) {
        context.api.vkDestroyBuffer(context.device, buffer.buffer, nullptr);
        buffer.buffer = VK_NULL_HANDLE;
    }
    if (buffer.memory != VK_NULL_HANDLE) {
        context.api.vkFreeMemory(context.device, buffer.memory, nullptr);
        buffer.memory = VK_NULL_HANDLE;
    }
}

bool createShaderModule(VulkanContext& context,
                        const uint32_t* code,
                        size_t byteLength,
                        VkShaderModule* out,
                        std::string* error) {
    VkShaderModuleCreateInfo info{};
    info.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    info.codeSize = byteLength;
    info.pCode = code;
    VkResult result = context.api.vkCreateShaderModule(context.device, &info, nullptr, out);
    if (result != VK_SUCCESS) {
        *error = std::string("vkCreateShaderModule: ") + describeVkResult(result);
        return false;
    }
    return true;
}

VkSampleCountFlagBits pickSampleCount(const VulkanContext& context, VkSampleCountFlagBits wanted) {
    const VkSampleCountFlags supported = context.identity.framebufferColorSampleCounts &
                                         context.identity.framebufferDepthSampleCounts;
    for (VkSampleCountFlagBits candidate :
         {VK_SAMPLE_COUNT_8_BIT, VK_SAMPLE_COUNT_4_BIT, VK_SAMPLE_COUNT_2_BIT}) {
        if (candidate > wanted) continue;
        if (supported & candidate) return candidate;
    }
    return VK_SAMPLE_COUNT_1_BIT;
}

}  // namespace amaral
