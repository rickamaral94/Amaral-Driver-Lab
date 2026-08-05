#include <jni.h>

#include <adrenotools/driver.h>
#include <dlfcn.h>
#include <vulkan/vulkan.h>

#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <fstream>
#include <iomanip>
#include <limits>
#include <sstream>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

namespace {

constexpr uint32_t kWidth = 256;
constexpr uint32_t kHeight = 256;
constexpr VkFormat kColorFormat = VK_FORMAT_R8G8B8A8_UNORM;
constexpr char kWorkloadId[] = "render_correctness_offscreen";
constexpr uint32_t kWorkloadVersion = 1;

static const uint32_t kVertexShaderSpirv[] =
#include "render_correctness_vert.inc"
;

static const uint32_t kFragmentShaderSpirv[] =
#include "render_correctness_frag.inc"
;

struct Vertex {
    float position[2];
    float color[3];
};

constexpr std::array<Vertex, 12> kVertices{{
        {{-1.0F, -1.0F}, {0.08F, 0.18F, 0.72F}},
        {{3.0F, -1.0F}, {0.92F, 0.12F, 0.22F}},
        {{-1.0F, 3.0F}, {0.12F, 0.88F, 0.28F}},

        {{-0.78F, -0.62F}, {0.96F, 0.76F, 0.08F}},
        {{-0.08F, -0.58F}, {0.15F, 0.92F, 0.86F}},
        {{-0.42F, 0.18F}, {0.92F, 0.16F, 0.78F}},

        {{0.08F, -0.34F}, {0.12F, 0.18F, 0.98F}},
        {{0.82F, -0.28F}, {0.98F, 0.38F, 0.10F}},
        {{0.48F, 0.56F}, {0.10F, 0.94F, 0.34F}},

        {{-0.62F, 0.32F}, {0.88F, 0.20F, 0.18F}},
        {{-0.08F, 0.46F}, {0.20F, 0.34F, 0.96F}},
        {{-0.38F, 0.88F}, {0.18F, 0.92F, 0.44F}},
}};

std::string jsonEscape(const std::string &value) {
    std::ostringstream escaped;
    for (unsigned char character : value) {
        switch (character) {
            case '"': escaped << "\\\""; break;
            case '\\': escaped << "\\\\"; break;
            case '\b': escaped << "\\b"; break;
            case '\f': escaped << "\\f"; break;
            case '\n': escaped << "\\n"; break;
            case '\r': escaped << "\\r"; break;
            case '\t': escaped << "\\t"; break;
            default:
                if (character < 0x20) {
                    escaped << "\\u" << std::hex << std::setw(4) << std::setfill('0')
                            << static_cast<int>(character) << std::dec;
                } else {
                    escaped << character;
                }
        }
    }
    return escaped.str();
}

std::string withTrailingSlash(std::string path) {
    if (!path.empty() && path.back() != '/') path.push_back('/');
    return path;
}

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

template <typename Function>
Function requireSymbol(void *library, const char *name) {
    auto function = reinterpret_cast<Function>(dlsym(library, name));
    if (function == nullptr) {
        throw std::runtime_error(std::string("Missing Vulkan symbol: ") + name);
    }
    return function;
}

template <typename Function>
Function optionalInstance(PFN_vkGetInstanceProcAddr getter, VkInstance instance, const char *name) {
    return reinterpret_cast<Function>(getter(instance, name));
}

template <typename Function>
Function requireInstance(PFN_vkGetInstanceProcAddr getter, VkInstance instance, const char *name) {
    Function function = optionalInstance<Function>(getter, instance, name);
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

std::string versionString(uint32_t version) {
    std::ostringstream output;
    output << VK_VERSION_MAJOR(version) << '.' << VK_VERSION_MINOR(version)
           << '.' << VK_VERSION_PATCH(version);
    return output.str();
}

bool hasExtension(const std::vector<VkExtensionProperties> &extensions, const char *name) {
    return std::any_of(extensions.begin(), extensions.end(), [name](const auto &extension) {
        return std::strcmp(extension.extensionName, name) == 0;
    });
}

std::pair<int, int> parseMesaMajorMinor(const std::string &value) {
    const std::string marker = "Mesa";
    size_t position = value.find(marker);
    if (position == std::string::npos) return {-1, -1};
    position += marker.size();
    while (position < value.size() && (value[position] < '0' || value[position] > '9')) ++position;
    if (position >= value.size()) return {-1, -1};
    int major = 0;
    while (position < value.size() && value[position] >= '0' && value[position] <= '9') {
        major = major * 10 + (value[position] - '0');
        ++position;
    }
    if (position >= value.size() || value[position] != '.') return {major, -1};
    ++position;
    int minor = 0;
    bool foundMinor = false;
    while (position < value.size() && value[position] >= '0' && value[position] <= '9') {
        foundMinor = true;
        minor = minor * 10 + (value[position] - '0');
        ++position;
    }
    return {major, foundMinor ? minor : -1};
}

const char *driverIdName(VkDriverId id) {
    switch (id) {
        case VK_DRIVER_ID_AMD_PROPRIETARY: return "AMD_PROPRIETARY";
        case VK_DRIVER_ID_AMD_OPEN_SOURCE: return "AMD_OPEN_SOURCE";
        case VK_DRIVER_ID_MESA_RADV: return "MESA_RADV";
        case VK_DRIVER_ID_NVIDIA_PROPRIETARY: return "NVIDIA_PROPRIETARY";
        case VK_DRIVER_ID_INTEL_PROPRIETARY_WINDOWS: return "INTEL_PROPRIETARY_WINDOWS";
        case VK_DRIVER_ID_INTEL_OPEN_SOURCE_MESA: return "INTEL_OPEN_SOURCE_MESA";
        case VK_DRIVER_ID_IMAGINATION_PROPRIETARY: return "IMAGINATION_PROPRIETARY";
        case VK_DRIVER_ID_QUALCOMM_PROPRIETARY: return "QUALCOMM_PROPRIETARY";
        case VK_DRIVER_ID_ARM_PROPRIETARY: return "ARM_PROPRIETARY";
        case VK_DRIVER_ID_GOOGLE_SWIFTSHADER: return "GOOGLE_SWIFTSHADER";
        case VK_DRIVER_ID_GGP_PROPRIETARY: return "GGP_PROPRIETARY";
        case VK_DRIVER_ID_BROADCOM_PROPRIETARY: return "BROADCOM_PROPRIETARY";
        case VK_DRIVER_ID_MESA_LLVMPIPE: return "MESA_LLVMPIPE";
        case VK_DRIVER_ID_MOLTENVK: return "MOLTENVK";
        case VK_DRIVER_ID_COREAVI_PROPRIETARY: return "COREAVI_PROPRIETARY";
        case VK_DRIVER_ID_JUICE_PROPRIETARY: return "JUICE_PROPRIETARY";
        case VK_DRIVER_ID_VERISILICON_PROPRIETARY: return "VERISILICON_PROPRIETARY";
        case VK_DRIVER_ID_MESA_TURNIP: return "MESA_TURNIP";
        case VK_DRIVER_ID_MESA_V3DV: return "MESA_V3DV";
        case VK_DRIVER_ID_MESA_PANVK: return "MESA_PANVK";
        case VK_DRIVER_ID_SAMSUNG_PROPRIETARY: return "SAMSUNG_PROPRIETARY";
        case VK_DRIVER_ID_MESA_VENUS: return "MESA_VENUS";
        case VK_DRIVER_ID_MESA_DOZEN: return "MESA_DOZEN";
        case VK_DRIVER_ID_MESA_NVK: return "MESA_NVK";
        case VK_DRIVER_ID_IMAGINATION_OPEN_SOURCE_MESA: return "IMAGINATION_OPEN_SOURCE_MESA";
        default: return "UNKNOWN";
    }
}

class CorrectnessRenderer {
public:
    ~CorrectnessRenderer() { cleanup(); }

    void initialize(const std::string &driverDirectory,
                    const std::string &driverName,
                    const std::string &nativeLibraryDirectory,
                    const std::string &temporaryDirectory) {
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
            const char *message = dlerror();
            throw std::runtime_error(std::string("Unable to open Vulkan loader: ")
                                     + (message == nullptr ? "unknown error" : message));
        }

        getInstanceProcAddr = requireSymbol<PFN_vkGetInstanceProcAddr>(library, "vkGetInstanceProcAddr");
        createInstance = requireSymbol<PFN_vkCreateInstance>(library, "vkCreateInstance");
        enumerateInstanceExtensionProperties = requireSymbol<PFN_vkEnumerateInstanceExtensionProperties>(
                library, "vkEnumerateInstanceExtensionProperties");
        auto enumerateInstanceVersion = reinterpret_cast<PFN_vkEnumerateInstanceVersion>(
                dlsym(library, "vkEnumerateInstanceVersion"));

        uint32_t loaderApiVersion = VK_API_VERSION_1_0;
        if (enumerateInstanceVersion != nullptr) {
            VkResult versionResult = enumerateInstanceVersion(&loaderApiVersion);
            if (versionResult != VK_SUCCESS) loaderApiVersion = VK_API_VERSION_1_0;
        }

        uint32_t instanceExtensionCount = 0;
        check(enumerateInstanceExtensionProperties(nullptr, &instanceExtensionCount, nullptr),
              "vkEnumerateInstanceExtensionProperties(count)");
        std::vector<VkExtensionProperties> instanceExtensions(instanceExtensionCount);
        if (instanceExtensionCount > 0) {
            check(enumerateInstanceExtensionProperties(nullptr, &instanceExtensionCount,
                                                       instanceExtensions.data()),
                  "vkEnumerateInstanceExtensionProperties(list)");
        }

        std::vector<const char *> enabledInstanceExtensions;
        const bool properties2Extension = hasExtension(
                instanceExtensions, VK_KHR_GET_PHYSICAL_DEVICE_PROPERTIES_2_EXTENSION_NAME);
        if (VK_VERSION_MINOR(loaderApiVersion) == 0 && properties2Extension) {
            enabledInstanceExtensions.push_back(VK_KHR_GET_PHYSICAL_DEVICE_PROPERTIES_2_EXTENSION_NAME);
        }

        VkApplicationInfo applicationInfo{};
        applicationInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
        applicationInfo.pApplicationName = "Amaral Driver Lab";
        applicationInfo.applicationVersion = VK_MAKE_VERSION(0, 2, 0);
        applicationInfo.pEngineName = "Deterministic offscreen correctness";
        applicationInfo.engineVersion = VK_MAKE_VERSION(1, 0, 0);
        applicationInfo.apiVersion = loaderApiVersion >= VK_API_VERSION_1_1
                ? VK_API_VERSION_1_1 : VK_API_VERSION_1_0;

        VkInstanceCreateInfo instanceInfo{};
        instanceInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
        instanceInfo.pApplicationInfo = &applicationInfo;
        instanceInfo.enabledExtensionCount = static_cast<uint32_t>(enabledInstanceExtensions.size());
        instanceInfo.ppEnabledExtensionNames = enabledInstanceExtensions.data();
        stage = "create_instance";
        check(createInstance(&instanceInfo, nullptr, &instance), "vkCreateInstance");

        destroyInstance = requireInstance<PFN_vkDestroyInstance>(getInstanceProcAddr, instance,
                                                                 "vkDestroyInstance");
        enumeratePhysicalDevices = requireInstance<PFN_vkEnumeratePhysicalDevices>(
                getInstanceProcAddr, instance, "vkEnumeratePhysicalDevices");
        getPhysicalDeviceProperties = requireInstance<PFN_vkGetPhysicalDeviceProperties>(
                getInstanceProcAddr, instance, "vkGetPhysicalDeviceProperties");
        getPhysicalDeviceFeatures = requireInstance<PFN_vkGetPhysicalDeviceFeatures>(
                getInstanceProcAddr, instance, "vkGetPhysicalDeviceFeatures");
        getPhysicalDeviceQueueFamilyProperties = requireInstance<PFN_vkGetPhysicalDeviceQueueFamilyProperties>(
                getInstanceProcAddr, instance, "vkGetPhysicalDeviceQueueFamilyProperties");
        getPhysicalDeviceMemoryProperties = requireInstance<PFN_vkGetPhysicalDeviceMemoryProperties>(
                getInstanceProcAddr, instance, "vkGetPhysicalDeviceMemoryProperties");
        getPhysicalDeviceFormatProperties = requireInstance<PFN_vkGetPhysicalDeviceFormatProperties>(
                getInstanceProcAddr, instance, "vkGetPhysicalDeviceFormatProperties");
        enumerateDeviceExtensionProperties = requireInstance<PFN_vkEnumerateDeviceExtensionProperties>(
                getInstanceProcAddr, instance, "vkEnumerateDeviceExtensionProperties");
        createDevice = requireInstance<PFN_vkCreateDevice>(getInstanceProcAddr, instance,
                                                           "vkCreateDevice");
        getDeviceProcAddr = requireInstance<PFN_vkGetDeviceProcAddr>(getInstanceProcAddr, instance,
                                                                    "vkGetDeviceProcAddr");
        getPhysicalDeviceProperties2 = optionalInstance<PFN_vkGetPhysicalDeviceProperties2>(
                getInstanceProcAddr, instance, "vkGetPhysicalDeviceProperties2");
        if (getPhysicalDeviceProperties2 == nullptr) {
            getPhysicalDeviceProperties2 = reinterpret_cast<PFN_vkGetPhysicalDeviceProperties2>(
                    optionalInstance<PFN_vkGetPhysicalDeviceProperties2KHR>(
                            getInstanceProcAddr, instance, "vkGetPhysicalDeviceProperties2KHR"));
        }
        getPhysicalDeviceFeatures2 = optionalInstance<PFN_vkGetPhysicalDeviceFeatures2>(
                getInstanceProcAddr, instance, "vkGetPhysicalDeviceFeatures2");
        if (getPhysicalDeviceFeatures2 == nullptr) {
            getPhysicalDeviceFeatures2 = reinterpret_cast<PFN_vkGetPhysicalDeviceFeatures2>(
                    optionalInstance<PFN_vkGetPhysicalDeviceFeatures2KHR>(
                            getInstanceProcAddr, instance, "vkGetPhysicalDeviceFeatures2KHR"));
        }

        stage = "enumerate_physical_device";
        uint32_t physicalDeviceCount = 0;
        check(enumeratePhysicalDevices(instance, &physicalDeviceCount, nullptr),
              "vkEnumeratePhysicalDevices(count)");
        if (physicalDeviceCount == 0) throw std::runtime_error("Driver exposed no Vulkan device");
        std::vector<VkPhysicalDevice> devices(physicalDeviceCount);
        check(enumeratePhysicalDevices(instance, &physicalDeviceCount, devices.data()),
              "vkEnumeratePhysicalDevices(list)");
        physicalDevice = devices.front();

        getPhysicalDeviceProperties(physicalDevice, &properties);
        getPhysicalDeviceMemoryProperties(physicalDevice, &memoryProperties);
        if (getPhysicalDeviceFeatures2 != nullptr) {
            features2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
            getPhysicalDeviceFeatures2(physicalDevice, &features2);
        } else {
            features2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
            getPhysicalDeviceFeatures(physicalDevice, &features2.features);
        }

        uint32_t deviceExtensionCount = 0;
        check(enumerateDeviceExtensionProperties(physicalDevice, nullptr, &deviceExtensionCount, nullptr),
              "vkEnumerateDeviceExtensionProperties(count)");
        deviceExtensions.resize(deviceExtensionCount);
        if (deviceExtensionCount > 0) {
            check(enumerateDeviceExtensionProperties(physicalDevice, nullptr, &deviceExtensionCount,
                                                     deviceExtensions.data()),
                  "vkEnumerateDeviceExtensionProperties(list)");
        }
        std::sort(deviceExtensions.begin(), deviceExtensions.end(), [](const auto &left, const auto &right) {
            return std::strcmp(left.extensionName, right.extensionName) < 0;
        });

        driverProperties.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DRIVER_PROPERTIES;
        const bool driverPropertiesAvailable = properties.apiVersion >= VK_API_VERSION_1_2
                || hasExtension(deviceExtensions, VK_KHR_DRIVER_PROPERTIES_EXTENSION_NAME);
        if (driverPropertiesAvailable && getPhysicalDeviceProperties2 != nullptr) {
            VkPhysicalDeviceProperties2 properties2{};
            properties2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2;
            properties2.pNext = &driverProperties;
            getPhysicalDeviceProperties2(physicalDevice, &properties2);
            properties = properties2.properties;
            hasDriverProperties = true;
        }
        capabilities = buildCapabilitiesJson();

        uint32_t queueFamilyCount = 0;
        getPhysicalDeviceQueueFamilyProperties(physicalDevice, &queueFamilyCount, nullptr);
        std::vector<VkQueueFamilyProperties> queueFamilies(queueFamilyCount);
        getPhysicalDeviceQueueFamilyProperties(physicalDevice, &queueFamilyCount, queueFamilies.data());
        bool foundGraphicsQueue = false;
        for (uint32_t index = 0; index < queueFamilyCount; ++index) {
            if (queueFamilies[index].queueCount > 0
                    && (queueFamilies[index].queueFlags & VK_QUEUE_GRAPHICS_BIT) != 0) {
                queueFamilyIndex = index;
                queueFamilyProperties = queueFamilies[index];
                foundGraphicsQueue = true;
                break;
            }
        }
        if (!foundGraphicsQueue) throw std::runtime_error("Driver exposed no graphics queue");

        VkFormatProperties formatProperties{};
        getPhysicalDeviceFormatProperties(physicalDevice, kColorFormat, &formatProperties);
        if ((formatProperties.optimalTilingFeatures & VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT) == 0) {
            throw std::runtime_error("RGBA8 UNORM is not supported as an optimal color attachment");
        }

        float priority = 1.0F;
        VkDeviceQueueCreateInfo queueInfo{};
        queueInfo.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
        queueInfo.queueFamilyIndex = queueFamilyIndex;
        queueInfo.queueCount = 1;
        queueInfo.pQueuePriorities = &priority;

        VkDeviceCreateInfo deviceInfo{};
        deviceInfo.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
        deviceInfo.queueCreateInfoCount = 1;
        deviceInfo.pQueueCreateInfos = &queueInfo;
        stage = "create_device";
        check(createDevice(physicalDevice, &deviceInfo, nullptr, &device), "vkCreateDevice");
        loadDeviceFunctions();
        vkGetDeviceQueue(device, queueFamilyIndex, 0, &queue);
        createResources();
    }

    std::string render(const std::string &outputPath) {
        stage = "record_commands";
        recordCommands();

        VkSubmitInfo submitInfo{};
        submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        submitInfo.commandBufferCount = 1;
        submitInfo.pCommandBuffers = &commandBuffer;
        stage = "queue_submit";
        check(vkQueueSubmit(queue, 1, &submitInfo, VK_NULL_HANDLE), "vkQueueSubmit");
        stage = "queue_wait_idle";
        check(vkQueueWaitIdle(queue), "vkQueueWaitIdle");

        stage = "readback";
        void *mapped = nullptr;
        check(vkMapMemory(device, readbackMemory, 0, readbackAllocationSize, 0, &mapped),
              "vkMapMemory(readback)");
        if ((readbackMemoryFlags & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) == 0) {
            VkMappedMemoryRange range{};
            range.sType = VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE;
            range.memory = readbackMemory;
            range.offset = 0;
            range.size = VK_WHOLE_SIZE;
            check(vkInvalidateMappedMemoryRanges(device, 1, &range),
                  "vkInvalidateMappedMemoryRanges(readback)");
        }

        const size_t outputSize = static_cast<size_t>(kWidth) * kHeight * 4U;
        std::ofstream output(outputPath, std::ios::binary | std::ios::trunc);
        if (!output) {
            vkUnmapMemory(device, readbackMemory);
            throw std::runtime_error("Unable to create raw render evidence");
        }
        output.write(static_cast<const char *>(mapped), static_cast<std::streamsize>(outputSize));
        output.close();
        vkUnmapMemory(device, readbackMemory);
        if (!output) throw std::runtime_error("Unable to write raw render evidence");

        std::ostringstream json;
        json << "{\"success\":true"
             << ",\"workload_id\":\"" << kWorkloadId << "\""
             << ",\"workload_version\":" << kWorkloadVersion
             << ",\"custom_driver\":" << (customDriver ? "true" : "false")
             << ",\"image_width\":" << kWidth
             << ",\"image_height\":" << kHeight
             << ",\"image_format\":\"VK_FORMAT_R8G8B8A8_UNORM\""
             << ",\"raw_row_stride_bytes\":" << kWidth * 4U
             << ",\"raw_output_bytes\":" << outputSize
             << ",\"capabilities\":" << capabilities
             << ",\"metric_note\":\"A fixed offscreen scene detects differences in this render path; it does not prove game performance or correctness in other workloads.\""
             << '}';
        return json.str();
    }

    std::string failureJson(const std::exception &error) const {
        std::string failureType = "native_error";
        int vkResult = std::numeric_limits<int>::max();
        std::string operation;
        if (const auto *vulkanFailure = dynamic_cast<const VulkanFailure *>(&error)) {
            vkResult = static_cast<int>(vulkanFailure->result);
            operation = vulkanFailure->operation;
            failureType = vulkanFailure->result == VK_ERROR_DEVICE_LOST
                    ? "vk_error_device_lost" : "vulkan_error";
        }
        std::ostringstream json;
        json << "{\"success\":false"
             << ",\"workload_id\":\"" << kWorkloadId << "\""
             << ",\"workload_version\":" << kWorkloadVersion
             << ",\"custom_driver\":" << (customDriver ? "true" : "false")
             << ",\"failure_type\":\"" << failureType << "\""
             << ",\"failure_stage\":\"" << jsonEscape(stage) << "\""
             << ",\"error\":\"" << jsonEscape(error.what()) << "\"";
        if (!operation.empty()) json << ",\"vulkan_operation\":\"" << jsonEscape(operation) << "\"";
        if (vkResult != std::numeric_limits<int>::max()) json << ",\"vk_result\":" << vkResult;
        if (!capabilities.empty()) json << ",\"capabilities\":" << capabilities;
        json << '}';
        return json.str();
    }

private:
    void loadDeviceFunctions() {
#define LOAD_DEVICE(name) name = requireDevice<PFN_##name>(getDeviceProcAddr, device, #name)
        LOAD_DEVICE(vkDestroyDevice);
        LOAD_DEVICE(vkGetDeviceQueue);
        LOAD_DEVICE(vkCreateBuffer);
        LOAD_DEVICE(vkDestroyBuffer);
        LOAD_DEVICE(vkGetBufferMemoryRequirements);
        LOAD_DEVICE(vkAllocateMemory);
        LOAD_DEVICE(vkFreeMemory);
        LOAD_DEVICE(vkBindBufferMemory);
        LOAD_DEVICE(vkMapMemory);
        LOAD_DEVICE(vkUnmapMemory);
        LOAD_DEVICE(vkFlushMappedMemoryRanges);
        LOAD_DEVICE(vkInvalidateMappedMemoryRanges);
        LOAD_DEVICE(vkCreateImage);
        LOAD_DEVICE(vkDestroyImage);
        LOAD_DEVICE(vkGetImageMemoryRequirements);
        LOAD_DEVICE(vkBindImageMemory);
        LOAD_DEVICE(vkCreateImageView);
        LOAD_DEVICE(vkDestroyImageView);
        LOAD_DEVICE(vkCreateRenderPass);
        LOAD_DEVICE(vkDestroyRenderPass);
        LOAD_DEVICE(vkCreateFramebuffer);
        LOAD_DEVICE(vkDestroyFramebuffer);
        LOAD_DEVICE(vkCreateShaderModule);
        LOAD_DEVICE(vkDestroyShaderModule);
        LOAD_DEVICE(vkCreatePipelineLayout);
        LOAD_DEVICE(vkDestroyPipelineLayout);
        LOAD_DEVICE(vkCreateGraphicsPipelines);
        LOAD_DEVICE(vkDestroyPipeline);
        LOAD_DEVICE(vkCreateCommandPool);
        LOAD_DEVICE(vkDestroyCommandPool);
        LOAD_DEVICE(vkAllocateCommandBuffers);
        LOAD_DEVICE(vkBeginCommandBuffer);
        LOAD_DEVICE(vkEndCommandBuffer);
        LOAD_DEVICE(vkCmdPipelineBarrier);
        LOAD_DEVICE(vkCmdBeginRenderPass);
        LOAD_DEVICE(vkCmdEndRenderPass);
        LOAD_DEVICE(vkCmdBindPipeline);
        LOAD_DEVICE(vkCmdBindVertexBuffers);
        LOAD_DEVICE(vkCmdDraw);
        LOAD_DEVICE(vkCmdCopyImageToBuffer);
        LOAD_DEVICE(vkQueueSubmit);
        LOAD_DEVICE(vkQueueWaitIdle);
        LOAD_DEVICE(vkDeviceWaitIdle);
#undef LOAD_DEVICE
    }

    uint32_t findMemoryType(uint32_t typeBits, VkMemoryPropertyFlags required,
                            VkMemoryPropertyFlags preferred,
                            VkMemoryPropertyFlags *selectedFlags) const {
        int fallback = -1;
        for (uint32_t index = 0; index < memoryProperties.memoryTypeCount; ++index) {
            if ((typeBits & (1U << index)) == 0) continue;
            const VkMemoryPropertyFlags flags = memoryProperties.memoryTypes[index].propertyFlags;
            if ((flags & required) != required) continue;
            if ((flags & preferred) == preferred) {
                if (selectedFlags != nullptr) *selectedFlags = flags;
                return index;
            }
            if (fallback < 0) fallback = static_cast<int>(index);
        }
        if (fallback >= 0) {
            if (selectedFlags != nullptr) {
                *selectedFlags = memoryProperties.memoryTypes[static_cast<uint32_t>(fallback)].propertyFlags;
            }
            return static_cast<uint32_t>(fallback);
        }
        throw std::runtime_error("No compatible Vulkan memory type");
    }

    void createBuffer(VkDeviceSize size, VkBufferUsageFlags usage,
                      VkMemoryPropertyFlags required, VkMemoryPropertyFlags preferred,
                      VkBuffer *buffer, VkDeviceMemory *memory, VkDeviceSize *allocationSize,
                      VkMemoryPropertyFlags *memoryFlags) {
        VkBufferCreateInfo bufferInfo{};
        bufferInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
        bufferInfo.size = size;
        bufferInfo.usage = usage;
        bufferInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        check(vkCreateBuffer(device, &bufferInfo, nullptr, buffer), "vkCreateBuffer");

        VkMemoryRequirements requirements{};
        vkGetBufferMemoryRequirements(device, *buffer, &requirements);
        VkMemoryAllocateInfo allocationInfo{};
        allocationInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        allocationInfo.allocationSize = requirements.size;
        allocationInfo.memoryTypeIndex = findMemoryType(requirements.memoryTypeBits, required,
                                                        preferred, memoryFlags);
        check(vkAllocateMemory(device, &allocationInfo, nullptr, memory), "vkAllocateMemory(buffer)");
        check(vkBindBufferMemory(device, *buffer, *memory, 0), "vkBindBufferMemory");
        if (allocationSize != nullptr) *allocationSize = requirements.size;
    }

    void createResources() {
        stage = "create_resources";
        createBuffer(sizeof(kVertices), VK_BUFFER_USAGE_VERTEX_BUFFER_BIT,
                     VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT,
                     VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                     &vertexBuffer, &vertexMemory, &vertexAllocationSize, &vertexMemoryFlags);
        void *vertices = nullptr;
        check(vkMapMemory(device, vertexMemory, 0, vertexAllocationSize, 0, &vertices),
              "vkMapMemory(vertices)");
        std::memcpy(vertices, kVertices.data(), sizeof(kVertices));
        if ((vertexMemoryFlags & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) == 0) {
            VkMappedMemoryRange range{};
            range.sType = VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE;
            range.memory = vertexMemory;
            range.offset = 0;
            range.size = VK_WHOLE_SIZE;
            check(vkFlushMappedMemoryRanges(device, 1, &range),
                  "vkFlushMappedMemoryRanges(vertices)");
        }
        vkUnmapMemory(device, vertexMemory);

        const VkDeviceSize readbackSize = static_cast<VkDeviceSize>(kWidth) * kHeight * 4U;
        createBuffer(readbackSize, VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                     VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT,
                     VK_MEMORY_PROPERTY_HOST_CACHED_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                     &readbackBuffer, &readbackMemory, &readbackAllocationSize, &readbackMemoryFlags);

        VkImageCreateInfo imageInfo{};
        imageInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
        imageInfo.imageType = VK_IMAGE_TYPE_2D;
        imageInfo.format = kColorFormat;
        imageInfo.extent = {kWidth, kHeight, 1};
        imageInfo.mipLevels = 1;
        imageInfo.arrayLayers = 1;
        imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
        imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
        imageInfo.usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
        imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        check(vkCreateImage(device, &imageInfo, nullptr, &colorImage), "vkCreateImage");

        VkMemoryRequirements imageRequirements{};
        vkGetImageMemoryRequirements(device, colorImage, &imageRequirements);
        VkMemoryAllocateInfo imageAllocation{};
        imageAllocation.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        imageAllocation.allocationSize = imageRequirements.size;
        imageAllocation.memoryTypeIndex = findMemoryType(imageRequirements.memoryTypeBits,
                                                         0, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                                                         nullptr);
        check(vkAllocateMemory(device, &imageAllocation, nullptr, &colorMemory),
              "vkAllocateMemory(image)");
        check(vkBindImageMemory(device, colorImage, colorMemory, 0), "vkBindImageMemory");

        VkImageViewCreateInfo viewInfo{};
        viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
        viewInfo.image = colorImage;
        viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
        viewInfo.format = kColorFormat;
        viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        viewInfo.subresourceRange.levelCount = 1;
        viewInfo.subresourceRange.layerCount = 1;
        check(vkCreateImageView(device, &viewInfo, nullptr, &colorView), "vkCreateImageView");

        VkAttachmentDescription attachment{};
        attachment.format = kColorFormat;
        attachment.samples = VK_SAMPLE_COUNT_1_BIT;
        attachment.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
        attachment.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
        attachment.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
        attachment.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
        attachment.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        attachment.finalLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;

        VkAttachmentReference colorReference{};
        colorReference.attachment = 0;
        colorReference.layout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;

        VkSubpassDescription subpass{};
        subpass.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
        subpass.colorAttachmentCount = 1;
        subpass.pColorAttachments = &colorReference;

        std::array<VkSubpassDependency, 2> dependencies{};
        dependencies[0].srcSubpass = VK_SUBPASS_EXTERNAL;
        dependencies[0].dstSubpass = 0;
        dependencies[0].srcStageMask = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
        dependencies[0].dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        dependencies[0].dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
        dependencies[1].srcSubpass = 0;
        dependencies[1].dstSubpass = VK_SUBPASS_EXTERNAL;
        dependencies[1].srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        dependencies[1].dstStageMask = VK_PIPELINE_STAGE_TRANSFER_BIT;
        dependencies[1].srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
        dependencies[1].dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;

        VkRenderPassCreateInfo renderPassInfo{};
        renderPassInfo.sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO;
        renderPassInfo.attachmentCount = 1;
        renderPassInfo.pAttachments = &attachment;
        renderPassInfo.subpassCount = 1;
        renderPassInfo.pSubpasses = &subpass;
        renderPassInfo.dependencyCount = static_cast<uint32_t>(dependencies.size());
        renderPassInfo.pDependencies = dependencies.data();
        check(vkCreateRenderPass(device, &renderPassInfo, nullptr, &renderPass),
              "vkCreateRenderPass");

        VkFramebufferCreateInfo framebufferInfo{};
        framebufferInfo.sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
        framebufferInfo.renderPass = renderPass;
        framebufferInfo.attachmentCount = 1;
        framebufferInfo.pAttachments = &colorView;
        framebufferInfo.width = kWidth;
        framebufferInfo.height = kHeight;
        framebufferInfo.layers = 1;
        check(vkCreateFramebuffer(device, &framebufferInfo, nullptr, &framebuffer),
              "vkCreateFramebuffer");

        vertexShader = createShader(kVertexShaderSpirv, sizeof(kVertexShaderSpirv));
        fragmentShader = createShader(kFragmentShaderSpirv, sizeof(kFragmentShaderSpirv));

        VkPipelineLayoutCreateInfo layoutInfo{};
        layoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
        check(vkCreatePipelineLayout(device, &layoutInfo, nullptr, &pipelineLayout),
              "vkCreatePipelineLayout");

        std::array<VkPipelineShaderStageCreateInfo, 2> shaderStages{};
        shaderStages[0].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        shaderStages[0].stage = VK_SHADER_STAGE_VERTEX_BIT;
        shaderStages[0].module = vertexShader;
        shaderStages[0].pName = "main";
        shaderStages[1].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        shaderStages[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT;
        shaderStages[1].module = fragmentShader;
        shaderStages[1].pName = "main";

        VkVertexInputBindingDescription binding{};
        binding.binding = 0;
        binding.stride = sizeof(Vertex);
        binding.inputRate = VK_VERTEX_INPUT_RATE_VERTEX;
        std::array<VkVertexInputAttributeDescription, 2> attributes{};
        attributes[0].location = 0;
        attributes[0].binding = 0;
        attributes[0].format = VK_FORMAT_R32G32_SFLOAT;
        attributes[0].offset = offsetof(Vertex, position);
        attributes[1].location = 1;
        attributes[1].binding = 0;
        attributes[1].format = VK_FORMAT_R32G32B32_SFLOAT;
        attributes[1].offset = offsetof(Vertex, color);

        VkPipelineVertexInputStateCreateInfo vertexInput{};
        vertexInput.sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;
        vertexInput.vertexBindingDescriptionCount = 1;
        vertexInput.pVertexBindingDescriptions = &binding;
        vertexInput.vertexAttributeDescriptionCount = static_cast<uint32_t>(attributes.size());
        vertexInput.pVertexAttributeDescriptions = attributes.data();

        VkPipelineInputAssemblyStateCreateInfo inputAssembly{};
        inputAssembly.sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO;
        inputAssembly.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;

        VkViewport viewport{};
        viewport.x = 0.0F;
        viewport.y = 0.0F;
        viewport.width = static_cast<float>(kWidth);
        viewport.height = static_cast<float>(kHeight);
        viewport.minDepth = 0.0F;
        viewport.maxDepth = 1.0F;
        VkRect2D scissor{{0, 0}, {kWidth, kHeight}};
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
        rasterization.lineWidth = 1.0F;

        VkPipelineMultisampleStateCreateInfo multisample{};
        multisample.sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO;
        multisample.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;

        VkPipelineColorBlendAttachmentState blendAttachment{};
        blendAttachment.colorWriteMask = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT
                | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT;
        VkPipelineColorBlendStateCreateInfo blend{};
        blend.sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO;
        blend.attachmentCount = 1;
        blend.pAttachments = &blendAttachment;

        VkGraphicsPipelineCreateInfo pipelineInfo{};
        pipelineInfo.sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO;
        pipelineInfo.stageCount = static_cast<uint32_t>(shaderStages.size());
        pipelineInfo.pStages = shaderStages.data();
        pipelineInfo.pVertexInputState = &vertexInput;
        pipelineInfo.pInputAssemblyState = &inputAssembly;
        pipelineInfo.pViewportState = &viewportState;
        pipelineInfo.pRasterizationState = &rasterization;
        pipelineInfo.pMultisampleState = &multisample;
        pipelineInfo.pColorBlendState = &blend;
        pipelineInfo.layout = pipelineLayout;
        pipelineInfo.renderPass = renderPass;
        pipelineInfo.subpass = 0;
        check(vkCreateGraphicsPipelines(device, VK_NULL_HANDLE, 1, &pipelineInfo, nullptr,
                                        &pipeline), "vkCreateGraphicsPipelines");

        VkCommandPoolCreateInfo poolInfo{};
        poolInfo.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
        poolInfo.flags = VK_COMMAND_POOL_CREATE_TRANSIENT_BIT;
        poolInfo.queueFamilyIndex = queueFamilyIndex;
        check(vkCreateCommandPool(device, &poolInfo, nullptr, &commandPool),
              "vkCreateCommandPool");

        VkCommandBufferAllocateInfo commandInfo{};
        commandInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
        commandInfo.commandPool = commandPool;
        commandInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        commandInfo.commandBufferCount = 1;
        check(vkAllocateCommandBuffers(device, &commandInfo, &commandBuffer),
              "vkAllocateCommandBuffers");
    }

    VkShaderModule createShader(const uint32_t *code, size_t size) {
        VkShaderModuleCreateInfo shaderInfo{};
        shaderInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
        shaderInfo.codeSize = size;
        shaderInfo.pCode = code;
        VkShaderModule shader = VK_NULL_HANDLE;
        check(vkCreateShaderModule(device, &shaderInfo, nullptr, &shader), "vkCreateShaderModule");
        return shader;
    }

    void recordCommands() {
        VkCommandBufferBeginInfo beginInfo{};
        beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
        beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        check(vkBeginCommandBuffer(commandBuffer, &beginInfo), "vkBeginCommandBuffer");

        VkClearValue clear{};
        clear.color.float32[0] = 0.03125F;
        clear.color.float32[1] = 0.046875F;
        clear.color.float32[2] = 0.078125F;
        clear.color.float32[3] = 1.0F;
        VkRenderPassBeginInfo passInfo{};
        passInfo.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
        passInfo.renderPass = renderPass;
        passInfo.framebuffer = framebuffer;
        passInfo.renderArea.extent = {kWidth, kHeight};
        passInfo.clearValueCount = 1;
        passInfo.pClearValues = &clear;
        vkCmdBeginRenderPass(commandBuffer, &passInfo, VK_SUBPASS_CONTENTS_INLINE);
        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
        VkDeviceSize offset = 0;
        vkCmdBindVertexBuffers(commandBuffer, 0, 1, &vertexBuffer, &offset);
        vkCmdDraw(commandBuffer, 3, 1, 0, 0);
        vkCmdDraw(commandBuffer, 9, 1, 3, 0);
        vkCmdEndRenderPass(commandBuffer);

        VkBufferImageCopy copy{};
        copy.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        copy.imageSubresource.layerCount = 1;
        copy.imageExtent = {kWidth, kHeight, 1};
        vkCmdCopyImageToBuffer(commandBuffer, colorImage, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                               readbackBuffer, 1, &copy);

        VkBufferMemoryBarrier hostBarrier{};
        hostBarrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        hostBarrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        hostBarrier.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
        hostBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        hostBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        hostBarrier.buffer = readbackBuffer;
        hostBarrier.offset = 0;
        hostBarrier.size = VK_WHOLE_SIZE;
        vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT,
                             VK_PIPELINE_STAGE_HOST_BIT, 0, 0, nullptr, 1, &hostBarrier,
                             0, nullptr);
        check(vkEndCommandBuffer(commandBuffer), "vkEndCommandBuffer");
    }

    std::string buildCapabilitiesJson() const {
        const auto &features = features2.features;
        const auto &limits = properties.limits;
        std::ostringstream json;
        json << '{'
             << "\"gpu_name\":\"" << jsonEscape(properties.deviceName) << "\""
             << ",\"vendor_id\":" << properties.vendorID
             << ",\"device_id\":" << properties.deviceID
             << ",\"device_type\":" << properties.deviceType
             << ",\"api_version_raw\":" << properties.apiVersion
             << ",\"api_version\":\"" << versionString(properties.apiVersion) << "\""
             << ",\"driver_version_raw\":" << properties.driverVersion
             << ",\"driver_version_decoded\":\"" << versionString(properties.driverVersion) << "\"";
        if (hasDriverProperties) {
            const std::string driverInfo(driverProperties.driverInfo);
            const auto mesaVersion = parseMesaMajorMinor(driverInfo);
            json << ",\"driver_id\":" << static_cast<int>(driverProperties.driverID)
                 << ",\"driver_id_name\":\"" << driverIdName(driverProperties.driverID) << "\""
                 << ",\"driver_name\":\"" << jsonEscape(driverProperties.driverName) << "\""
                 << ",\"driver_info\":\"" << jsonEscape(driverInfo) << "\""
                 << ",\"conformance_version\":\""
                 << static_cast<unsigned int>(driverProperties.conformanceVersion.major) << '.'
                 << static_cast<unsigned int>(driverProperties.conformanceVersion.minor) << '.'
                 << static_cast<unsigned int>(driverProperties.conformanceVersion.subminor) << '.'
                 << static_cast<unsigned int>(driverProperties.conformanceVersion.patch) << "\""
                 << ",\"mesa_version_major\":"
                 << (mesaVersion.first >= 0 ? std::to_string(mesaVersion.first) : "null")
                 << ",\"mesa_version_minor\":"
                 << (mesaVersion.second >= 0 ? std::to_string(mesaVersion.second) : "null");
        } else {
            json << ",\"driver_id\":null,\"driver_id_name\":null"
                 << ",\"driver_name\":null,\"driver_info\":null"
                 << ",\"conformance_version\":null"
                 << ",\"mesa_version_major\":null,\"mesa_version_minor\":null";
        }

        json << ",\"extensions\":[";
        for (size_t index = 0; index < deviceExtensions.size(); ++index) {
            if (index > 0) json << ',';
            json << "\"" << jsonEscape(deviceExtensions[index].extensionName) << "\"";
        }
        json << "]";

        json << ",\"extension_spec_versions\":{";
        for (size_t index = 0; index < deviceExtensions.size(); ++index) {
            if (index > 0) json << ',';
            json << "\"" << jsonEscape(deviceExtensions[index].extensionName) << "\":"
                 << deviceExtensions[index].specVersion;
        }
        json << '}';

        json << ",\"features2_source\":\""
             << (getPhysicalDeviceFeatures2 != nullptr ? "vkGetPhysicalDeviceFeatures2" : "legacy_fallback")
             << "\",\"features\":{";
#define FEATURE(name) "\"" #name "\":" << (features.name ? "true" : "false")
        json << FEATURE(robustBufferAccess)
             << ',' << FEATURE(fullDrawIndexUint32)
             << ',' << FEATURE(imageCubeArray)
             << ',' << FEATURE(independentBlend)
             << ',' << FEATURE(geometryShader)
             << ',' << FEATURE(tessellationShader)
             << ',' << FEATURE(sampleRateShading)
             << ',' << FEATURE(dualSrcBlend)
             << ',' << FEATURE(logicOp)
             << ',' << FEATURE(multiDrawIndirect)
             << ',' << FEATURE(drawIndirectFirstInstance)
             << ',' << FEATURE(depthClamp)
             << ',' << FEATURE(depthBiasClamp)
             << ',' << FEATURE(fillModeNonSolid)
             << ',' << FEATURE(depthBounds)
             << ',' << FEATURE(wideLines)
             << ',' << FEATURE(largePoints)
             << ',' << FEATURE(alphaToOne)
             << ',' << FEATURE(multiViewport)
             << ',' << FEATURE(samplerAnisotropy)
             << ',' << FEATURE(textureCompressionETC2)
             << ',' << FEATURE(textureCompressionASTC_LDR)
             << ',' << FEATURE(textureCompressionBC)
             << ',' << FEATURE(occlusionQueryPrecise)
             << ',' << FEATURE(pipelineStatisticsQuery)
             << ',' << FEATURE(vertexPipelineStoresAndAtomics)
             << ',' << FEATURE(fragmentStoresAndAtomics)
             << ',' << FEATURE(shaderTessellationAndGeometryPointSize)
             << ',' << FEATURE(shaderImageGatherExtended)
             << ',' << FEATURE(shaderStorageImageExtendedFormats)
             << ',' << FEATURE(shaderStorageImageMultisample)
             << ',' << FEATURE(shaderStorageImageReadWithoutFormat)
             << ',' << FEATURE(shaderStorageImageWriteWithoutFormat)
             << ',' << FEATURE(shaderUniformBufferArrayDynamicIndexing)
             << ',' << FEATURE(shaderSampledImageArrayDynamicIndexing)
             << ',' << FEATURE(shaderStorageBufferArrayDynamicIndexing)
             << ',' << FEATURE(shaderStorageImageArrayDynamicIndexing)
             << ',' << FEATURE(shaderClipDistance)
             << ',' << FEATURE(shaderCullDistance)
             << ',' << FEATURE(shaderFloat64)
             << ',' << FEATURE(shaderInt64)
             << ',' << FEATURE(shaderInt16)
             << ',' << FEATURE(shaderResourceResidency)
             << ',' << FEATURE(shaderResourceMinLod)
             << ',' << FEATURE(sparseBinding)
             << ',' << FEATURE(sparseResidencyBuffer)
             << ',' << FEATURE(sparseResidencyImage2D)
             << ',' << FEATURE(sparseResidencyImage3D)
             << ',' << FEATURE(sparseResidency2Samples)
             << ',' << FEATURE(sparseResidency4Samples)
             << ',' << FEATURE(sparseResidency8Samples)
             << ',' << FEATURE(sparseResidency16Samples)
             << ',' << FEATURE(sparseResidencyAliased)
             << ',' << FEATURE(variableMultisampleRate)
             << ',' << FEATURE(inheritedQueries);
#undef FEATURE
        json << '}';

        json << ",\"limits\":{"
             << "\"max_image_dimension_2d\":" << limits.maxImageDimension2D
             << ",\"max_uniform_buffer_range\":" << limits.maxUniformBufferRange
             << ",\"max_storage_buffer_range\":" << limits.maxStorageBufferRange
             << ",\"max_memory_allocation_count\":" << limits.maxMemoryAllocationCount
             << ",\"max_sampler_allocation_count\":" << limits.maxSamplerAllocationCount
             << ",\"buffer_image_granularity\":" << limits.bufferImageGranularity
             << ",\"max_bound_descriptor_sets\":" << limits.maxBoundDescriptorSets
             << ",\"max_per_stage_descriptor_samplers\":" << limits.maxPerStageDescriptorSamplers
             << ",\"max_per_stage_descriptor_uniform_buffers\":" << limits.maxPerStageDescriptorUniformBuffers
             << ",\"max_per_stage_descriptor_storage_buffers\":" << limits.maxPerStageDescriptorStorageBuffers
             << ",\"max_per_stage_descriptor_sampled_images\":" << limits.maxPerStageDescriptorSampledImages
             << ",\"max_per_stage_descriptor_storage_images\":" << limits.maxPerStageDescriptorStorageImages
             << ",\"max_per_stage_resources\":" << limits.maxPerStageResources
             << ",\"max_descriptor_set_samplers\":" << limits.maxDescriptorSetSamplers
             << ",\"max_descriptor_set_uniform_buffers\":" << limits.maxDescriptorSetUniformBuffers
             << ",\"max_descriptor_set_storage_buffers\":" << limits.maxDescriptorSetStorageBuffers
             << ",\"max_descriptor_set_sampled_images\":" << limits.maxDescriptorSetSampledImages
             << ",\"max_descriptor_set_storage_images\":" << limits.maxDescriptorSetStorageImages
             << ",\"max_vertex_input_attributes\":" << limits.maxVertexInputAttributes
             << ",\"max_vertex_input_bindings\":" << limits.maxVertexInputBindings
             << ",\"max_vertex_output_components\":" << limits.maxVertexOutputComponents
             << ",\"max_fragment_input_components\":" << limits.maxFragmentInputComponents
             << ",\"max_fragment_output_attachments\":" << limits.maxFragmentOutputAttachments
             << ",\"max_fragment_combined_output_resources\":" << limits.maxFragmentCombinedOutputResources
             << ",\"max_compute_shared_memory_size\":" << limits.maxComputeSharedMemorySize
             << ",\"max_compute_work_group_invocations\":" << limits.maxComputeWorkGroupInvocations
             << ",\"max_compute_work_group_count\":[" << limits.maxComputeWorkGroupCount[0] << ','
             << limits.maxComputeWorkGroupCount[1] << ',' << limits.maxComputeWorkGroupCount[2] << ']'
             << ",\"max_compute_work_group_size\":[" << limits.maxComputeWorkGroupSize[0] << ','
             << limits.maxComputeWorkGroupSize[1] << ',' << limits.maxComputeWorkGroupSize[2] << ']'
             << ",\"max_draw_indexed_index_value\":" << limits.maxDrawIndexedIndexValue
             << ",\"max_draw_indirect_count\":" << limits.maxDrawIndirectCount
             << ",\"max_sampler_anisotropy\":" << limits.maxSamplerAnisotropy
             << ",\"max_viewports\":" << limits.maxViewports
             << ",\"max_viewport_dimensions\":[" << limits.maxViewportDimensions[0] << ','
             << limits.maxViewportDimensions[1] << ']'
             << ",\"framebuffer_color_sample_counts\":" << limits.framebufferColorSampleCounts
             << ",\"sampled_image_color_sample_counts\":" << limits.sampledImageColorSampleCounts
             << ",\"timestamp_compute_and_graphics\":"
             << (limits.timestampComputeAndGraphics ? "true" : "false")
             << ",\"timestamp_period_ns\":" << limits.timestampPeriod
             << ",\"min_memory_map_alignment\":" << limits.minMemoryMapAlignment
             << ",\"non_coherent_atom_size\":" << limits.nonCoherentAtomSize
             << '}';
        json << '}';
        return json.str();
    }

    void cleanup() {
        if (device != VK_NULL_HANDLE && vkDeviceWaitIdle != nullptr) vkDeviceWaitIdle(device);
        if (device != VK_NULL_HANDLE) {
            if (commandPool != VK_NULL_HANDLE) vkDestroyCommandPool(device, commandPool, nullptr);
            if (pipeline != VK_NULL_HANDLE) vkDestroyPipeline(device, pipeline, nullptr);
            if (pipelineLayout != VK_NULL_HANDLE) vkDestroyPipelineLayout(device, pipelineLayout, nullptr);
            if (vertexShader != VK_NULL_HANDLE) vkDestroyShaderModule(device, vertexShader, nullptr);
            if (fragmentShader != VK_NULL_HANDLE) vkDestroyShaderModule(device, fragmentShader, nullptr);
            if (framebuffer != VK_NULL_HANDLE) vkDestroyFramebuffer(device, framebuffer, nullptr);
            if (renderPass != VK_NULL_HANDLE) vkDestroyRenderPass(device, renderPass, nullptr);
            if (colorView != VK_NULL_HANDLE) vkDestroyImageView(device, colorView, nullptr);
            if (colorImage != VK_NULL_HANDLE) vkDestroyImage(device, colorImage, nullptr);
            if (colorMemory != VK_NULL_HANDLE) vkFreeMemory(device, colorMemory, nullptr);
            if (readbackBuffer != VK_NULL_HANDLE) vkDestroyBuffer(device, readbackBuffer, nullptr);
            if (readbackMemory != VK_NULL_HANDLE) vkFreeMemory(device, readbackMemory, nullptr);
            if (vertexBuffer != VK_NULL_HANDLE) vkDestroyBuffer(device, vertexBuffer, nullptr);
            if (vertexMemory != VK_NULL_HANDLE) vkFreeMemory(device, vertexMemory, nullptr);
            if (vkDestroyDevice != nullptr) vkDestroyDevice(device, nullptr);
        }
        device = VK_NULL_HANDLE;
        if (instance != VK_NULL_HANDLE && destroyInstance != nullptr) destroyInstance(instance, nullptr);
        instance = VK_NULL_HANDLE;
        if (library != nullptr) dlclose(library);
        library = nullptr;
    }

    std::string stage = "not_started";
    bool customDriver = false;
    bool hasDriverProperties = false;
    std::string capabilities;
    void *library = nullptr;
    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    uint32_t queueFamilyIndex = 0;
    VkQueueFamilyProperties queueFamilyProperties{};
    VkPhysicalDeviceProperties properties{};
    VkPhysicalDeviceFeatures2 features2{};
    VkPhysicalDeviceDriverProperties driverProperties{};
    VkPhysicalDeviceMemoryProperties memoryProperties{};
    std::vector<VkExtensionProperties> deviceExtensions;

    VkBuffer vertexBuffer = VK_NULL_HANDLE;
    VkDeviceMemory vertexMemory = VK_NULL_HANDLE;
    VkDeviceSize vertexAllocationSize = 0;
    VkMemoryPropertyFlags vertexMemoryFlags = 0;
    VkBuffer readbackBuffer = VK_NULL_HANDLE;
    VkDeviceMemory readbackMemory = VK_NULL_HANDLE;
    VkDeviceSize readbackAllocationSize = 0;
    VkMemoryPropertyFlags readbackMemoryFlags = 0;
    VkImage colorImage = VK_NULL_HANDLE;
    VkDeviceMemory colorMemory = VK_NULL_HANDLE;
    VkImageView colorView = VK_NULL_HANDLE;
    VkRenderPass renderPass = VK_NULL_HANDLE;
    VkFramebuffer framebuffer = VK_NULL_HANDLE;
    VkShaderModule vertexShader = VK_NULL_HANDLE;
    VkShaderModule fragmentShader = VK_NULL_HANDLE;
    VkPipelineLayout pipelineLayout = VK_NULL_HANDLE;
    VkPipeline pipeline = VK_NULL_HANDLE;
    VkCommandPool commandPool = VK_NULL_HANDLE;
    VkCommandBuffer commandBuffer = VK_NULL_HANDLE;

    PFN_vkGetInstanceProcAddr getInstanceProcAddr = nullptr;
    PFN_vkCreateInstance createInstance = nullptr;
    PFN_vkEnumerateInstanceExtensionProperties enumerateInstanceExtensionProperties = nullptr;
    PFN_vkDestroyInstance destroyInstance = nullptr;
    PFN_vkEnumeratePhysicalDevices enumeratePhysicalDevices = nullptr;
    PFN_vkGetPhysicalDeviceProperties getPhysicalDeviceProperties = nullptr;
    PFN_vkGetPhysicalDeviceFeatures getPhysicalDeviceFeatures = nullptr;
    PFN_vkGetPhysicalDeviceProperties2 getPhysicalDeviceProperties2 = nullptr;
    PFN_vkGetPhysicalDeviceFeatures2 getPhysicalDeviceFeatures2 = nullptr;
    PFN_vkGetPhysicalDeviceQueueFamilyProperties getPhysicalDeviceQueueFamilyProperties = nullptr;
    PFN_vkGetPhysicalDeviceMemoryProperties getPhysicalDeviceMemoryProperties = nullptr;
    PFN_vkGetPhysicalDeviceFormatProperties getPhysicalDeviceFormatProperties = nullptr;
    PFN_vkEnumerateDeviceExtensionProperties enumerateDeviceExtensionProperties = nullptr;
    PFN_vkCreateDevice createDevice = nullptr;
    PFN_vkGetDeviceProcAddr getDeviceProcAddr = nullptr;

    PFN_vkDestroyDevice vkDestroyDevice = nullptr;
    PFN_vkGetDeviceQueue vkGetDeviceQueue = nullptr;
    PFN_vkCreateBuffer vkCreateBuffer = nullptr;
    PFN_vkDestroyBuffer vkDestroyBuffer = nullptr;
    PFN_vkGetBufferMemoryRequirements vkGetBufferMemoryRequirements = nullptr;
    PFN_vkAllocateMemory vkAllocateMemory = nullptr;
    PFN_vkFreeMemory vkFreeMemory = nullptr;
    PFN_vkBindBufferMemory vkBindBufferMemory = nullptr;
    PFN_vkMapMemory vkMapMemory = nullptr;
    PFN_vkUnmapMemory vkUnmapMemory = nullptr;
    PFN_vkFlushMappedMemoryRanges vkFlushMappedMemoryRanges = nullptr;
    PFN_vkInvalidateMappedMemoryRanges vkInvalidateMappedMemoryRanges = nullptr;
    PFN_vkCreateImage vkCreateImage = nullptr;
    PFN_vkDestroyImage vkDestroyImage = nullptr;
    PFN_vkGetImageMemoryRequirements vkGetImageMemoryRequirements = nullptr;
    PFN_vkBindImageMemory vkBindImageMemory = nullptr;
    PFN_vkCreateImageView vkCreateImageView = nullptr;
    PFN_vkDestroyImageView vkDestroyImageView = nullptr;
    PFN_vkCreateRenderPass vkCreateRenderPass = nullptr;
    PFN_vkDestroyRenderPass vkDestroyRenderPass = nullptr;
    PFN_vkCreateFramebuffer vkCreateFramebuffer = nullptr;
    PFN_vkDestroyFramebuffer vkDestroyFramebuffer = nullptr;
    PFN_vkCreateShaderModule vkCreateShaderModule = nullptr;
    PFN_vkDestroyShaderModule vkDestroyShaderModule = nullptr;
    PFN_vkCreatePipelineLayout vkCreatePipelineLayout = nullptr;
    PFN_vkDestroyPipelineLayout vkDestroyPipelineLayout = nullptr;
    PFN_vkCreateGraphicsPipelines vkCreateGraphicsPipelines = nullptr;
    PFN_vkDestroyPipeline vkDestroyPipeline = nullptr;
    PFN_vkCreateCommandPool vkCreateCommandPool = nullptr;
    PFN_vkDestroyCommandPool vkDestroyCommandPool = nullptr;
    PFN_vkAllocateCommandBuffers vkAllocateCommandBuffers = nullptr;
    PFN_vkBeginCommandBuffer vkBeginCommandBuffer = nullptr;
    PFN_vkEndCommandBuffer vkEndCommandBuffer = nullptr;
    PFN_vkCmdPipelineBarrier vkCmdPipelineBarrier = nullptr;
    PFN_vkCmdBeginRenderPass vkCmdBeginRenderPass = nullptr;
    PFN_vkCmdEndRenderPass vkCmdEndRenderPass = nullptr;
    PFN_vkCmdBindPipeline vkCmdBindPipeline = nullptr;
    PFN_vkCmdBindVertexBuffers vkCmdBindVertexBuffers = nullptr;
    PFN_vkCmdDraw vkCmdDraw = nullptr;
    PFN_vkCmdCopyImageToBuffer vkCmdCopyImageToBuffer = nullptr;
    PFN_vkQueueSubmit vkQueueSubmit = nullptr;
    PFN_vkQueueWaitIdle vkQueueWaitIdle = nullptr;
    PFN_vkDeviceWaitIdle vkDeviceWaitIdle = nullptr;
};

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_amaral_driverlab_RunnerActivity_runNativeRenderCorrectness(
        JNIEnv *environment,
        jclass,
        jstring driverDirectory,
        jstring driverName,
        jstring nativeLibraryDirectory,
        jstring temporaryDirectory,
        jstring rawOutputPath) {
    CorrectnessRenderer renderer;
    try {
        renderer.initialize(UtfString(environment, driverDirectory).string(),
                            UtfString(environment, driverName).string(),
                            UtfString(environment, nativeLibraryDirectory).string(),
                            UtfString(environment, temporaryDirectory).string());
        const std::string result = renderer.render(UtfString(environment, rawOutputPath).string());
        return environment->NewStringUTF(result.c_str());
    } catch (const std::exception &error) {
        const std::string result = renderer.failureJson(error);
        return environment->NewStringUTF(result.c_str());
    }
}
