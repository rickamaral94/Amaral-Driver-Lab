#include "workloads.h"

#include <ctime>
#include <sstream>

#include "offscreen_target.h"
#include "sha256.h"

#include "baseline_vert.h"
#include "baseline_frag.h"
#include "tiling_geometry_vert.h"
#include "tiling_geometry_frag.h"
#include "fullscreen_vert.h"
#include "tiling_resolve_frag.h"

namespace amaral {

const char* const kWorkloadBaseline = "baseline/v1";
const char* const kWorkloadTilingGmem = "tiling_gmem/v1";

namespace {

constexpr VkFormat kColorFormat = VK_FORMAT_R8G8B8A8_UNORM;
constexpr uint64_t kFenceTimeoutNs = 10ull * 1000ull * 1000ull * 1000ull;  // 10 s

struct PushConstants {
    uint32_t frameIndex;
    uint32_t drawIndex;
};

int64_t monotonicNanos() {
    timespec now{};
    clock_gettime(CLOCK_MONOTONIC, &now);
    return static_cast<int64_t>(now.tv_sec) * 1000000000ll + now.tv_nsec;
}

WorkloadOutcome fail(std::string stage, std::string message) {
    WorkloadOutcome outcome;
    outcome.ok = false;
    outcome.errorStage = std::move(stage);
    outcome.error = std::move(message);
    return outcome;
}

/** Every Vulkan object one workload needs, released in reverse on the way out. */
struct Resources {
    VulkanContext* context = nullptr;

    GpuImage msaaColor;
    GpuImage msaaDepth;
    GpuImage resolvedColor;
    GpuImage finalColor;
    HostBuffer readback;

    VkRenderPass renderPass = VK_NULL_HANDLE;
    VkFramebuffer framebuffer = VK_NULL_HANDLE;
    VkDescriptorSetLayout descriptorSetLayout = VK_NULL_HANDLE;
    VkDescriptorPool descriptorPool = VK_NULL_HANDLE;
    VkDescriptorSet descriptorSet = VK_NULL_HANDLE;
    VkPipelineLayout pipelineLayout = VK_NULL_HANDLE;
    VkPipeline geometryPipeline = VK_NULL_HANDLE;
    VkPipeline resolvePipeline = VK_NULL_HANDLE;
    VkPipelineCache pipelineCache = VK_NULL_HANDLE;
    VkCommandPool commandPool = VK_NULL_HANDLE;
    VkCommandBuffer commandBuffer = VK_NULL_HANDLE;
    VkFence fence = VK_NULL_HANDLE;
    VkQueryPool queryPool = VK_NULL_HANDLE;

    ~Resources() {
        if (context == nullptr || context->device == VK_NULL_HANDLE) return;
        VulkanApi& api = context->api;
        api.vkDeviceWaitIdle(context->device);

        if (queryPool != VK_NULL_HANDLE) api.vkDestroyQueryPool(context->device, queryPool, nullptr);
        if (fence != VK_NULL_HANDLE) api.vkDestroyFence(context->device, fence, nullptr);
        if (commandPool != VK_NULL_HANDLE) {
            api.vkDestroyCommandPool(context->device, commandPool, nullptr);
        }
        if (geometryPipeline != VK_NULL_HANDLE) {
            api.vkDestroyPipeline(context->device, geometryPipeline, nullptr);
        }
        if (resolvePipeline != VK_NULL_HANDLE) {
            api.vkDestroyPipeline(context->device, resolvePipeline, nullptr);
        }
        if (pipelineCache != VK_NULL_HANDLE) {
            api.vkDestroyPipelineCache(context->device, pipelineCache, nullptr);
        }
        if (pipelineLayout != VK_NULL_HANDLE) {
            api.vkDestroyPipelineLayout(context->device, pipelineLayout, nullptr);
        }
        if (descriptorPool != VK_NULL_HANDLE) {
            api.vkDestroyDescriptorPool(context->device, descriptorPool, nullptr);
        }
        if (descriptorSetLayout != VK_NULL_HANDLE) {
            api.vkDestroyDescriptorSetLayout(context->device, descriptorSetLayout, nullptr);
        }
        if (framebuffer != VK_NULL_HANDLE) {
            api.vkDestroyFramebuffer(context->device, framebuffer, nullptr);
        }
        if (renderPass != VK_NULL_HANDLE) {
            api.vkDestroyRenderPass(context->device, renderPass, nullptr);
        }
        destroyHostBuffer(*context, readback);
        destroyImage(*context, finalColor);
        destroyImage(*context, resolvedColor);
        destroyImage(*context, msaaDepth);
        destroyImage(*context, msaaColor);
    }
};

/** Holds a shader module for exactly as long as pipeline creation needs it. */
struct ScopedShaderModule {
    VulkanContext* context;
    VkShaderModule module = VK_NULL_HANDLE;

    explicit ScopedShaderModule(VulkanContext* ctx) : context(ctx) {}
    ~ScopedShaderModule() {
        if (module != VK_NULL_HANDLE) {
            context->api.vkDestroyShaderModule(context->device, module, nullptr);
        }
    }
    ScopedShaderModule(const ScopedShaderModule&) = delete;
    ScopedShaderModule& operator=(const ScopedShaderModule&) = delete;
};

VkPipelineShaderStageCreateInfo stageOf(VkShaderStageFlagBits stage, VkShaderModule module) {
    VkPipelineShaderStageCreateInfo info{};
    info.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    info.stage = stage;
    info.module = module;
    info.pName = "main";
    return info;
}

/**
 * Baseline: one subpass, one single-sampled colour attachment, no depth.
 * The whole point is that almost nothing happens per fragment, so what is left
 * is the cost of getting a frame into the queue at all.
 */
bool buildBaselineRenderPass(VulkanContext& context, Resources& resources, std::string* error) {
    VkAttachmentDescription colour{};
    colour.format = kColorFormat;
    colour.samples = VK_SAMPLE_COUNT_1_BIT;
    colour.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
    colour.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
    colour.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
    colour.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
    colour.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    colour.finalLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;

    VkAttachmentReference colourRef{};
    colourRef.attachment = 0;
    colourRef.layout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;

    VkSubpassDescription subpass{};
    subpass.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
    subpass.colorAttachmentCount = 1;
    subpass.pColorAttachments = &colourRef;

    // Makes the colour writes visible to the transfer that reads the frame back.
    VkSubpassDependency outgoing{};
    outgoing.srcSubpass = 0;
    outgoing.dstSubpass = VK_SUBPASS_EXTERNAL;
    outgoing.srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    outgoing.dstStageMask = VK_PIPELINE_STAGE_TRANSFER_BIT;
    outgoing.srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
    outgoing.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;

    VkRenderPassCreateInfo info{};
    info.sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO;
    info.attachmentCount = 1;
    info.pAttachments = &colour;
    info.subpassCount = 1;
    info.pSubpasses = &subpass;
    info.dependencyCount = 1;
    info.pDependencies = &outgoing;

    VkResult result =
            context.api.vkCreateRenderPass(context.device, &info, nullptr, &resources.renderPass);
    if (result != VK_SUCCESS) {
        *error = std::string("vkCreateRenderPass (baseline): ") + describeVkResult(result);
        return false;
    }
    return true;
}

/**
 * Tiling/GMEM: two subpasses over a multisampled colour target and a packed
 * depth/stencil target, resolved inside the render pass and read back as an input
 * attachment by the second subpass.
 *
 * Every part of that shape is a decision the driver has to make about what stays
 * in on-chip memory. The multisampled attachments are DONT_CARE on store, so a
 * tiler is free to never write them out — and a driver that gets binning wrong
 * pays for a full round trip through system memory, which is what this measures.
 */
bool buildTilingRenderPass(VulkanContext& context,
                           Resources& resources,
                           VkSampleCountFlagBits samples,
                           VkFormat depthFormat,
                           std::string* error) {
    VkAttachmentDescription attachments[4]{};

    // 0: multisampled colour, resolved away and never stored.
    attachments[0].format = kColorFormat;
    attachments[0].samples = samples;
    attachments[0].loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
    attachments[0].storeOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
    attachments[0].stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
    attachments[0].stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
    attachments[0].initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    attachments[0].finalLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;

    // 1: multisampled depth/stencil, likewise transient.
    attachments[1].format = depthFormat;
    attachments[1].samples = samples;
    attachments[1].loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
    attachments[1].storeOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
    attachments[1].stencilLoadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
    attachments[1].stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
    attachments[1].initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    attachments[1].finalLayout = VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL;

    // 2: resolve target for subpass 0, input attachment for subpass 1.
    attachments[2].format = kColorFormat;
    attachments[2].samples = VK_SAMPLE_COUNT_1_BIT;
    attachments[2].loadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
    attachments[2].storeOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
    attachments[2].stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
    attachments[2].stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
    attachments[2].initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    attachments[2].finalLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;

    // 3: the frame that is kept, hashed and compared.
    attachments[3].format = kColorFormat;
    attachments[3].samples = VK_SAMPLE_COUNT_1_BIT;
    attachments[3].loadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
    attachments[3].storeOp = VK_ATTACHMENT_STORE_OP_STORE;
    attachments[3].stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
    attachments[3].stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
    attachments[3].initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    attachments[3].finalLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;

    VkAttachmentReference msaaColourRef{0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
    VkAttachmentReference depthRef{1, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL};
    VkAttachmentReference resolveRef{2, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
    VkAttachmentReference inputRef{2, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
    VkAttachmentReference finalRef{3, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};

    VkSubpassDescription subpasses[2]{};
    subpasses[0].pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
    subpasses[0].colorAttachmentCount = 1;
    subpasses[0].pColorAttachments = &msaaColourRef;
    subpasses[0].pResolveAttachments = &resolveRef;
    subpasses[0].pDepthStencilAttachment = &depthRef;

    subpasses[1].pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
    subpasses[1].inputAttachmentCount = 1;
    subpasses[1].pInputAttachments = &inputRef;
    subpasses[1].colorAttachmentCount = 1;
    subpasses[1].pColorAttachments = &finalRef;

    VkSubpassDependency dependencies[2]{};
    // BY_REGION is the whole point: it tells the driver each tile's second subpass
    // only needs its own tile from the first, which is what keeps the data on chip.
    dependencies[0].srcSubpass = 0;
    dependencies[0].dstSubpass = 1;
    dependencies[0].srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    dependencies[0].dstStageMask = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
    dependencies[0].srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
    dependencies[0].dstAccessMask = VK_ACCESS_INPUT_ATTACHMENT_READ_BIT;
    dependencies[0].dependencyFlags = VK_DEPENDENCY_BY_REGION_BIT;

    dependencies[1].srcSubpass = 1;
    dependencies[1].dstSubpass = VK_SUBPASS_EXTERNAL;
    dependencies[1].srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    dependencies[1].dstStageMask = VK_PIPELINE_STAGE_TRANSFER_BIT;
    dependencies[1].srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
    dependencies[1].dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;

    VkRenderPassCreateInfo info{};
    info.sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO;
    info.attachmentCount = 4;
    info.pAttachments = attachments;
    info.subpassCount = 2;
    info.pSubpasses = subpasses;
    info.dependencyCount = 2;
    info.pDependencies = dependencies;

    VkResult result =
            context.api.vkCreateRenderPass(context.device, &info, nullptr, &resources.renderPass);
    if (result != VK_SUCCESS) {
        *error = std::string("vkCreateRenderPass (tiling): ") + describeVkResult(result);
        return false;
    }
    return true;
}

bool buildPipeline(VulkanContext& context,
                   Resources& resources,
                   const uint32_t* vertexCode,
                   size_t vertexBytes,
                   const uint32_t* fragmentCode,
                   size_t fragmentBytes,
                   uint32_t subpass,
                   VkSampleCountFlagBits samples,
                   bool depthEnabled,
                   const WorkloadConfig& config,
                   VkPipeline* outPipeline,
                   std::string* error) {
    ScopedShaderModule vertexModule(&context);
    ScopedShaderModule fragmentModule(&context);
    if (!createShaderModule(context, vertexCode, vertexBytes, &vertexModule.module, error)) return false;
    if (!createShaderModule(context, fragmentCode, fragmentBytes, &fragmentModule.module, error)) {
        return false;
    }

    VkPipelineShaderStageCreateInfo stages[2] = {
            stageOf(VK_SHADER_STAGE_VERTEX_BIT, vertexModule.module),
            stageOf(VK_SHADER_STAGE_FRAGMENT_BIT, fragmentModule.module),
    };

    // No vertex buffers anywhere: geometry is derived from gl_VertexIndex so the
    // scene is bit-identical on every device with no host data to drift.
    VkPipelineVertexInputStateCreateInfo vertexInput{};
    vertexInput.sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;

    VkPipelineInputAssemblyStateCreateInfo inputAssembly{};
    inputAssembly.sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO;
    inputAssembly.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;

    VkViewport viewport{};
    viewport.width = static_cast<float>(config.width);
    viewport.height = static_cast<float>(config.height);
    viewport.maxDepth = 1.0f;

    VkRect2D scissor{};
    scissor.extent = {config.width, config.height};

    VkPipelineViewportStateCreateInfo viewportState{};
    viewportState.sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO;
    viewportState.viewportCount = 1;
    viewportState.pViewports = &viewport;
    viewportState.scissorCount = 1;
    viewportState.pScissors = &scissor;

    VkPipelineRasterizationStateCreateInfo rasterization{};
    rasterization.sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO;
    rasterization.polygonMode = VK_POLYGON_MODE_FILL;
    rasterization.cullMode = VK_CULL_MODE_NONE;
    rasterization.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;
    rasterization.lineWidth = 1.0f;

    VkPipelineMultisampleStateCreateInfo multisample{};
    multisample.sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO;
    multisample.rasterizationSamples = samples;

    VkPipelineDepthStencilStateCreateInfo depthStencil{};
    depthStencil.sType = VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO;
    depthStencil.depthTestEnable = depthEnabled ? VK_TRUE : VK_FALSE;
    depthStencil.depthWriteEnable = depthEnabled ? VK_TRUE : VK_FALSE;
    depthStencil.depthCompareOp = VK_COMPARE_OP_LESS;

    VkPipelineColorBlendAttachmentState blendAttachment{};
    blendAttachment.colorWriteMask = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT |
                                     VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT;
    blendAttachment.blendEnable = VK_FALSE;

    VkPipelineColorBlendStateCreateInfo colorBlend{};
    colorBlend.sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO;
    colorBlend.attachmentCount = 1;
    colorBlend.pAttachments = &blendAttachment;

    VkGraphicsPipelineCreateInfo pipelineInfo{};
    pipelineInfo.sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO;
    pipelineInfo.stageCount = 2;
    pipelineInfo.pStages = stages;
    pipelineInfo.pVertexInputState = &vertexInput;
    pipelineInfo.pInputAssemblyState = &inputAssembly;
    pipelineInfo.pViewportState = &viewportState;
    pipelineInfo.pRasterizationState = &rasterization;
    pipelineInfo.pMultisampleState = &multisample;
    pipelineInfo.pDepthStencilState = &depthStencil;
    pipelineInfo.pColorBlendState = &colorBlend;
    pipelineInfo.layout = resources.pipelineLayout;
    pipelineInfo.renderPass = resources.renderPass;
    pipelineInfo.subpass = subpass;

    VkResult result = context.api.vkCreateGraphicsPipelines(
            context.device, resources.pipelineCache, 1, &pipelineInfo, nullptr, outPipeline);
    if (result != VK_SUCCESS) {
        *error = std::string("vkCreateGraphicsPipelines: ") + describeVkResult(result);
        return false;
    }
    return true;
}

}  // namespace

WorkloadOutcome runWorkload(VulkanContext& context,
                            const std::string& workloadId,
                            const WorkloadConfig& config) {
    const bool tiling = workloadId == kWorkloadTilingGmem;
    if (!tiling && workloadId != kWorkloadBaseline) {
        return fail("SETUP", "unknown workload id \"" + workloadId + "\"");
    }
    if (config.frameCount == 0) {
        return fail("SETUP", "a workload must run at least one timed frame");
    }

    VulkanApi& api = context.api;
    Resources resources;
    resources.context = &context;
    std::string error;

    VkSampleCountFlagBits samples = VK_SAMPLE_COUNT_1_BIT;
    VkFormat depthFormat = VK_FORMAT_UNDEFINED;

    if (tiling) {
        samples = pickSampleCount(context, VK_SAMPLE_COUNT_4_BIT);
        depthFormat = context.pickDepthStencilFormat();
        if (depthFormat == VK_FORMAT_UNDEFINED) {
            return fail("SETUP", "no supported depth/stencil attachment format");
        }
        if (!buildTilingRenderPass(context, resources, samples, depthFormat, &error)) {
            return fail("RENDER_PASS", error);
        }
        if (!createImage(context, config.width, config.height, kColorFormat, samples,
                         VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSIENT_ATTACHMENT_BIT,
                         VK_IMAGE_ASPECT_COLOR_BIT, &resources.msaaColor, &error)) {
            return fail("ATTACHMENTS", error);
        }
        const VkImageAspectFlags depthAspect =
                VK_IMAGE_ASPECT_DEPTH_BIT |
                (depthFormat == VK_FORMAT_D32_SFLOAT ? 0 : VK_IMAGE_ASPECT_STENCIL_BIT);
        if (!createImage(context, config.width, config.height, depthFormat, samples,
                         VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT |
                                 VK_IMAGE_USAGE_TRANSIENT_ATTACHMENT_BIT,
                         depthAspect, &resources.msaaDepth, &error)) {
            return fail("ATTACHMENTS", error);
        }
        if (!createImage(context, config.width, config.height, kColorFormat, VK_SAMPLE_COUNT_1_BIT,
                         VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_INPUT_ATTACHMENT_BIT,
                         VK_IMAGE_ASPECT_COLOR_BIT, &resources.resolvedColor, &error)) {
            return fail("ATTACHMENTS", error);
        }
    } else {
        if (!buildBaselineRenderPass(context, resources, &error)) {
            return fail("RENDER_PASS", error);
        }
    }

    if (!createImage(context, config.width, config.height, kColorFormat, VK_SAMPLE_COUNT_1_BIT,
                     VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT,
                     VK_IMAGE_ASPECT_COLOR_BIT, &resources.finalColor, &error)) {
        return fail("ATTACHMENTS", error);
    }

    // Framebuffer
    {
        VkImageView views[4] = {
                resources.msaaColor.view,
                resources.msaaDepth.view,
                resources.resolvedColor.view,
                resources.finalColor.view,
        };
        VkFramebufferCreateInfo info{};
        info.sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
        info.renderPass = resources.renderPass;
        info.attachmentCount = tiling ? 4 : 1;
        info.pAttachments = tiling ? views : &resources.finalColor.view;
        info.width = config.width;
        info.height = config.height;
        info.layers = 1;
        VkResult result =
                api.vkCreateFramebuffer(context.device, &info, nullptr, &resources.framebuffer);
        if (result != VK_SUCCESS) {
            return fail("FRAMEBUFFER", std::string("vkCreateFramebuffer: ") + describeVkResult(result));
        }
    }

    // Descriptor set for the second subpass's input attachment.
    if (tiling) {
        VkDescriptorSetLayoutBinding binding{};
        binding.binding = 0;
        binding.descriptorType = VK_DESCRIPTOR_TYPE_INPUT_ATTACHMENT;
        binding.descriptorCount = 1;
        binding.stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;

        VkDescriptorSetLayoutCreateInfo layoutInfo{};
        layoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
        layoutInfo.bindingCount = 1;
        layoutInfo.pBindings = &binding;
        VkResult result = api.vkCreateDescriptorSetLayout(context.device, &layoutInfo, nullptr,
                                                          &resources.descriptorSetLayout);
        if (result != VK_SUCCESS) {
            return fail("DESCRIPTORS",
                        std::string("vkCreateDescriptorSetLayout: ") + describeVkResult(result));
        }

        VkDescriptorPoolSize poolSize{VK_DESCRIPTOR_TYPE_INPUT_ATTACHMENT, 1};
        VkDescriptorPoolCreateInfo poolInfo{};
        poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
        poolInfo.maxSets = 1;
        poolInfo.poolSizeCount = 1;
        poolInfo.pPoolSizes = &poolSize;
        result = api.vkCreateDescriptorPool(context.device, &poolInfo, nullptr,
                                            &resources.descriptorPool);
        if (result != VK_SUCCESS) {
            return fail("DESCRIPTORS",
                        std::string("vkCreateDescriptorPool: ") + describeVkResult(result));
        }

        VkDescriptorSetAllocateInfo allocateInfo{};
        allocateInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
        allocateInfo.descriptorPool = resources.descriptorPool;
        allocateInfo.descriptorSetCount = 1;
        allocateInfo.pSetLayouts = &resources.descriptorSetLayout;
        result = api.vkAllocateDescriptorSets(context.device, &allocateInfo, &resources.descriptorSet);
        if (result != VK_SUCCESS) {
            return fail("DESCRIPTORS",
                        std::string("vkAllocateDescriptorSets: ") + describeVkResult(result));
        }

        VkDescriptorImageInfo imageInfo{};
        imageInfo.imageView = resources.resolvedColor.view;
        imageInfo.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;

        VkWriteDescriptorSet write{};
        write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        write.dstSet = resources.descriptorSet;
        write.dstBinding = 0;
        write.descriptorCount = 1;
        write.descriptorType = VK_DESCRIPTOR_TYPE_INPUT_ATTACHMENT;
        write.pImageInfo = &imageInfo;
        api.vkUpdateDescriptorSets(context.device, 1, &write, 0, nullptr);
    }

    // Pipeline layout: one push constant block shared by every shader here.
    {
        VkPushConstantRange range{};
        range.stageFlags = VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT;
        range.offset = 0;
        range.size = sizeof(PushConstants);

        VkPipelineLayoutCreateInfo info{};
        info.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
        info.setLayoutCount = tiling ? 1 : 0;
        info.pSetLayouts = tiling ? &resources.descriptorSetLayout : nullptr;
        info.pushConstantRangeCount = 1;
        info.pPushConstantRanges = &range;
        VkResult result =
                api.vkCreatePipelineLayout(context.device, &info, nullptr, &resources.pipelineLayout);
        if (result != VK_SUCCESS) {
            return fail("PIPELINE",
                        std::string("vkCreatePipelineLayout: ") + describeVkResult(result));
        }
    }

    {
        VkPipelineCacheCreateInfo info{};
        info.sType = VK_STRUCTURE_TYPE_PIPELINE_CACHE_CREATE_INFO;
        // Starts empty on purpose: a warm cache would hide the compile cost that
        // workload 5 is built to measure, and this run must not depend on it.
        api.vkCreatePipelineCache(context.device, &info, nullptr, &resources.pipelineCache);
    }

    if (tiling) {
        if (!buildPipeline(context, resources, tiling_geometry_vert, sizeof(tiling_geometry_vert),
                           tiling_geometry_frag, sizeof(tiling_geometry_frag), 0, samples, true,
                           config, &resources.geometryPipeline, &error)) {
            return fail("PIPELINE", error);
        }
        if (!buildPipeline(context, resources, fullscreen_vert, sizeof(fullscreen_vert),
                           tiling_resolve_frag, sizeof(tiling_resolve_frag), 1,
                           VK_SAMPLE_COUNT_1_BIT, false, config, &resources.resolvePipeline, &error)) {
            return fail("PIPELINE", error);
        }
    } else {
        if (!buildPipeline(context, resources, baseline_vert, sizeof(baseline_vert), baseline_frag,
                           sizeof(baseline_frag), 0, VK_SAMPLE_COUNT_1_BIT, false, config,
                           &resources.geometryPipeline, &error)) {
            return fail("PIPELINE", error);
        }
    }

    // Command pool, command buffer, fence, timestamp queries.
    {
        VkCommandPoolCreateInfo info{};
        info.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
        info.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
        info.queueFamilyIndex = context.queueFamilyIndex;
        VkResult result = api.vkCreateCommandPool(context.device, &info, nullptr, &resources.commandPool);
        if (result != VK_SUCCESS) {
            return fail("COMMANDS", std::string("vkCreateCommandPool: ") + describeVkResult(result));
        }

        VkCommandBufferAllocateInfo allocateInfo{};
        allocateInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
        allocateInfo.commandPool = resources.commandPool;
        allocateInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        allocateInfo.commandBufferCount = 1;
        result = api.vkAllocateCommandBuffers(context.device, &allocateInfo, &resources.commandBuffer);
        if (result != VK_SUCCESS) {
            return fail("COMMANDS",
                        std::string("vkAllocateCommandBuffers: ") + describeVkResult(result));
        }

        VkFenceCreateInfo fenceInfo{};
        fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
        result = api.vkCreateFence(context.device, &fenceInfo, nullptr, &resources.fence);
        if (result != VK_SUCCESS) {
            return fail("COMMANDS", std::string("vkCreateFence: ") + describeVkResult(result));
        }
    }

    const bool timestampsUsable = context.identity.timestampValidBits > 0 &&
                                  context.identity.timestampPeriod > 0.0f;
    if (timestampsUsable) {
        VkQueryPoolCreateInfo info{};
        info.sType = VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO;
        info.queryType = VK_QUERY_TYPE_TIMESTAMP;
        info.queryCount = 2;
        VkResult result = api.vkCreateQueryPool(context.device, &info, nullptr, &resources.queryPool);
        if (result != VK_SUCCESS) {
            return fail("COMMANDS", std::string("vkCreateQueryPool: ") + describeVkResult(result));
        }
    }

    // Timestamps come back with only timestampValidBits significant; the rest is
    // undefined and would produce enormous deltas if it were not masked off.
    const uint64_t timestampMask =
            context.identity.timestampValidBits >= 64
                    ? ~0ull
                    : ((1ull << context.identity.timestampValidBits) - 1ull);

    WorkloadOutcome outcome;
    outcome.imageWidth = config.width;
    outcome.imageHeight = config.height;
    outcome.gpuFrametimesNs.reserve(config.frameCount);
    outcome.cpuFrametimesNs.reserve(config.frameCount);

    const uint32_t totalFrames = config.warmupFrames + config.frameCount;
    const uint32_t vertexCount = tiling ? config.trianglesPerDraw * 3 : 3;

    for (uint32_t frame = 0; frame < totalFrames; ++frame) {
        const bool timed = frame >= config.warmupFrames;
        const int64_t cpuStart = monotonicNanos();

        VkCommandBufferBeginInfo begin{};
        begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
        begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        VkResult result = api.vkBeginCommandBuffer(resources.commandBuffer, &begin);
        if (result != VK_SUCCESS) {
            return fail("RECORD", std::string("vkBeginCommandBuffer: ") + describeVkResult(result));
        }

        if (resources.queryPool != VK_NULL_HANDLE) {
            api.vkCmdResetQueryPool(resources.commandBuffer, resources.queryPool, 0, 2);
            api.vkCmdWriteTimestamp(resources.commandBuffer, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                                    resources.queryPool, 0);
        }

        VkClearValue clears[4]{};
        clears[0].color = {{0.02f, 0.03f, 0.05f, 1.0f}};
        clears[1].depthStencil = {1.0f, 0};
        clears[2].color = {{0.0f, 0.0f, 0.0f, 1.0f}};
        clears[3].color = {{0.0f, 0.0f, 0.0f, 1.0f}};

        VkRenderPassBeginInfo renderPassBegin{};
        renderPassBegin.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
        renderPassBegin.renderPass = resources.renderPass;
        renderPassBegin.framebuffer = resources.framebuffer;
        renderPassBegin.renderArea.extent = {config.width, config.height};
        renderPassBegin.clearValueCount = tiling ? 4 : 1;
        renderPassBegin.pClearValues = clears;

        api.vkCmdBeginRenderPass(resources.commandBuffer, &renderPassBegin,
                                 VK_SUBPASS_CONTENTS_INLINE);
        api.vkCmdBindPipeline(resources.commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS,
                              resources.geometryPipeline);

        for (uint32_t draw = 0; draw < config.drawsPerFrame; ++draw) {
            PushConstants push{frame, draw};
            api.vkCmdPushConstants(resources.commandBuffer, resources.pipelineLayout,
                                   VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0,
                                   sizeof(push), &push);
            api.vkCmdDraw(resources.commandBuffer, vertexCount, 1, 0, 0);
        }

        if (tiling) {
            api.vkCmdNextSubpass(resources.commandBuffer, VK_SUBPASS_CONTENTS_INLINE);
            api.vkCmdBindPipeline(resources.commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS,
                                  resources.resolvePipeline);
            api.vkCmdBindDescriptorSets(resources.commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS,
                                        resources.pipelineLayout, 0, 1, &resources.descriptorSet, 0,
                                        nullptr);
            PushConstants push{frame, 0};
            api.vkCmdPushConstants(resources.commandBuffer, resources.pipelineLayout,
                                   VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0,
                                   sizeof(push), &push);
            api.vkCmdDraw(resources.commandBuffer, 3, 1, 0, 0);
        }

        api.vkCmdEndRenderPass(resources.commandBuffer);

        if (resources.queryPool != VK_NULL_HANDLE) {
            api.vkCmdWriteTimestamp(resources.commandBuffer, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                                    resources.queryPool, 1);
        }

        result = api.vkEndCommandBuffer(resources.commandBuffer);
        if (result != VK_SUCCESS) {
            return fail("RECORD", std::string("vkEndCommandBuffer: ") + describeVkResult(result));
        }

        VkSubmitInfo submit{};
        submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        submit.commandBufferCount = 1;
        submit.pCommandBuffers = &resources.commandBuffer;

        api.vkResetFences(context.device, 1, &resources.fence);
        result = api.vkQueueSubmit(context.queue, 1, &submit, resources.fence);
        if (result != VK_SUCCESS) {
            return fail(result == VK_ERROR_DEVICE_LOST ? "DEVICE_LOST" : "SUBMIT",
                        std::string("vkQueueSubmit: ") + describeVkResult(result));
        }

        // The host cost of a frame is recording plus submitting. The wait is
        // excluded deliberately: including it would make every CPU number a copy
        // of the GPU number and hide the driver's own overhead.
        const int64_t cpuEnd = monotonicNanos();

        result = api.vkWaitForFences(context.device, 1, &resources.fence, VK_TRUE, kFenceTimeoutNs);
        if (result == VK_TIMEOUT) {
            return fail("TIMEOUT", "the GPU did not finish a frame within 10 seconds");
        }
        if (result != VK_SUCCESS) {
            return fail(result == VK_ERROR_DEVICE_LOST ? "DEVICE_LOST" : "WAIT",
                        std::string("vkWaitForFences: ") + describeVkResult(result));
        }

        if (!timed) continue;

        outcome.cpuFrametimesNs.push_back(cpuEnd - cpuStart);

        if (resources.queryPool != VK_NULL_HANDLE) {
            uint64_t timestamps[2] = {0, 0};
            result = api.vkGetQueryPoolResults(context.device, resources.queryPool, 0, 2,
                                               sizeof(timestamps), timestamps, sizeof(uint64_t),
                                               VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WAIT_BIT);
            if (result == VK_SUCCESS) {
                const uint64_t start = timestamps[0] & timestampMask;
                const uint64_t end = timestamps[1] & timestampMask;
                // The counter wraps at timestampValidBits, so a smaller end means
                // it rolled over rather than time going backwards.
                const uint64_t ticks = end >= start ? (end - start)
                                                    : ((timestampMask - start) + end + 1);
                outcome.gpuFrametimesNs.push_back(
                        static_cast<int64_t>(static_cast<double>(ticks) *
                                             context.identity.timestampPeriod));
            }
        }
    }

    // Read the final frame back and hash it. Correctness is judged before speed,
    // so a workload that renders the wrong thing is not a fast workload (P2).
    if (config.captureImage) {
        const VkDeviceSize imageBytes =
                static_cast<VkDeviceSize>(config.width) * config.height * 4;
        if (!createHostBuffer(context, imageBytes, &resources.readback, &error)) {
            return fail("READBACK", error);
        }

        VkCommandBufferBeginInfo begin{};
        begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
        begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        api.vkBeginCommandBuffer(resources.commandBuffer, &begin);

        VkBufferImageCopy region{};
        region.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        region.imageSubresource.layerCount = 1;
        region.imageExtent = {config.width, config.height, 1};

        api.vkCmdCopyImageToBuffer(resources.commandBuffer, resources.finalColor.image,
                                   VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, resources.readback.buffer,
                                   1, &region);
        api.vkEndCommandBuffer(resources.commandBuffer);

        VkSubmitInfo submit{};
        submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        submit.commandBufferCount = 1;
        submit.pCommandBuffers = &resources.commandBuffer;
        api.vkResetFences(context.device, 1, &resources.fence);
        VkResult result = api.vkQueueSubmit(context.queue, 1, &submit, resources.fence);
        if (result != VK_SUCCESS) {
            return fail("READBACK", std::string("readback submit: ") + describeVkResult(result));
        }
        result = api.vkWaitForFences(context.device, 1, &resources.fence, VK_TRUE, kFenceTimeoutNs);
        if (result != VK_SUCCESS) {
            return fail("READBACK", std::string("readback wait: ") + describeVkResult(result));
        }

        VkMappedMemoryRange range{};
        range.sType = VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE;
        range.memory = resources.readback.memory;
        range.size = VK_WHOLE_SIZE;
        api.vkInvalidateMappedMemoryRanges(context.device, 1, &range);

        outcome.imageSha256 = sha256Hex(static_cast<const uint8_t*>(resources.readback.mapped),
                                        static_cast<size_t>(imageBytes));
    }

    std::ostringstream metadata;
    metadata << '{'
             << "\"workloadId\":\"" << workloadId << "\","
             << "\"width\":" << config.width << ','
             << "\"height\":" << config.height << ','
             << "\"frameCount\":" << config.frameCount << ','
             << "\"warmupFrames\":" << config.warmupFrames << ','
             << "\"drawsPerFrame\":" << config.drawsPerFrame << ','
             << "\"trianglesPerDraw\":" << (tiling ? config.trianglesPerDraw : 0u) << ','
             << "\"sampleCount\":" << static_cast<uint32_t>(samples) << ','
             << "\"depthFormat\":" << static_cast<uint32_t>(depthFormat) << ','
             << "\"colorFormat\":" << static_cast<uint32_t>(kColorFormat) << ','
             << "\"timestampsUsable\":" << (resources.queryPool != VK_NULL_HANDLE ? "true" : "false")
             << ',' << "\"timestampPeriod\":" << context.identity.timestampPeriod << ','
             << "\"attachmentBytes\":"
             << (resources.msaaColor.allocatedBytes + resources.msaaDepth.allocatedBytes +
                 resources.resolvedColor.allocatedBytes + resources.finalColor.allocatedBytes)
             << '}';
    outcome.metadataJson = metadata.str();
    outcome.ok = true;
    return outcome;
}

}  // namespace amaral
