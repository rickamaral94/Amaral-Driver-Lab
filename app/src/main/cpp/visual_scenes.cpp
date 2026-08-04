#define VK_USE_PLATFORM_ANDROID_KHR 1

#include <jni.h>

#include <adrenotools/driver.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <vulkan/vulkan.h>

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <fstream>
#include <iomanip>
#include <limits>
#include <numeric>
#include <sstream>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

namespace {

constexpr uint32_t kWidth = 960;
constexpr uint32_t kHeight = 540;
constexpr uint32_t kInstanceCount = 16 * 9;
constexpr VkFormat kOffscreenFormat = VK_FORMAT_R8G8B8A8_UNORM;
constexpr std::array<uint32_t, 3> kCheckpointFrames{{30, 90, 150}};

static const uint32_t kSceneVertexSpirv[] =
#include "visual_scene_vert.inc"
;
static const uint32_t kSceneFragmentSpirv[] =
#include "visual_scene_frag.inc"
;
static const uint32_t kPostVertexSpirv[] =
#include "visual_post_vert.inc"
;
static const uint32_t kPostFragmentSpirv[] =
#include "visual_post_frag.inc"
;

class UtfString {
public:
    UtfString(JNIEnv *environment, jstring source) : env(environment), value(source) {
        chars = value == nullptr ? nullptr : env->GetStringUTFChars(value, nullptr);
    }
    ~UtfString() {
        if (chars != nullptr) env->ReleaseStringUTFChars(value, chars);
    }
    std::string string() const {
        return chars == nullptr ? std::string() : std::string(chars);
    }
private:
    JNIEnv *env;
    jstring value;
    const char *chars = nullptr;
};

class VulkanFailure final : public std::runtime_error {
public:
    VulkanFailure(std::string operationValue, VkResult resultValue)
            : std::runtime_error(operationValue + " failed with VkResult="
                                 + std::to_string(static_cast<int>(resultValue))),
              operation(std::move(operationValue)), result(resultValue) {}
    std::string operation;
    VkResult result;
};

void check(VkResult result, const char *operation) {
    if (result != VK_SUCCESS) throw VulkanFailure(operation, result);
}

std::string jsonEscape(const std::string &value) {
    std::ostringstream output;
    for (unsigned char character : value) {
        switch (character) {
            case '"': output << "\\\""; break;
            case '\\': output << "\\\\"; break;
            case '\b': output << "\\b"; break;
            case '\f': output << "\\f"; break;
            case '\n': output << "\\n"; break;
            case '\r': output << "\\r"; break;
            case '\t': output << "\\t"; break;
            default:
                if (character < 0x20) {
                    output << "\\u" << std::hex << std::setw(4) << std::setfill('0')
                           << static_cast<int>(character) << std::dec;
                } else {
                    output << character;
                }
        }
    }
    return output.str();
}

std::string withTrailingSlash(std::string value) {
    if (!value.empty() && value.back() != '/') value.push_back('/');
    return value;
}

template <typename Function>
Function requireSymbol(void *library, const char *name) {
    auto function = reinterpret_cast<Function>(dlsym(library, name));
    if (function == nullptr) throw std::runtime_error(std::string("Missing Vulkan symbol: ") + name);
    return function;
}

template <typename Function>
Function requireInstance(PFN_vkGetInstanceProcAddr getter, VkInstance instance, const char *name) {
    auto function = reinterpret_cast<Function>(getter(instance, name));
    if (function == nullptr) {
        throw std::runtime_error(std::string("Missing Vulkan instance function: ") + name);
    }
    return function;
}

template <typename Function>
Function requireDevice(PFN_vkGetDeviceProcAddr getter, VkDevice device, const char *name) {
    auto function = reinterpret_cast<Function>(getter(device, name));
    if (function == nullptr) {
        throw std::runtime_error(std::string("Missing Vulkan device function: ") + name);
    }
    return function;
}

bool hasExtension(const std::vector<VkExtensionProperties> &extensions, const char *name) {
    return std::any_of(extensions.begin(), extensions.end(), [name](const auto &extension) {
        return std::strcmp(extension.extensionName, name) == 0;
    });
}

double percentile(std::vector<double> values, double quantile) {
    if (values.empty()) return std::numeric_limits<double>::quiet_NaN();
    std::sort(values.begin(), values.end());
    if (values.size() == 1) return values.front();
    const double position = quantile * static_cast<double>(values.size() - 1);
    const size_t lower = static_cast<size_t>(std::floor(position));
    const size_t upper = static_cast<size_t>(std::ceil(position));
    if (lower == upper) return values[lower];
    const double fraction = position - static_cast<double>(lower);
    return values[lower] * (1.0 - fraction) + values[upper] * fraction;
}

struct PushConstants {
    float timeSeconds;
    uint32_t sceneKind;
    float resolution[2];
};

struct ImageResource {
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkImageView view = VK_NULL_HANDLE;
};

class VisualRenderer {
public:
    ~VisualRenderer() { cleanup(); }

    void initialize(JNIEnv *environment,
                    jobject surfaceObject,
                    const std::string &sceneIdValue,
                    const std::string &driverDirectory,
                    const std::string &driverName,
                    const std::string &nativeLibraryDirectory,
                    const std::string &temporaryDirectory) {
        sceneId = sceneIdValue;
        if (sceneId == "visual_scene_geometry") sceneKind = 0;
        else if (sceneId == "visual_scene_materials") sceneKind = 1;
        else if (sceneId == "visual_scene_postprocess") sceneKind = 2;
        else throw std::runtime_error("Unknown visual scene: " + sceneId);

        stage = "open_loader";
        customDriver = !driverDirectory.empty();
        if (customDriver) {
            if (driverName.empty()) throw std::runtime_error("Custom driver library name is missing");
            const std::string driverPath = withTrailingSlash(driverDirectory);
            const std::string temporaryPath = withTrailingSlash(temporaryDirectory);
            library = adrenotools_open_libvulkan(
                    RTLD_NOW | RTLD_LOCAL,
                    ADRENOTOOLS_DRIVER_CUSTOM,
                    temporaryPath.c_str(),
                    nativeLibraryDirectory.c_str(),
                    driverPath.c_str(),
                    driverName.c_str(),
                    nullptr,
                    nullptr);
        } else {
            library = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
            if (library == nullptr) library = dlopen("libvulkan.so.1", RTLD_NOW | RTLD_LOCAL);
        }
        if (library == nullptr) {
            const char *error = dlerror();
            throw std::runtime_error(std::string("Unable to open Vulkan loader: ")
                                     + (error == nullptr ? "unknown" : error));
        }

        getInstanceProcAddr = requireSymbol<PFN_vkGetInstanceProcAddr>(library, "vkGetInstanceProcAddr");
        createInstance = requireSymbol<PFN_vkCreateInstance>(library, "vkCreateInstance");
        enumerateInstanceExtensionProperties = requireSymbol<PFN_vkEnumerateInstanceExtensionProperties>(
                library, "vkEnumerateInstanceExtensionProperties");

        uint32_t extensionCount = 0;
        check(enumerateInstanceExtensionProperties(nullptr, &extensionCount, nullptr),
              "vkEnumerateInstanceExtensionProperties(count)");
        std::vector<VkExtensionProperties> extensions(extensionCount);
        check(enumerateInstanceExtensionProperties(nullptr, &extensionCount, extensions.data()),
              "vkEnumerateInstanceExtensionProperties(list)");
        if (!hasExtension(extensions, VK_KHR_SURFACE_EXTENSION_NAME)
                || !hasExtension(extensions, VK_KHR_ANDROID_SURFACE_EXTENSION_NAME)) {
            throw std::runtime_error("Driver does not expose Android Vulkan surface extensions");
        }
        const std::array<const char *, 2> instanceExtensions{{
                VK_KHR_SURFACE_EXTENSION_NAME,
                VK_KHR_ANDROID_SURFACE_EXTENSION_NAME,
        }};

        VkApplicationInfo appInfo{};
        appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
        appInfo.pApplicationName = "Amaral Driver Lab Visual Scenes";
        appInfo.applicationVersion = VK_MAKE_VERSION(0, 8, 0);
        appInfo.pEngineName = "Visible deterministic Vulkan scenes";
        appInfo.engineVersion = VK_MAKE_VERSION(1, 0, 0);
        appInfo.apiVersion = VK_API_VERSION_1_0;

        VkInstanceCreateInfo instanceInfo{};
        instanceInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
        instanceInfo.pApplicationInfo = &appInfo;
        instanceInfo.enabledExtensionCount = static_cast<uint32_t>(instanceExtensions.size());
        instanceInfo.ppEnabledExtensionNames = instanceExtensions.data();
        stage = "create_instance";
        check(createInstance(&instanceInfo, nullptr, &instance), "vkCreateInstance");
        loadInstanceFunctions();

        nativeWindow = ANativeWindow_fromSurface(environment, surfaceObject);
        if (nativeWindow == nullptr) throw std::runtime_error("Unable to acquire ANativeWindow");
        VkAndroidSurfaceCreateInfoKHR surfaceInfo{};
        surfaceInfo.sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR;
        surfaceInfo.window = nativeWindow;
        stage = "create_android_surface";
        check(vkCreateAndroidSurfaceKHR(instance, &surfaceInfo, nullptr, &surface),
              "vkCreateAndroidSurfaceKHR");

        uint32_t physicalCount = 0;
        check(vkEnumeratePhysicalDevices(instance, &physicalCount, nullptr),
              "vkEnumeratePhysicalDevices(count)");
        if (physicalCount == 0) throw std::runtime_error("Driver exposed no physical device");
        std::vector<VkPhysicalDevice> devices(physicalCount);
        check(vkEnumeratePhysicalDevices(instance, &physicalCount, devices.data()),
              "vkEnumeratePhysicalDevices(list)");
        physicalDevice = devices.front();
        vkGetPhysicalDeviceProperties(physicalDevice, &properties);
        vkGetPhysicalDeviceMemoryProperties(physicalDevice, &memoryProperties);

        uint32_t deviceExtensionCount = 0;
        check(vkEnumerateDeviceExtensionProperties(physicalDevice, nullptr,
                                                   &deviceExtensionCount, nullptr),
              "vkEnumerateDeviceExtensionProperties(count)");
        std::vector<VkExtensionProperties> deviceExtensions(deviceExtensionCount);
        check(vkEnumerateDeviceExtensionProperties(physicalDevice, nullptr,
                                                   &deviceExtensionCount, deviceExtensions.data()),
              "vkEnumerateDeviceExtensionProperties(list)");
        if (!hasExtension(deviceExtensions, VK_KHR_SWAPCHAIN_EXTENSION_NAME)) {
            throw std::runtime_error("Driver does not expose VK_KHR_swapchain");
        }

        uint32_t familyCount = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, &familyCount, nullptr);
        std::vector<VkQueueFamilyProperties> families(familyCount);
        vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, &familyCount, families.data());
        bool foundQueue = false;
        for (uint32_t index = 0; index < familyCount; ++index) {
            VkBool32 present = VK_FALSE;
            check(vkGetPhysicalDeviceSurfaceSupportKHR(physicalDevice, index, surface, &present),
                  "vkGetPhysicalDeviceSurfaceSupportKHR");
            if (families[index].queueCount > 0
                    && (families[index].queueFlags & VK_QUEUE_GRAPHICS_BIT) != 0
                    && present == VK_TRUE) {
                queueFamilyIndex = index;
                queueFamilyProperties = families[index];
                foundQueue = true;
                break;
            }
        }
        if (!foundQueue) throw std::runtime_error("No graphics+present queue is available");

        float priority = 1.0F;
        VkDeviceQueueCreateInfo queueInfo{};
        queueInfo.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
        queueInfo.queueFamilyIndex = queueFamilyIndex;
        queueInfo.queueCount = 1;
        queueInfo.pQueuePriorities = &priority;
        const char *swapchainExtension = VK_KHR_SWAPCHAIN_EXTENSION_NAME;
        VkDeviceCreateInfo deviceInfo{};
        deviceInfo.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
        deviceInfo.queueCreateInfoCount = 1;
        deviceInfo.pQueueCreateInfos = &queueInfo;
        deviceInfo.enabledExtensionCount = 1;
        deviceInfo.ppEnabledExtensionNames = &swapchainExtension;
        stage = "create_device";
        check(vkCreateDevice(physicalDevice, &deviceInfo, nullptr, &device), "vkCreateDevice");
        loadDeviceFunctions();
        vkGetDeviceQueue(device, queueFamilyIndex, 0, &queue);

        createSwapchain();
        createOffscreenResources();
        createRenderPasses();
        createDescriptors();
        createPipelines();
        createCommandResources();
        createSynchronization();
    }

    std::string run(int warmupSeconds, int measureSeconds, const std::string &rawPrefix) {
        warmupSeconds = std::clamp(warmupSeconds, 0, 30);
        measureSeconds = std::clamp(measureSeconds, 1, 120);
        const auto start = std::chrono::steady_clock::now();
        const auto warmupDeadline = start + std::chrono::seconds(warmupSeconds);
        const auto measurementDeadline = warmupDeadline + std::chrono::seconds(measureSeconds);
        uint32_t frame = 0;
        std::vector<double> measuredFrameTimes;
        std::vector<uint32_t> captured;

        do {
            const bool checkpoint = std::find(kCheckpointFrames.begin(), kCheckpointFrames.end(), frame)
                    != kCheckpointFrames.end();
            const auto wallStart = std::chrono::steady_clock::now();
            const double gpuMs = renderFrame(frame, checkpoint,
                    checkpoint ? checkpointPath(rawPrefix, frame) : std::string());
            const auto wallEnd = std::chrono::steady_clock::now();
            const double wallMs = std::chrono::duration<double, std::milli>(wallEnd - wallStart).count();
            if (checkpoint) captured.push_back(frame);
            if (wallEnd >= warmupDeadline && wallEnd <= measurementDeadline) {
                measuredFrameTimes.push_back(timestampsSupported ? gpuMs : wallMs);
            }
            ++frame;
        } while (std::chrono::steady_clock::now() < measurementDeadline
                 || frame <= kCheckpointFrames.back());

        if (measuredFrameTimes.empty()) {
            throw std::runtime_error("No visual frame timing samples were collected");
        }
        if (captured.size() != kCheckpointFrames.size()) {
            throw std::runtime_error("Not all deterministic visual checkpoints were captured");
        }
        const double mean = std::accumulate(measuredFrameTimes.begin(), measuredFrameTimes.end(), 0.0)
                / static_cast<double>(measuredFrameTimes.size());
        const double p50 = percentile(measuredFrameTimes, 0.50);
        const double p95 = percentile(measuredFrameTimes, 0.95);
        const double p99 = percentile(measuredFrameTimes, 0.99);
        const double onePercentLow = p99 > 0.0 ? 1000.0 / p99 : 0.0;

        std::ostringstream json;
        json << std::fixed << std::setprecision(6)
             << "{\"success\":true"
             << ",\"workload\":\"" << jsonEscape(sceneId) << "_v1\""
             << ",\"visual_scene_id\":\"" << jsonEscape(sceneId) << "\""
             << ",\"visual_scene_version\":1"
             << ",\"custom_driver\":" << (customDriver ? "true" : "false")
             << ",\"gpu_name\":\"" << jsonEscape(properties.deviceName) << "\""
             << ",\"vendor_id\":" << properties.vendorID
             << ",\"device_id\":" << properties.deviceID
             << ",\"driver_version_raw\":" << properties.driverVersion
             << ",\"image_width\":" << kWidth
             << ",\"image_height\":" << kHeight
             << ",\"surface_width\":" << surfaceExtent.width
             << ",\"surface_height\":" << surfaceExtent.height
             << ",\"swapchain_format\":" << static_cast<int>(surfaceFormat.format)
             << ",\"present_mode\":" << static_cast<int>(presentMode)
             << ",\"frames_rendered\":" << frame
             << ",\"timed_frame_count\":" << measuredFrameTimes.size()
             << ",\"gpu_timestamps_used\":" << (timestampsSupported ? "true" : "false")
             << ",\"p50_gpu_frame_ms\":" << p50
             << ",\"p95_gpu_frame_ms\":" << p95
             << ",\"p99_gpu_frame_ms\":" << p99
             << ",\"mean_gpu_frame_ms\":" << mean
             << ",\"one_percent_low_fps\":" << onePercentLow
             << ",\"checkpoint_frames\":[";
        for (size_t index = 0; index < captured.size(); ++index) {
            if (index > 0) json << ',';
            json << captured[index];
        }
        json << "]}"
             ;
        return json.str();
    }

    std::string failureJson(const std::exception &error) const {
        std::ostringstream json;
        json << "{\"success\":false"
             << ",\"failure_type\":\"visual_scene_failure\""
             << ",\"failure_stage\":\"" << jsonEscape(stage) << "\""
             << ",\"visual_scene_id\":\"" << jsonEscape(sceneId) << "\""
             << ",\"error\":\"" << jsonEscape(error.what()) << "\"";
        const auto *vulkan = dynamic_cast<const VulkanFailure *>(&error);
        if (vulkan != nullptr) {
            json << ",\"vk_result\":" << static_cast<int>(vulkan->result)
                 << ",\"vulkan_operation\":\"" << jsonEscape(vulkan->operation) << "\"";
            if (vulkan->result == VK_ERROR_DEVICE_LOST) {
                json << ",\"device_lost\":true";
            }
        }
        json << '}';
        return json.str();
    }

private:
    void loadInstanceFunctions() {
        vkDestroyInstance = requireInstance<PFN_vkDestroyInstance>(getInstanceProcAddr, instance,
                                                                   "vkDestroyInstance");
        vkEnumeratePhysicalDevices = requireInstance<PFN_vkEnumeratePhysicalDevices>(
                getInstanceProcAddr, instance, "vkEnumeratePhysicalDevices");
        vkGetPhysicalDeviceProperties = requireInstance<PFN_vkGetPhysicalDeviceProperties>(
                getInstanceProcAddr, instance, "vkGetPhysicalDeviceProperties");
        vkGetPhysicalDeviceMemoryProperties = requireInstance<PFN_vkGetPhysicalDeviceMemoryProperties>(
                getInstanceProcAddr, instance, "vkGetPhysicalDeviceMemoryProperties");
        vkGetPhysicalDeviceQueueFamilyProperties = requireInstance<PFN_vkGetPhysicalDeviceQueueFamilyProperties>(
                getInstanceProcAddr, instance, "vkGetPhysicalDeviceQueueFamilyProperties");
        vkEnumerateDeviceExtensionProperties = requireInstance<PFN_vkEnumerateDeviceExtensionProperties>(
                getInstanceProcAddr, instance, "vkEnumerateDeviceExtensionProperties");
        vkGetPhysicalDeviceFormatProperties = requireInstance<PFN_vkGetPhysicalDeviceFormatProperties>(
                getInstanceProcAddr, instance, "vkGetPhysicalDeviceFormatProperties");
        vkCreateDevice = requireInstance<PFN_vkCreateDevice>(getInstanceProcAddr, instance,
                                                             "vkCreateDevice");
        getDeviceProcAddr = requireInstance<PFN_vkGetDeviceProcAddr>(getInstanceProcAddr, instance,
                                                                    "vkGetDeviceProcAddr");
        vkCreateAndroidSurfaceKHR = requireInstance<PFN_vkCreateAndroidSurfaceKHR>(
                getInstanceProcAddr, instance, "vkCreateAndroidSurfaceKHR");
        vkDestroySurfaceKHR = requireInstance<PFN_vkDestroySurfaceKHR>(getInstanceProcAddr, instance,
                                                                       "vkDestroySurfaceKHR");
        vkGetPhysicalDeviceSurfaceSupportKHR = requireInstance<PFN_vkGetPhysicalDeviceSurfaceSupportKHR>(
                getInstanceProcAddr, instance, "vkGetPhysicalDeviceSurfaceSupportKHR");
        vkGetPhysicalDeviceSurfaceCapabilitiesKHR = requireInstance<PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR>(
                getInstanceProcAddr, instance, "vkGetPhysicalDeviceSurfaceCapabilitiesKHR");
        vkGetPhysicalDeviceSurfaceFormatsKHR = requireInstance<PFN_vkGetPhysicalDeviceSurfaceFormatsKHR>(
                getInstanceProcAddr, instance, "vkGetPhysicalDeviceSurfaceFormatsKHR");
        vkGetPhysicalDeviceSurfacePresentModesKHR = requireInstance<PFN_vkGetPhysicalDeviceSurfacePresentModesKHR>(
                getInstanceProcAddr, instance, "vkGetPhysicalDeviceSurfacePresentModesKHR");
    }

    void loadDeviceFunctions() {
#define LOAD_DEVICE(name) name = requireDevice<PFN_##name>(getDeviceProcAddr, device, #name)
        LOAD_DEVICE(vkDestroyDevice);
        LOAD_DEVICE(vkGetDeviceQueue);
        LOAD_DEVICE(vkCreateSwapchainKHR);
        LOAD_DEVICE(vkDestroySwapchainKHR);
        LOAD_DEVICE(vkGetSwapchainImagesKHR);
        LOAD_DEVICE(vkAcquireNextImageKHR);
        LOAD_DEVICE(vkQueuePresentKHR);
        LOAD_DEVICE(vkCreateImage);
        LOAD_DEVICE(vkDestroyImage);
        LOAD_DEVICE(vkGetImageMemoryRequirements);
        LOAD_DEVICE(vkBindImageMemory);
        LOAD_DEVICE(vkCreateImageView);
        LOAD_DEVICE(vkDestroyImageView);
        LOAD_DEVICE(vkAllocateMemory);
        LOAD_DEVICE(vkFreeMemory);
        LOAD_DEVICE(vkCreateBuffer);
        LOAD_DEVICE(vkDestroyBuffer);
        LOAD_DEVICE(vkGetBufferMemoryRequirements);
        LOAD_DEVICE(vkBindBufferMemory);
        LOAD_DEVICE(vkMapMemory);
        LOAD_DEVICE(vkUnmapMemory);
        LOAD_DEVICE(vkInvalidateMappedMemoryRanges);
        LOAD_DEVICE(vkCreateRenderPass);
        LOAD_DEVICE(vkDestroyRenderPass);
        LOAD_DEVICE(vkCreateFramebuffer);
        LOAD_DEVICE(vkDestroyFramebuffer);
        LOAD_DEVICE(vkCreateShaderModule);
        LOAD_DEVICE(vkDestroyShaderModule);
        LOAD_DEVICE(vkCreateDescriptorSetLayout);
        LOAD_DEVICE(vkDestroyDescriptorSetLayout);
        LOAD_DEVICE(vkCreateDescriptorPool);
        LOAD_DEVICE(vkDestroyDescriptorPool);
        LOAD_DEVICE(vkAllocateDescriptorSets);
        LOAD_DEVICE(vkUpdateDescriptorSets);
        LOAD_DEVICE(vkCreateSampler);
        LOAD_DEVICE(vkDestroySampler);
        LOAD_DEVICE(vkCreatePipelineLayout);
        LOAD_DEVICE(vkDestroyPipelineLayout);
        LOAD_DEVICE(vkCreateGraphicsPipelines);
        LOAD_DEVICE(vkDestroyPipeline);
        LOAD_DEVICE(vkCreateCommandPool);
        LOAD_DEVICE(vkDestroyCommandPool);
        LOAD_DEVICE(vkAllocateCommandBuffers);
        LOAD_DEVICE(vkResetCommandBuffer);
        LOAD_DEVICE(vkBeginCommandBuffer);
        LOAD_DEVICE(vkEndCommandBuffer);
        LOAD_DEVICE(vkCmdPipelineBarrier);
        LOAD_DEVICE(vkCmdBeginRenderPass);
        LOAD_DEVICE(vkCmdEndRenderPass);
        LOAD_DEVICE(vkCmdBindPipeline);
        LOAD_DEVICE(vkCmdBindDescriptorSets);
        LOAD_DEVICE(vkCmdPushConstants);
        LOAD_DEVICE(vkCmdDraw);
        LOAD_DEVICE(vkCmdBlitImage);
        LOAD_DEVICE(vkCmdCopyImageToBuffer);
        LOAD_DEVICE(vkCreateSemaphore);
        LOAD_DEVICE(vkDestroySemaphore);
        LOAD_DEVICE(vkCreateFence);
        LOAD_DEVICE(vkDestroyFence);
        LOAD_DEVICE(vkWaitForFences);
        LOAD_DEVICE(vkResetFences);
        LOAD_DEVICE(vkQueueSubmit);
        LOAD_DEVICE(vkDeviceWaitIdle);
        LOAD_DEVICE(vkCreateQueryPool);
        LOAD_DEVICE(vkDestroyQueryPool);
        LOAD_DEVICE(vkCmdResetQueryPool);
        LOAD_DEVICE(vkCmdWriteTimestamp);
        LOAD_DEVICE(vkGetQueryPoolResults);
#undef LOAD_DEVICE
    }

    void createSwapchain() {
        stage = "create_swapchain";
        VkSurfaceCapabilitiesKHR capabilities{};
        check(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physicalDevice, surface, &capabilities),
              "vkGetPhysicalDeviceSurfaceCapabilitiesKHR");
        if ((capabilities.supportedUsageFlags & VK_IMAGE_USAGE_TRANSFER_DST_BIT) == 0) {
            throw std::runtime_error("Android swapchain does not support TRANSFER_DST");
        }
        uint32_t formatCount = 0;
        check(vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, surface, &formatCount, nullptr),
              "vkGetPhysicalDeviceSurfaceFormatsKHR(count)");
        if (formatCount == 0) throw std::runtime_error("Surface exposes no formats");
        std::vector<VkSurfaceFormatKHR> formats(formatCount);
        check(vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, surface, &formatCount,
                                                   formats.data()),
              "vkGetPhysicalDeviceSurfaceFormatsKHR(list)");
        surfaceFormat = formats.front();
        for (const auto &candidate : formats) {
            if ((candidate.format == VK_FORMAT_R8G8B8A8_UNORM
                    || candidate.format == VK_FORMAT_B8G8R8A8_UNORM)
                    && candidate.colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
                surfaceFormat = candidate;
                break;
            }
        }

        uint32_t presentCount = 0;
        check(vkGetPhysicalDeviceSurfacePresentModesKHR(physicalDevice, surface, &presentCount,
                                                        nullptr),
              "vkGetPhysicalDeviceSurfacePresentModesKHR(count)");
        std::vector<VkPresentModeKHR> modes(presentCount);
        check(vkGetPhysicalDeviceSurfacePresentModesKHR(physicalDevice, surface, &presentCount,
                                                        modes.data()),
              "vkGetPhysicalDeviceSurfacePresentModesKHR(list)");
        presentMode = VK_PRESENT_MODE_FIFO_KHR;
        if (std::find(modes.begin(), modes.end(), VK_PRESENT_MODE_MAILBOX_KHR) != modes.end()) {
            presentMode = VK_PRESENT_MODE_MAILBOX_KHR;
        } else if (std::find(modes.begin(), modes.end(), VK_PRESENT_MODE_IMMEDIATE_KHR) != modes.end()) {
            presentMode = VK_PRESENT_MODE_IMMEDIATE_KHR;
        }

        if (capabilities.currentExtent.width != std::numeric_limits<uint32_t>::max()) {
            surfaceExtent = capabilities.currentExtent;
        } else {
            surfaceExtent.width = std::clamp<uint32_t>(
                    static_cast<uint32_t>(std::max(1, ANativeWindow_getWidth(nativeWindow))),
                    capabilities.minImageExtent.width, capabilities.maxImageExtent.width);
            surfaceExtent.height = std::clamp<uint32_t>(
                    static_cast<uint32_t>(std::max(1, ANativeWindow_getHeight(nativeWindow))),
                    capabilities.minImageExtent.height, capabilities.maxImageExtent.height);
        }
        uint32_t imageCount = capabilities.minImageCount + 1;
        if (capabilities.maxImageCount > 0) imageCount = std::min(imageCount, capabilities.maxImageCount);

        VkSwapchainCreateInfoKHR info{};
        info.sType = VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR;
        info.surface = surface;
        info.minImageCount = imageCount;
        info.imageFormat = surfaceFormat.format;
        info.imageColorSpace = surfaceFormat.colorSpace;
        info.imageExtent = surfaceExtent;
        info.imageArrayLayers = 1;
        info.imageUsage = VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        info.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
        info.preTransform = capabilities.currentTransform;
        info.compositeAlpha = chooseCompositeAlpha(capabilities.supportedCompositeAlpha);
        info.presentMode = presentMode;
        info.clipped = VK_TRUE;
        check(vkCreateSwapchainKHR(device, &info, nullptr, &swapchain), "vkCreateSwapchainKHR");
        check(vkGetSwapchainImagesKHR(device, swapchain, &imageCount, nullptr),
              "vkGetSwapchainImagesKHR(count)");
        swapchainImages.resize(imageCount);
        check(vkGetSwapchainImagesKHR(device, swapchain, &imageCount, swapchainImages.data()),
              "vkGetSwapchainImagesKHR(list)");

        VkFormatProperties sourceProperties{};
        VkFormatProperties destinationProperties{};
        vkGetPhysicalDeviceFormatProperties(physicalDevice, kOffscreenFormat, &sourceProperties);
        vkGetPhysicalDeviceFormatProperties(physicalDevice, surfaceFormat.format,
                                            &destinationProperties);
        if ((sourceProperties.optimalTilingFeatures & VK_FORMAT_FEATURE_BLIT_SRC_BIT) == 0
                || (destinationProperties.optimalTilingFeatures & VK_FORMAT_FEATURE_BLIT_DST_BIT) == 0) {
            throw std::runtime_error("Surface/offscreen formats do not support Vulkan blit");
        }
    }

    static VkCompositeAlphaFlagBitsKHR chooseCompositeAlpha(VkCompositeAlphaFlagsKHR flags) {
        const std::array<VkCompositeAlphaFlagBitsKHR, 4> values{{
                VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR,
                VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR,
                VK_COMPOSITE_ALPHA_POST_MULTIPLIED_BIT_KHR,
                VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR,
        }};
        for (VkCompositeAlphaFlagBitsKHR value : values) if ((flags & value) != 0) return value;
        return VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
    }

    uint32_t memoryType(uint32_t bits, VkMemoryPropertyFlags required) const {
        for (uint32_t index = 0; index < memoryProperties.memoryTypeCount; ++index) {
            if ((bits & (1u << index)) != 0
                    && (memoryProperties.memoryTypes[index].propertyFlags & required) == required) {
                return index;
            }
        }
        throw std::runtime_error("No compatible Vulkan memory type");
    }

    void createImage(ImageResource &resource, VkFormat format, VkImageUsageFlags usage,
                     VkImageAspectFlags aspect) {
        VkImageCreateInfo imageInfo{};
        imageInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
        imageInfo.imageType = VK_IMAGE_TYPE_2D;
        imageInfo.format = format;
        imageInfo.extent = {kWidth, kHeight, 1};
        imageInfo.mipLevels = 1;
        imageInfo.arrayLayers = 1;
        imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
        imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
        imageInfo.usage = usage;
        imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        check(vkCreateImage(device, &imageInfo, nullptr, &resource.image), "vkCreateImage");
        VkMemoryRequirements requirements{};
        vkGetImageMemoryRequirements(device, resource.image, &requirements);
        VkMemoryAllocateInfo allocate{};
        allocate.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        allocate.allocationSize = requirements.size;
        allocate.memoryTypeIndex = memoryType(requirements.memoryTypeBits,
                                               VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        check(vkAllocateMemory(device, &allocate, nullptr, &resource.memory), "vkAllocateMemory(image)");
        check(vkBindImageMemory(device, resource.image, resource.memory, 0), "vkBindImageMemory");
        VkImageViewCreateInfo viewInfo{};
        viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
        viewInfo.image = resource.image;
        viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
        viewInfo.format = format;
        viewInfo.subresourceRange.aspectMask = aspect;
        viewInfo.subresourceRange.levelCount = 1;
        viewInfo.subresourceRange.layerCount = 1;
        check(vkCreateImageView(device, &viewInfo, nullptr, &resource.view), "vkCreateImageView");
    }

    void createOffscreenResources() {
        stage = "create_offscreen_images";
        VkFormatProperties colorProperties{};
        vkGetPhysicalDeviceFormatProperties(physicalDevice, kOffscreenFormat, &colorProperties);
        const VkFormatFeatureFlags needed = VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT
                | VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT | VK_FORMAT_FEATURE_BLIT_SRC_BIT;
        if ((colorProperties.optimalTilingFeatures & needed) != needed) {
            throw std::runtime_error("RGBA8 does not expose required visual scene features");
        }
        depthFormat = chooseDepthFormat();
        createImage(sceneImage, kOffscreenFormat,
                    VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
                    VK_IMAGE_ASPECT_COLOR_BIT);
        createImage(depthImage, depthFormat, VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT,
                    VK_IMAGE_ASPECT_DEPTH_BIT);
        createImage(finalImage, kOffscreenFormat,
                    VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT,
                    VK_IMAGE_ASPECT_COLOR_BIT);

        const VkDeviceSize bytes = static_cast<VkDeviceSize>(kWidth) * kHeight * 4;
        VkBufferCreateInfo bufferInfo{};
        bufferInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
        bufferInfo.size = bytes;
        bufferInfo.usage = VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        bufferInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        check(vkCreateBuffer(device, &bufferInfo, nullptr, &readbackBuffer), "vkCreateBuffer(readback)");
        VkMemoryRequirements requirements{};
        vkGetBufferMemoryRequirements(device, readbackBuffer, &requirements);
        VkMemoryAllocateInfo allocate{};
        allocate.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        allocate.allocationSize = requirements.size;
        uint32_t coherentIndex = std::numeric_limits<uint32_t>::max();
        for (uint32_t index = 0; index < memoryProperties.memoryTypeCount; ++index) {
            const auto flags = memoryProperties.memoryTypes[index].propertyFlags;
            if ((requirements.memoryTypeBits & (1u << index)) != 0
                    && (flags & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) != 0) {
                if (coherentIndex == std::numeric_limits<uint32_t>::max()) coherentIndex = index;
                if ((flags & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) != 0) {
                    coherentIndex = index;
                    readbackCoherent = true;
                    break;
                }
            }
        }
        if (coherentIndex == std::numeric_limits<uint32_t>::max()) {
            throw std::runtime_error("No host-visible memory for visual checkpoints");
        }
        allocate.memoryTypeIndex = coherentIndex;
        check(vkAllocateMemory(device, &allocate, nullptr, &readbackMemory),
              "vkAllocateMemory(readback)");
        check(vkBindBufferMemory(device, readbackBuffer, readbackMemory, 0),
              "vkBindBufferMemory(readback)");
    }

    VkFormat chooseDepthFormat() {
        const std::array<VkFormat, 3> formats{{
                VK_FORMAT_D32_SFLOAT,
                VK_FORMAT_D24_UNORM_S8_UINT,
                VK_FORMAT_D16_UNORM,
        }};
        for (VkFormat format : formats) {
            VkFormatProperties propertiesValue{};
            vkGetPhysicalDeviceFormatProperties(physicalDevice, format, &propertiesValue);
            if ((propertiesValue.optimalTilingFeatures
                    & VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT) != 0) return format;
        }
        throw std::runtime_error("No supported depth format for visual scenes");
    }

    void createRenderPasses() {
        stage = "create_render_passes";
        VkAttachmentDescription sceneAttachments[2]{};
        sceneAttachments[0].format = kOffscreenFormat;
        sceneAttachments[0].samples = VK_SAMPLE_COUNT_1_BIT;
        sceneAttachments[0].loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
        sceneAttachments[0].storeOp = VK_ATTACHMENT_STORE_OP_STORE;
        sceneAttachments[0].initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        sceneAttachments[0].finalLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        sceneAttachments[1].format = depthFormat;
        sceneAttachments[1].samples = VK_SAMPLE_COUNT_1_BIT;
        sceneAttachments[1].loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
        sceneAttachments[1].storeOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
        sceneAttachments[1].initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        sceneAttachments[1].finalLayout = VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL;
        VkAttachmentReference colorReference{0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
        VkAttachmentReference depthReference{1, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL};
        VkSubpassDescription subpass{};
        subpass.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
        subpass.colorAttachmentCount = 1;
        subpass.pColorAttachments = &colorReference;
        subpass.pDepthStencilAttachment = &depthReference;
        VkSubpassDependency dependency{};
        dependency.srcSubpass = VK_SUBPASS_EXTERNAL;
        dependency.dstSubpass = 0;
        dependency.srcStageMask = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
        dependency.dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        dependency.srcAccessMask = VK_ACCESS_SHADER_READ_BIT;
        dependency.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
        VkRenderPassCreateInfo sceneInfo{};
        sceneInfo.sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO;
        sceneInfo.attachmentCount = 2;
        sceneInfo.pAttachments = sceneAttachments;
        sceneInfo.subpassCount = 1;
        sceneInfo.pSubpasses = &subpass;
        sceneInfo.dependencyCount = 1;
        sceneInfo.pDependencies = &dependency;
        check(vkCreateRenderPass(device, &sceneInfo, nullptr, &sceneRenderPass),
              "vkCreateRenderPass(scene)");

        VkAttachmentDescription finalAttachment{};
        finalAttachment.format = kOffscreenFormat;
        finalAttachment.samples = VK_SAMPLE_COUNT_1_BIT;
        finalAttachment.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
        finalAttachment.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
        finalAttachment.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        finalAttachment.finalLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
        VkAttachmentReference finalReference{0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
        VkSubpassDescription finalSubpass{};
        finalSubpass.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
        finalSubpass.colorAttachmentCount = 1;
        finalSubpass.pColorAttachments = &finalReference;
        VkSubpassDependency finalDependency{};
        finalDependency.srcSubpass = VK_SUBPASS_EXTERNAL;
        finalDependency.dstSubpass = 0;
        finalDependency.srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        finalDependency.dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        finalDependency.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
        VkRenderPassCreateInfo finalInfo{};
        finalInfo.sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO;
        finalInfo.attachmentCount = 1;
        finalInfo.pAttachments = &finalAttachment;
        finalInfo.subpassCount = 1;
        finalInfo.pSubpasses = &finalSubpass;
        finalInfo.dependencyCount = 1;
        finalInfo.pDependencies = &finalDependency;
        check(vkCreateRenderPass(device, &finalInfo, nullptr, &finalRenderPass),
              "vkCreateRenderPass(final)");

        const std::array<VkImageView, 2> sceneViews{{sceneImage.view, depthImage.view}};
        VkFramebufferCreateInfo framebufferInfo{};
        framebufferInfo.sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
        framebufferInfo.renderPass = sceneRenderPass;
        framebufferInfo.attachmentCount = static_cast<uint32_t>(sceneViews.size());
        framebufferInfo.pAttachments = sceneViews.data();
        framebufferInfo.width = kWidth;
        framebufferInfo.height = kHeight;
        framebufferInfo.layers = 1;
        check(vkCreateFramebuffer(device, &framebufferInfo, nullptr, &sceneFramebuffer),
              "vkCreateFramebuffer(scene)");
        framebufferInfo.renderPass = finalRenderPass;
        framebufferInfo.attachmentCount = 1;
        framebufferInfo.pAttachments = &finalImage.view;
        check(vkCreateFramebuffer(device, &framebufferInfo, nullptr, &finalFramebuffer),
              "vkCreateFramebuffer(final)");
    }

    void createDescriptors() {
        VkDescriptorSetLayoutBinding binding{};
        binding.binding = 0;
        binding.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        binding.descriptorCount = 1;
        binding.stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;
        VkDescriptorSetLayoutCreateInfo layoutInfo{};
        layoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
        layoutInfo.bindingCount = 1;
        layoutInfo.pBindings = &binding;
        check(vkCreateDescriptorSetLayout(device, &layoutInfo, nullptr, &descriptorSetLayout),
              "vkCreateDescriptorSetLayout");
        VkDescriptorPoolSize poolSize{VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1};
        VkDescriptorPoolCreateInfo poolInfo{};
        poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
        poolInfo.maxSets = 1;
        poolInfo.poolSizeCount = 1;
        poolInfo.pPoolSizes = &poolSize;
        check(vkCreateDescriptorPool(device, &poolInfo, nullptr, &descriptorPool),
              "vkCreateDescriptorPool");
        VkDescriptorSetAllocateInfo allocate{};
        allocate.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
        allocate.descriptorPool = descriptorPool;
        allocate.descriptorSetCount = 1;
        allocate.pSetLayouts = &descriptorSetLayout;
        check(vkAllocateDescriptorSets(device, &allocate, &descriptorSet),
              "vkAllocateDescriptorSets");
        VkSamplerCreateInfo samplerInfo{};
        samplerInfo.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
        samplerInfo.magFilter = VK_FILTER_LINEAR;
        samplerInfo.minFilter = VK_FILTER_LINEAR;
        samplerInfo.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
        samplerInfo.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        samplerInfo.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        samplerInfo.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        samplerInfo.maxLod = 0.0F;
        check(vkCreateSampler(device, &samplerInfo, nullptr, &sampler), "vkCreateSampler");
        VkDescriptorImageInfo imageInfo{};
        imageInfo.sampler = sampler;
        imageInfo.imageView = sceneImage.view;
        imageInfo.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        VkWriteDescriptorSet write{};
        write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        write.dstSet = descriptorSet;
        write.dstBinding = 0;
        write.descriptorCount = 1;
        write.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        write.pImageInfo = &imageInfo;
        vkUpdateDescriptorSets(device, 1, &write, 0, nullptr);
    }

    VkShaderModule shader(const uint32_t *words, size_t bytes) {
        VkShaderModuleCreateInfo info{};
        info.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
        info.codeSize = bytes;
        info.pCode = words;
        VkShaderModule module = VK_NULL_HANDLE;
        check(vkCreateShaderModule(device, &info, nullptr, &module), "vkCreateShaderModule");
        return module;
    }

    VkPipeline createPipeline(VkRenderPass renderPass, VkPipelineLayout layout,
                              VkShaderModule vertex, VkShaderModule fragment, bool depth) {
        VkPipelineShaderStageCreateInfo stages[2]{};
        stages[0].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        stages[0].stage = VK_SHADER_STAGE_VERTEX_BIT;
        stages[0].module = vertex;
        stages[0].pName = "main";
        stages[1].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        stages[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT;
        stages[1].module = fragment;
        stages[1].pName = "main";
        VkPipelineVertexInputStateCreateInfo vertexInput{};
        vertexInput.sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;
        VkPipelineInputAssemblyStateCreateInfo assembly{};
        assembly.sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO;
        assembly.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
        VkViewport viewport{0.0F, 0.0F, static_cast<float>(kWidth), static_cast<float>(kHeight),
                            0.0F, 1.0F};
        VkRect2D scissor{{0, 0}, {kWidth, kHeight}};
        VkPipelineViewportStateCreateInfo viewportState{};
        viewportState.sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO;
        viewportState.viewportCount = 1;
        viewportState.pViewports = &viewport;
        viewportState.scissorCount = 1;
        viewportState.pScissors = &scissor;
        VkPipelineRasterizationStateCreateInfo raster{};
        raster.sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO;
        raster.polygonMode = VK_POLYGON_MODE_FILL;
        raster.cullMode = VK_CULL_MODE_NONE;
        raster.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;
        raster.lineWidth = 1.0F;
        VkPipelineMultisampleStateCreateInfo multisample{};
        multisample.sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO;
        multisample.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;
        VkPipelineDepthStencilStateCreateInfo depthState{};
        depthState.sType = VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO;
        depthState.depthTestEnable = depth ? VK_TRUE : VK_FALSE;
        depthState.depthWriteEnable = depth ? VK_TRUE : VK_FALSE;
        depthState.depthCompareOp = VK_COMPARE_OP_LESS;
        VkPipelineColorBlendAttachmentState blendAttachment{};
        blendAttachment.colorWriteMask = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT
                | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT;
        VkPipelineColorBlendStateCreateInfo blend{};
        blend.sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO;
        blend.attachmentCount = 1;
        blend.pAttachments = &blendAttachment;
        VkGraphicsPipelineCreateInfo info{};
        info.sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO;
        info.stageCount = 2;
        info.pStages = stages;
        info.pVertexInputState = &vertexInput;
        info.pInputAssemblyState = &assembly;
        info.pViewportState = &viewportState;
        info.pRasterizationState = &raster;
        info.pMultisampleState = &multisample;
        info.pDepthStencilState = &depthState;
        info.pColorBlendState = &blend;
        info.layout = layout;
        info.renderPass = renderPass;
        info.subpass = 0;
        VkPipeline pipeline = VK_NULL_HANDLE;
        check(vkCreateGraphicsPipelines(device, VK_NULL_HANDLE, 1, &info, nullptr, &pipeline),
              "vkCreateGraphicsPipelines");
        return pipeline;
    }

    void createPipelines() {
        stage = "create_visual_pipelines";
        VkPushConstantRange push{};
        push.stageFlags = VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT;
        push.offset = 0;
        push.size = sizeof(PushConstants);
        VkPipelineLayoutCreateInfo sceneLayoutInfo{};
        sceneLayoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
        sceneLayoutInfo.pushConstantRangeCount = 1;
        sceneLayoutInfo.pPushConstantRanges = &push;
        check(vkCreatePipelineLayout(device, &sceneLayoutInfo, nullptr, &scenePipelineLayout),
              "vkCreatePipelineLayout(scene)");
        VkPipelineLayoutCreateInfo finalLayoutInfo = sceneLayoutInfo;
        finalLayoutInfo.setLayoutCount = 1;
        finalLayoutInfo.pSetLayouts = &descriptorSetLayout;
        check(vkCreatePipelineLayout(device, &finalLayoutInfo, nullptr, &finalPipelineLayout),
              "vkCreatePipelineLayout(final)");
        VkShaderModule sceneVertex = shader(kSceneVertexSpirv, sizeof(kSceneVertexSpirv));
        VkShaderModule sceneFragment = shader(kSceneFragmentSpirv, sizeof(kSceneFragmentSpirv));
        VkShaderModule postVertex = shader(kPostVertexSpirv, sizeof(kPostVertexSpirv));
        VkShaderModule postFragment = shader(kPostFragmentSpirv, sizeof(kPostFragmentSpirv));
        try {
            scenePipeline = createPipeline(sceneRenderPass, scenePipelineLayout,
                                           sceneVertex, sceneFragment, true);
            finalPipeline = createPipeline(finalRenderPass, finalPipelineLayout,
                                           postVertex, postFragment, false);
        } catch (...) {
            vkDestroyShaderModule(device, sceneVertex, nullptr);
            vkDestroyShaderModule(device, sceneFragment, nullptr);
            vkDestroyShaderModule(device, postVertex, nullptr);
            vkDestroyShaderModule(device, postFragment, nullptr);
            throw;
        }
        vkDestroyShaderModule(device, sceneVertex, nullptr);
        vkDestroyShaderModule(device, sceneFragment, nullptr);
        vkDestroyShaderModule(device, postVertex, nullptr);
        vkDestroyShaderModule(device, postFragment, nullptr);
    }

    void createCommandResources() {
        VkCommandPoolCreateInfo poolInfo{};
        poolInfo.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
        poolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
        poolInfo.queueFamilyIndex = queueFamilyIndex;
        check(vkCreateCommandPool(device, &poolInfo, nullptr, &commandPool), "vkCreateCommandPool");
        VkCommandBufferAllocateInfo allocate{};
        allocate.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
        allocate.commandPool = commandPool;
        allocate.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        allocate.commandBufferCount = 1;
        check(vkAllocateCommandBuffers(device, &allocate, &commandBuffer),
              "vkAllocateCommandBuffers");
        timestampsSupported = queueFamilyProperties.timestampValidBits > 0;
        if (timestampsSupported) {
            VkQueryPoolCreateInfo queryInfo{};
            queryInfo.sType = VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO;
            queryInfo.queryType = VK_QUERY_TYPE_TIMESTAMP;
            queryInfo.queryCount = 2;
            if (vkCreateQueryPool(device, &queryInfo, nullptr, &queryPool) != VK_SUCCESS) {
                timestampsSupported = false;
                queryPool = VK_NULL_HANDLE;
            }
        }
    }

    void createSynchronization() {
        VkSemaphoreCreateInfo semaphoreInfo{};
        semaphoreInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
        check(vkCreateSemaphore(device, &semaphoreInfo, nullptr, &imageAvailable),
              "vkCreateSemaphore(imageAvailable)");
        check(vkCreateSemaphore(device, &semaphoreInfo, nullptr, &renderFinished),
              "vkCreateSemaphore(renderFinished)");
        VkFenceCreateInfo fenceInfo{};
        fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
        fenceInfo.flags = VK_FENCE_CREATE_SIGNALED_BIT;
        check(vkCreateFence(device, &fenceInfo, nullptr, &frameFence), "vkCreateFence");
    }

    double renderFrame(uint32_t frame, bool checkpoint, const std::string &checkpointFile) {
        stage = "render_visible_frame";
        check(vkWaitForFences(device, 1, &frameFence, VK_TRUE, UINT64_MAX), "vkWaitForFences");
        check(vkResetFences(device, 1, &frameFence), "vkResetFences");
        uint32_t imageIndex = 0;
        VkResult acquire = vkAcquireNextImageKHR(device, swapchain, UINT64_MAX,
                                                 imageAvailable, VK_NULL_HANDLE, &imageIndex);
        if (acquire != VK_SUCCESS && acquire != VK_SUBOPTIMAL_KHR) {
            check(acquire, "vkAcquireNextImageKHR");
        }
        check(vkResetCommandBuffer(commandBuffer, 0), "vkResetCommandBuffer");
        VkCommandBufferBeginInfo begin{};
        begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
        begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        check(vkBeginCommandBuffer(commandBuffer, &begin), "vkBeginCommandBuffer");
        if (timestampsSupported) {
            vkCmdResetQueryPool(commandBuffer, queryPool, 0, 2);
            vkCmdWriteTimestamp(commandBuffer, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, queryPool, 0);
        }

        PushConstants push{};
        push.timeSeconds = static_cast<float>(frame) / 60.0F;
        push.sceneKind = static_cast<uint32_t>(sceneKind);
        push.resolution[0] = static_cast<float>(kWidth);
        push.resolution[1] = static_cast<float>(kHeight);
        VkClearValue sceneClears[2]{};
        sceneClears[0].color = {{0.018F, 0.026F, 0.045F, 1.0F}};
        sceneClears[1].depthStencil = {1.0F, 0};
        VkRenderPassBeginInfo sceneBegin{};
        sceneBegin.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
        sceneBegin.renderPass = sceneRenderPass;
        sceneBegin.framebuffer = sceneFramebuffer;
        sceneBegin.renderArea.extent = {kWidth, kHeight};
        sceneBegin.clearValueCount = 2;
        sceneBegin.pClearValues = sceneClears;
        vkCmdBeginRenderPass(commandBuffer, &sceneBegin, VK_SUBPASS_CONTENTS_INLINE);
        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, scenePipeline);
        vkCmdPushConstants(commandBuffer, scenePipelineLayout,
                           VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT,
                           0, sizeof(push), &push);
        vkCmdDraw(commandBuffer, 6, kInstanceCount, 0, 0);
        vkCmdEndRenderPass(commandBuffer);

        VkClearValue finalClear{};
        finalClear.color = {{0.0F, 0.0F, 0.0F, 1.0F}};
        VkRenderPassBeginInfo finalBegin{};
        finalBegin.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
        finalBegin.renderPass = finalRenderPass;
        finalBegin.framebuffer = finalFramebuffer;
        finalBegin.renderArea.extent = {kWidth, kHeight};
        finalBegin.clearValueCount = 1;
        finalBegin.pClearValues = &finalClear;
        vkCmdBeginRenderPass(commandBuffer, &finalBegin, VK_SUBPASS_CONTENTS_INLINE);
        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, finalPipeline);
        vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS,
                                finalPipelineLayout, 0, 1, &descriptorSet, 0, nullptr);
        vkCmdPushConstants(commandBuffer, finalPipelineLayout,
                           VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT,
                           0, sizeof(push), &push);
        vkCmdDraw(commandBuffer, 3, 1, 0, 0);
        vkCmdEndRenderPass(commandBuffer);

        if (checkpoint) {
            VkBufferImageCopy copy{};
            copy.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
            copy.imageSubresource.layerCount = 1;
            copy.imageExtent = {kWidth, kHeight, 1};
            vkCmdCopyImageToBuffer(commandBuffer, finalImage.image,
                                   VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                                   readbackBuffer, 1, &copy);
        }

        VkImageMemoryBarrier toDestination{};
        toDestination.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        toDestination.srcAccessMask = 0;
        toDestination.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        toDestination.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        toDestination.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
        toDestination.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        toDestination.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        toDestination.image = swapchainImages[imageIndex];
        toDestination.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        toDestination.subresourceRange.levelCount = 1;
        toDestination.subresourceRange.layerCount = 1;
        vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                             VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr,
                             1, &toDestination);
        VkImageBlit blit{};
        blit.srcSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        blit.srcSubresource.layerCount = 1;
        blit.srcOffsets[1] = {static_cast<int32_t>(kWidth), static_cast<int32_t>(kHeight), 1};
        blit.dstSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        blit.dstSubresource.layerCount = 1;
        blit.dstOffsets[1] = {static_cast<int32_t>(surfaceExtent.width),
                              static_cast<int32_t>(surfaceExtent.height), 1};
        vkCmdBlitImage(commandBuffer, finalImage.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                       swapchainImages[imageIndex], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                       1, &blit, VK_FILTER_NEAREST);
        VkImageMemoryBarrier toPresent = toDestination;
        toPresent.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        toPresent.dstAccessMask = 0;
        toPresent.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
        toPresent.newLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
        vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT,
                             VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0, nullptr, 0, nullptr,
                             1, &toPresent);
        if (timestampsSupported) {
            vkCmdWriteTimestamp(commandBuffer, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, queryPool, 1);
        }
        check(vkEndCommandBuffer(commandBuffer), "vkEndCommandBuffer");

        VkPipelineStageFlags waitStage = VK_PIPELINE_STAGE_TRANSFER_BIT;
        VkSubmitInfo submit{};
        submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        submit.waitSemaphoreCount = 1;
        submit.pWaitSemaphores = &imageAvailable;
        submit.pWaitDstStageMask = &waitStage;
        submit.commandBufferCount = 1;
        submit.pCommandBuffers = &commandBuffer;
        submit.signalSemaphoreCount = 1;
        submit.pSignalSemaphores = &renderFinished;
        check(vkQueueSubmit(queue, 1, &submit, frameFence), "vkQueueSubmit");
        VkPresentInfoKHR present{};
        present.sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR;
        present.waitSemaphoreCount = 1;
        present.pWaitSemaphores = &renderFinished;
        present.swapchainCount = 1;
        present.pSwapchains = &swapchain;
        present.pImageIndices = &imageIndex;
        VkResult presentResult = vkQueuePresentKHR(queue, &present);
        if (presentResult != VK_SUCCESS && presentResult != VK_SUBOPTIMAL_KHR) {
            check(presentResult, "vkQueuePresentKHR");
        }
        check(vkWaitForFences(device, 1, &frameFence, VK_TRUE, UINT64_MAX),
              "vkWaitForFences(frame)");

        if (checkpoint) writeCheckpoint(checkpointFile);
        if (!timestampsSupported) return 0.0;
        uint64_t timestamps[2]{};
        VkResult query = vkGetQueryPoolResults(device, queryPool, 0, 2, sizeof(timestamps),
                                               timestamps, sizeof(uint64_t),
                                               VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WAIT_BIT);
        if (query != VK_SUCCESS || timestamps[1] < timestamps[0]) return 0.0;
        return static_cast<double>(timestamps[1] - timestamps[0])
                * static_cast<double>(properties.limits.timestampPeriod) / 1.0e6;
    }

    void writeCheckpoint(const std::string &path) {
        if (path.empty()) throw std::runtime_error("Checkpoint path is empty");
        const VkDeviceSize bytes = static_cast<VkDeviceSize>(kWidth) * kHeight * 4;
        if (!readbackCoherent) {
            VkMappedMemoryRange range{};
            range.sType = VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE;
            range.memory = readbackMemory;
            range.offset = 0;
            range.size = VK_WHOLE_SIZE;
            check(vkInvalidateMappedMemoryRanges(device, 1, &range),
                  "vkInvalidateMappedMemoryRanges");
        }
        void *mapped = nullptr;
        check(vkMapMemory(device, readbackMemory, 0, bytes, 0, &mapped), "vkMapMemory(readback)");
        std::ofstream output(path, std::ios::binary | std::ios::trunc);
        if (!output) {
            vkUnmapMemory(device, readbackMemory);
            throw std::runtime_error("Unable to open visual checkpoint output");
        }
        output.write(static_cast<const char *>(mapped), static_cast<std::streamsize>(bytes));
        output.close();
        vkUnmapMemory(device, readbackMemory);
        if (!output) throw std::runtime_error("Unable to write visual checkpoint output");
    }

    static std::string checkpointPath(const std::string &prefix, uint32_t frame) {
        std::ostringstream output;
        output << prefix << "-f" << std::setw(4) << std::setfill('0') << frame << ".rgba";
        return output.str();
    }

    void destroyImage(ImageResource &resource) {
        if (device == VK_NULL_HANDLE) return;
        if (resource.view != VK_NULL_HANDLE) vkDestroyImageView(device, resource.view, nullptr);
        if (resource.image != VK_NULL_HANDLE) vkDestroyImage(device, resource.image, nullptr);
        if (resource.memory != VK_NULL_HANDLE) vkFreeMemory(device, resource.memory, nullptr);
        resource = {};
    }

    void cleanup() {
        if (device != VK_NULL_HANDLE && vkDeviceWaitIdle != nullptr) vkDeviceWaitIdle(device);
        if (device != VK_NULL_HANDLE) {
            if (frameFence != VK_NULL_HANDLE) vkDestroyFence(device, frameFence, nullptr);
            if (renderFinished != VK_NULL_HANDLE) vkDestroySemaphore(device, renderFinished, nullptr);
            if (imageAvailable != VK_NULL_HANDLE) vkDestroySemaphore(device, imageAvailable, nullptr);
            if (queryPool != VK_NULL_HANDLE) vkDestroyQueryPool(device, queryPool, nullptr);
            if (commandPool != VK_NULL_HANDLE) vkDestroyCommandPool(device, commandPool, nullptr);
            if (finalPipeline != VK_NULL_HANDLE) vkDestroyPipeline(device, finalPipeline, nullptr);
            if (scenePipeline != VK_NULL_HANDLE) vkDestroyPipeline(device, scenePipeline, nullptr);
            if (finalPipelineLayout != VK_NULL_HANDLE) vkDestroyPipelineLayout(device, finalPipelineLayout, nullptr);
            if (scenePipelineLayout != VK_NULL_HANDLE) vkDestroyPipelineLayout(device, scenePipelineLayout, nullptr);
            if (sampler != VK_NULL_HANDLE) vkDestroySampler(device, sampler, nullptr);
            if (descriptorPool != VK_NULL_HANDLE) vkDestroyDescriptorPool(device, descriptorPool, nullptr);
            if (descriptorSetLayout != VK_NULL_HANDLE) vkDestroyDescriptorSetLayout(device, descriptorSetLayout, nullptr);
            if (finalFramebuffer != VK_NULL_HANDLE) vkDestroyFramebuffer(device, finalFramebuffer, nullptr);
            if (sceneFramebuffer != VK_NULL_HANDLE) vkDestroyFramebuffer(device, sceneFramebuffer, nullptr);
            if (finalRenderPass != VK_NULL_HANDLE) vkDestroyRenderPass(device, finalRenderPass, nullptr);
            if (sceneRenderPass != VK_NULL_HANDLE) vkDestroyRenderPass(device, sceneRenderPass, nullptr);
            if (readbackBuffer != VK_NULL_HANDLE) vkDestroyBuffer(device, readbackBuffer, nullptr);
            if (readbackMemory != VK_NULL_HANDLE) vkFreeMemory(device, readbackMemory, nullptr);
            destroyImage(finalImage);
            destroyImage(depthImage);
            destroyImage(sceneImage);
            if (swapchain != VK_NULL_HANDLE) vkDestroySwapchainKHR(device, swapchain, nullptr);
            vkDestroyDevice(device, nullptr);
            device = VK_NULL_HANDLE;
        }
        if (instance != VK_NULL_HANDLE) {
            if (surface != VK_NULL_HANDLE) vkDestroySurfaceKHR(instance, surface, nullptr);
            vkDestroyInstance(instance, nullptr);
            instance = VK_NULL_HANDLE;
        }
        if (nativeWindow != nullptr) {
            ANativeWindow_release(nativeWindow);
            nativeWindow = nullptr;
        }
        // adrenotools hooks intentionally live until the short-lived runner process exits.
        library = nullptr;
    }

    std::string sceneId;
    int sceneKind = -1;
    std::string stage = "not_started";
    bool customDriver = false;
    void *library = nullptr;
    ANativeWindow *nativeWindow = nullptr;

    PFN_vkGetInstanceProcAddr getInstanceProcAddr = nullptr;
    PFN_vkCreateInstance createInstance = nullptr;
    PFN_vkEnumerateInstanceExtensionProperties enumerateInstanceExtensionProperties = nullptr;
    VkInstance instance = VK_NULL_HANDLE;
    VkSurfaceKHR surface = VK_NULL_HANDLE;
    VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
    VkPhysicalDeviceProperties properties{};
    VkPhysicalDeviceMemoryProperties memoryProperties{};
    VkQueueFamilyProperties queueFamilyProperties{};
    uint32_t queueFamilyIndex = 0;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    VkSwapchainKHR swapchain = VK_NULL_HANDLE;
    std::vector<VkImage> swapchainImages;
    VkSurfaceFormatKHR surfaceFormat{};
    VkPresentModeKHR presentMode = VK_PRESENT_MODE_FIFO_KHR;
    VkExtent2D surfaceExtent{};

    ImageResource sceneImage;
    ImageResource depthImage;
    ImageResource finalImage;
    VkFormat depthFormat = VK_FORMAT_UNDEFINED;
    VkBuffer readbackBuffer = VK_NULL_HANDLE;
    VkDeviceMemory readbackMemory = VK_NULL_HANDLE;
    bool readbackCoherent = false;
    VkRenderPass sceneRenderPass = VK_NULL_HANDLE;
    VkRenderPass finalRenderPass = VK_NULL_HANDLE;
    VkFramebuffer sceneFramebuffer = VK_NULL_HANDLE;
    VkFramebuffer finalFramebuffer = VK_NULL_HANDLE;
    VkDescriptorSetLayout descriptorSetLayout = VK_NULL_HANDLE;
    VkDescriptorPool descriptorPool = VK_NULL_HANDLE;
    VkDescriptorSet descriptorSet = VK_NULL_HANDLE;
    VkSampler sampler = VK_NULL_HANDLE;
    VkPipelineLayout scenePipelineLayout = VK_NULL_HANDLE;
    VkPipelineLayout finalPipelineLayout = VK_NULL_HANDLE;
    VkPipeline scenePipeline = VK_NULL_HANDLE;
    VkPipeline finalPipeline = VK_NULL_HANDLE;
    VkCommandPool commandPool = VK_NULL_HANDLE;
    VkCommandBuffer commandBuffer = VK_NULL_HANDLE;
    VkQueryPool queryPool = VK_NULL_HANDLE;
    bool timestampsSupported = false;
    VkSemaphore imageAvailable = VK_NULL_HANDLE;
    VkSemaphore renderFinished = VK_NULL_HANDLE;
    VkFence frameFence = VK_NULL_HANDLE;

    PFN_vkDestroyInstance vkDestroyInstance = nullptr;
    PFN_vkEnumeratePhysicalDevices vkEnumeratePhysicalDevices = nullptr;
    PFN_vkGetPhysicalDeviceProperties vkGetPhysicalDeviceProperties = nullptr;
    PFN_vkGetPhysicalDeviceMemoryProperties vkGetPhysicalDeviceMemoryProperties = nullptr;
    PFN_vkGetPhysicalDeviceQueueFamilyProperties vkGetPhysicalDeviceQueueFamilyProperties = nullptr;
    PFN_vkEnumerateDeviceExtensionProperties vkEnumerateDeviceExtensionProperties = nullptr;
    PFN_vkGetPhysicalDeviceFormatProperties vkGetPhysicalDeviceFormatProperties = nullptr;
    PFN_vkCreateDevice vkCreateDevice = nullptr;
    PFN_vkGetDeviceProcAddr getDeviceProcAddr = nullptr;
    PFN_vkCreateAndroidSurfaceKHR vkCreateAndroidSurfaceKHR = nullptr;
    PFN_vkDestroySurfaceKHR vkDestroySurfaceKHR = nullptr;
    PFN_vkGetPhysicalDeviceSurfaceSupportKHR vkGetPhysicalDeviceSurfaceSupportKHR = nullptr;
    PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR vkGetPhysicalDeviceSurfaceCapabilitiesKHR = nullptr;
    PFN_vkGetPhysicalDeviceSurfaceFormatsKHR vkGetPhysicalDeviceSurfaceFormatsKHR = nullptr;
    PFN_vkGetPhysicalDeviceSurfacePresentModesKHR vkGetPhysicalDeviceSurfacePresentModesKHR = nullptr;

    PFN_vkDestroyDevice vkDestroyDevice = nullptr;
    PFN_vkGetDeviceQueue vkGetDeviceQueue = nullptr;
    PFN_vkCreateSwapchainKHR vkCreateSwapchainKHR = nullptr;
    PFN_vkDestroySwapchainKHR vkDestroySwapchainKHR = nullptr;
    PFN_vkGetSwapchainImagesKHR vkGetSwapchainImagesKHR = nullptr;
    PFN_vkAcquireNextImageKHR vkAcquireNextImageKHR = nullptr;
    PFN_vkQueuePresentKHR vkQueuePresentKHR = nullptr;
    PFN_vkCreateImage vkCreateImage = nullptr;
    PFN_vkDestroyImage vkDestroyImage = nullptr;
    PFN_vkGetImageMemoryRequirements vkGetImageMemoryRequirements = nullptr;
    PFN_vkBindImageMemory vkBindImageMemory = nullptr;
    PFN_vkCreateImageView vkCreateImageView = nullptr;
    PFN_vkDestroyImageView vkDestroyImageView = nullptr;
    PFN_vkAllocateMemory vkAllocateMemory = nullptr;
    PFN_vkFreeMemory vkFreeMemory = nullptr;
    PFN_vkCreateBuffer vkCreateBuffer = nullptr;
    PFN_vkDestroyBuffer vkDestroyBuffer = nullptr;
    PFN_vkGetBufferMemoryRequirements vkGetBufferMemoryRequirements = nullptr;
    PFN_vkBindBufferMemory vkBindBufferMemory = nullptr;
    PFN_vkMapMemory vkMapMemory = nullptr;
    PFN_vkUnmapMemory vkUnmapMemory = nullptr;
    PFN_vkInvalidateMappedMemoryRanges vkInvalidateMappedMemoryRanges = nullptr;
    PFN_vkCreateRenderPass vkCreateRenderPass = nullptr;
    PFN_vkDestroyRenderPass vkDestroyRenderPass = nullptr;
    PFN_vkCreateFramebuffer vkCreateFramebuffer = nullptr;
    PFN_vkDestroyFramebuffer vkDestroyFramebuffer = nullptr;
    PFN_vkCreateShaderModule vkCreateShaderModule = nullptr;
    PFN_vkDestroyShaderModule vkDestroyShaderModule = nullptr;
    PFN_vkCreateDescriptorSetLayout vkCreateDescriptorSetLayout = nullptr;
    PFN_vkDestroyDescriptorSetLayout vkDestroyDescriptorSetLayout = nullptr;
    PFN_vkCreateDescriptorPool vkCreateDescriptorPool = nullptr;
    PFN_vkDestroyDescriptorPool vkDestroyDescriptorPool = nullptr;
    PFN_vkAllocateDescriptorSets vkAllocateDescriptorSets = nullptr;
    PFN_vkUpdateDescriptorSets vkUpdateDescriptorSets = nullptr;
    PFN_vkCreateSampler vkCreateSampler = nullptr;
    PFN_vkDestroySampler vkDestroySampler = nullptr;
    PFN_vkCreatePipelineLayout vkCreatePipelineLayout = nullptr;
    PFN_vkDestroyPipelineLayout vkDestroyPipelineLayout = nullptr;
    PFN_vkCreateGraphicsPipelines vkCreateGraphicsPipelines = nullptr;
    PFN_vkDestroyPipeline vkDestroyPipeline = nullptr;
    PFN_vkCreateCommandPool vkCreateCommandPool = nullptr;
    PFN_vkDestroyCommandPool vkDestroyCommandPool = nullptr;
    PFN_vkAllocateCommandBuffers vkAllocateCommandBuffers = nullptr;
    PFN_vkResetCommandBuffer vkResetCommandBuffer = nullptr;
    PFN_vkBeginCommandBuffer vkBeginCommandBuffer = nullptr;
    PFN_vkEndCommandBuffer vkEndCommandBuffer = nullptr;
    PFN_vkCmdPipelineBarrier vkCmdPipelineBarrier = nullptr;
    PFN_vkCmdBeginRenderPass vkCmdBeginRenderPass = nullptr;
    PFN_vkCmdEndRenderPass vkCmdEndRenderPass = nullptr;
    PFN_vkCmdBindPipeline vkCmdBindPipeline = nullptr;
    PFN_vkCmdBindDescriptorSets vkCmdBindDescriptorSets = nullptr;
    PFN_vkCmdPushConstants vkCmdPushConstants = nullptr;
    PFN_vkCmdDraw vkCmdDraw = nullptr;
    PFN_vkCmdBlitImage vkCmdBlitImage = nullptr;
    PFN_vkCmdCopyImageToBuffer vkCmdCopyImageToBuffer = nullptr;
    PFN_vkCreateSemaphore vkCreateSemaphore = nullptr;
    PFN_vkDestroySemaphore vkDestroySemaphore = nullptr;
    PFN_vkCreateFence vkCreateFence = nullptr;
    PFN_vkDestroyFence vkDestroyFence = nullptr;
    PFN_vkWaitForFences vkWaitForFences = nullptr;
    PFN_vkResetFences vkResetFences = nullptr;
    PFN_vkQueueSubmit vkQueueSubmit = nullptr;
    PFN_vkDeviceWaitIdle vkDeviceWaitIdle = nullptr;
    PFN_vkCreateQueryPool vkCreateQueryPool = nullptr;
    PFN_vkDestroyQueryPool vkDestroyQueryPool = nullptr;
    PFN_vkCmdResetQueryPool vkCmdResetQueryPool = nullptr;
    PFN_vkCmdWriteTimestamp vkCmdWriteTimestamp = nullptr;
    PFN_vkGetQueryPoolResults vkGetQueryPoolResults = nullptr;
};

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_amaral_driverlab_VisualRunnerActivity_runNativeVisualScene(
        JNIEnv *environment,
        jclass,
        jobject surface,
        jstring sceneId,
        jstring driverDirectory,
        jstring driverName,
        jstring nativeLibraryDirectory,
        jstring temporaryDirectory,
        jint warmupSeconds,
        jint measureSeconds,
        jstring rawPrefix) {
    VisualRenderer renderer;
    try {
        renderer.initialize(environment, surface,
                            UtfString(environment, sceneId).string(),
                            UtfString(environment, driverDirectory).string(),
                            UtfString(environment, driverName).string(),
                            UtfString(environment, nativeLibraryDirectory).string(),
                            UtfString(environment, temporaryDirectory).string());
        const std::string result = renderer.run(warmupSeconds, measureSeconds,
                                                UtfString(environment, rawPrefix).string());
        return environment->NewStringUTF(result.c_str());
    } catch (const std::exception &error) {
        const std::string result = renderer.failureJson(error);
        return environment->NewStringUTF(result.c_str());
    }
}
