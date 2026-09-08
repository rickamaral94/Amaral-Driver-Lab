#include "vk_context.h"

#include <algorithm>
#include <cstring>
#include <sstream>

namespace amaral {
namespace {

constexpr const char* kValidationLayer = "VK_LAYER_KHRONOS_validation";

ContextResult contextFailure(LoadStage stage, std::string message, VkResult result = VK_SUCCESS) {
    ContextResult out;
    out.ok = false;
    out.stage = stage;
    out.message = std::move(message);
    out.vulkanResult = result;
    return out;
}

std::string jsonEscape(const std::string& input) {
    std::string out;
    out.reserve(input.size() + 8);
    for (char c : input) {
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (static_cast<unsigned char>(c) < 0x20) {
                    char buffer[8];
                    snprintf(buffer, sizeof(buffer), "\\u%04x", c);
                    out += buffer;
                } else {
                    out += c;
                }
        }
    }
    return out;
}

std::string jsonStringArray(const std::vector<std::string>& values) {
    std::ostringstream out;
    out << '[';
    for (size_t i = 0; i < values.size(); ++i) {
        if (i > 0) out << ',';
        out << '"' << jsonEscape(values[i]) << '"';
    }
    out << ']';
    return out.str();
}

bool hasLayer(VulkanApi& api, const char* name) {
    uint32_t count = 0;
    if (api.vkEnumerateInstanceLayerProperties(&count, nullptr) != VK_SUCCESS || count == 0) {
        return false;
    }
    std::vector<VkLayerProperties> layers(count);
    if (api.vkEnumerateInstanceLayerProperties(&count, layers.data()) != VK_SUCCESS) return false;
    return std::any_of(layers.begin(), layers.end(), [name](const VkLayerProperties& layer) {
        return std::strcmp(layer.layerName, name) == 0;
    });
}

std::vector<std::string> instanceExtensionNames(VulkanApi& api) {
    uint32_t count = 0;
    std::vector<std::string> names;
    if (api.vkEnumerateInstanceExtensionProperties(nullptr, &count, nullptr) != VK_SUCCESS) return names;
    std::vector<VkExtensionProperties> extensions(count);
    if (api.vkEnumerateInstanceExtensionProperties(nullptr, &count, extensions.data()) != VK_SUCCESS) {
        return names;
    }
    names.reserve(count);
    for (const auto& extension : extensions) names.emplace_back(extension.extensionName);
    return names;
}

std::vector<std::string> deviceExtensionNames(VulkanApi& api, VkPhysicalDevice device) {
    uint32_t count = 0;
    std::vector<std::string> names;
    if (api.vkEnumerateDeviceExtensionProperties(device, nullptr, &count, nullptr) != VK_SUCCESS) {
        return names;
    }
    std::vector<VkExtensionProperties> extensions(count);
    if (api.vkEnumerateDeviceExtensionProperties(device, nullptr, &count, extensions.data()) !=
        VK_SUCCESS) {
        return names;
    }
    names.reserve(count);
    for (const auto& extension : extensions) names.emplace_back(extension.extensionName);
    return names;
}

}  // namespace

VulkanContext::~VulkanContext() {
    if (device != VK_NULL_HANDLE && api.vkDestroyDevice != nullptr) {
        api.vkDeviceWaitIdle(device);
        api.vkDestroyDevice(device, nullptr);
        device = VK_NULL_HANDLE;
    }
    if (instance != VK_NULL_HANDLE && api.vkDestroyInstance != nullptr) {
        api.vkDestroyInstance(instance, nullptr);
        instance = VK_NULL_HANDLE;
    }
    closeIcd(icd);
}

bool VulkanContext::findMemoryType(uint32_t typeBits,
                                   VkMemoryPropertyFlags properties,
                                   uint32_t* outIndex) const {
    for (uint32_t i = 0; i < memoryProperties.memoryTypeCount; ++i) {
        const bool typeAllowed = (typeBits & (1u << i)) != 0;
        const bool hasProperties =
                (memoryProperties.memoryTypes[i].propertyFlags & properties) == properties;
        if (typeAllowed && hasProperties) {
            *outIndex = i;
            return true;
        }
    }
    return false;
}

VkFormat VulkanContext::pickDepthStencilFormat() const {
    // D32_SFLOAT_S8_UINT is what the spec asks workload 2 to stress, but it is not
    // universally supported. Falling back is fine as long as the report says which
    // format ran, because the two are not comparable.
    const VkFormat candidates[] = {
            VK_FORMAT_D32_SFLOAT_S8_UINT,
            VK_FORMAT_D24_UNORM_S8_UINT,
            VK_FORMAT_D32_SFLOAT,
    };
    for (VkFormat format : candidates) {
        VkFormatProperties properties{};
        api.vkGetPhysicalDeviceFormatProperties(physicalDevice, format, &properties);
        if (properties.optimalTilingFeatures & VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT) {
            return format;
        }
    }
    return VK_FORMAT_UNDEFINED;
}

ContextResult createContext(VulkanContext& context, IcdHandle handle, bool enableValidation) {
    context.icd = handle;
    if (!context.api.loadGlobal(handle.getInstanceProcAddr)) {
        return contextFailure(LoadStage::InstanceCreationFailed,
                              "could not resolve " + context.api.missingFunction);
    }

    VkApplicationInfo applicationInfo{};
    applicationInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    applicationInfo.pApplicationName = "Amaral Driver Lab";
    applicationInfo.applicationVersion = 1;
    applicationInfo.pEngineName = "amaral-vk";
    applicationInfo.engineVersion = 1;
    // 1.1 is the floor: VkPhysicalDeviceProperties2 and the driver-properties chain
    // are what section 5 needs, and both are core there.
    applicationInfo.apiVersion = VK_API_VERSION_1_1;

    std::vector<const char*> layers;
    if (enableValidation && hasLayer(context.api, kValidationLayer)) {
        layers.push_back(kValidationLayer);
        context.validationEnabled = true;
    }

    VkInstanceCreateInfo instanceInfo{};
    instanceInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    instanceInfo.pApplicationInfo = &applicationInfo;
    instanceInfo.enabledLayerCount = static_cast<uint32_t>(layers.size());
    instanceInfo.ppEnabledLayerNames = layers.empty() ? nullptr : layers.data();

    VkResult result = context.api.vkCreateInstance(&instanceInfo, nullptr, &context.instance);
    if (result != VK_SUCCESS) {
        return contextFailure(LoadStage::InstanceCreationFailed, "vkCreateInstance failed", result);
    }
    if (!context.api.loadInstance(context.instance)) {
        return contextFailure(LoadStage::InstanceCreationFailed,
                              "could not resolve " + context.api.missingFunction);
    }

    uint32_t deviceCount = 0;
    result = context.api.vkEnumeratePhysicalDevices(context.instance, &deviceCount, nullptr);
    if (result != VK_SUCCESS || deviceCount == 0) {
        return contextFailure(LoadStage::NoPhysicalDevice,
                              "the driver reported no Vulkan physical devices", result);
    }
    std::vector<VkPhysicalDevice> devices(deviceCount);
    result = context.api.vkEnumeratePhysicalDevices(context.instance, &deviceCount, devices.data());
    if (result != VK_SUCCESS) {
        return contextFailure(LoadStage::NoPhysicalDevice, "vkEnumeratePhysicalDevices failed", result);
    }
    // One GPU per Android device in practice; taking the first keeps selection
    // deterministic, and the identity records exactly which one answered.
    context.physicalDevice = devices[0];

    VkPhysicalDeviceDriverProperties driverProperties{};
    driverProperties.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DRIVER_PROPERTIES;

    VkPhysicalDeviceProperties2 properties2{};
    properties2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2;
    properties2.pNext = &driverProperties;
    context.api.vkGetPhysicalDeviceProperties2(context.physicalDevice, &properties2);

    DriverIdentity& identity = context.identity;
    identity.driverId = static_cast<uint32_t>(driverProperties.driverID);
    identity.driverName = driverProperties.driverName;
    identity.driverInfo = driverProperties.driverInfo;
    const VkConformanceVersion& conformance = driverProperties.conformanceVersion;
    identity.hasConformanceVersion =
            conformance.major != 0 || conformance.minor != 0 ||
            conformance.subminor != 0 || conformance.patch != 0;
    identity.conformanceMajor = conformance.major;
    identity.conformanceMinor = conformance.minor;
    identity.conformanceSubminor = conformance.subminor;
    identity.conformancePatch = conformance.patch;

    const VkPhysicalDeviceProperties& properties = properties2.properties;
    identity.deviceName = properties.deviceName;
    identity.vendorId = properties.vendorID;
    identity.deviceId = properties.deviceID;
    identity.apiVersion = properties.apiVersion;
    identity.driverVersion = properties.driverVersion;
    identity.timestampPeriod = properties.limits.timestampPeriod;
    identity.maxImageDimension2D = properties.limits.maxImageDimension2D;
    identity.maxColorAttachments = properties.limits.maxColorAttachments;
    identity.framebufferColorSampleCounts = properties.limits.framebufferColorSampleCounts;
    identity.framebufferDepthSampleCounts = properties.limits.framebufferDepthSampleCounts;

    identity.instanceExtensions = instanceExtensionNames(context.api);
    identity.deviceExtensions = deviceExtensionNames(context.api, context.physicalDevice);

    context.api.vkGetPhysicalDeviceMemoryProperties(context.physicalDevice,
                                                    &context.memoryProperties);
    for (uint32_t i = 0; i < context.memoryProperties.memoryHeapCount; ++i) {
        if (context.memoryProperties.memoryHeaps[i].flags & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) {
            identity.deviceLocalHeapBytes =
                    std::max<uint64_t>(identity.deviceLocalHeapBytes,
                                       context.memoryProperties.memoryHeaps[i].size);
        }
    }

    uint32_t queueFamilyCount = 0;
    context.api.vkGetPhysicalDeviceQueueFamilyProperties(context.physicalDevice, &queueFamilyCount,
                                                         nullptr);
    if (queueFamilyCount == 0) {
        return contextFailure(LoadStage::DeviceCreationFailed, "the device exposes no queue families");
    }
    std::vector<VkQueueFamilyProperties> queueFamilies(queueFamilyCount);
    context.api.vkGetPhysicalDeviceQueueFamilyProperties(context.physicalDevice, &queueFamilyCount,
                                                         queueFamilies.data());

    bool foundQueue = false;
    for (uint32_t i = 0; i < queueFamilyCount; ++i) {
        if (queueFamilies[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) {
            context.queueFamilyIndex = i;
            identity.timestampValidBits = queueFamilies[i].timestampValidBits;
            foundQueue = true;
            break;
        }
    }
    if (!foundQueue) {
        return contextFailure(LoadStage::DeviceCreationFailed, "no graphics queue family");
    }

    const float queuePriority = 1.0f;
    VkDeviceQueueCreateInfo queueInfo{};
    queueInfo.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    queueInfo.queueFamilyIndex = context.queueFamilyIndex;
    queueInfo.queueCount = 1;
    queueInfo.pQueuePriorities = &queuePriority;

    VkDeviceCreateInfo deviceInfo{};
    deviceInfo.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    deviceInfo.queueCreateInfoCount = 1;
    deviceInfo.pQueueCreateInfos = &queueInfo;

    result = context.api.vkCreateDevice(context.physicalDevice, &deviceInfo, nullptr, &context.device);
    if (result != VK_SUCCESS) {
        return contextFailure(LoadStage::DeviceCreationFailed, "vkCreateDevice failed", result);
    }
    if (!context.api.loadDevice(context.device)) {
        return contextFailure(LoadStage::DeviceCreationFailed,
                              "could not resolve " + context.api.missingFunction);
    }
    context.api.vkGetDeviceQueue(context.device, context.queueFamilyIndex, 0, &context.queue);

    if (identity.driverId == 0 && identity.driverName.empty()) {
        return contextFailure(LoadStage::IdentityUnavailable,
                              "the device did not report VkPhysicalDeviceDriverProperties");
    }

    ContextResult out;
    out.ok = true;
    return out;
}

std::string identityToJson(const VulkanContext& context) {
    const DriverIdentity& identity = context.identity;
    std::ostringstream out;
    out << '{'
        << "\"driverId\":" << identity.driverId << ','
        << "\"driverName\":\"" << jsonEscape(identity.driverName) << "\","
        << "\"driverInfo\":\"" << jsonEscape(identity.driverInfo) << "\","
        << "\"hasConformanceVersion\":" << (identity.hasConformanceVersion ? "true" : "false") << ','
        << "\"conformanceMajor\":" << identity.conformanceMajor << ','
        << "\"conformanceMinor\":" << identity.conformanceMinor << ','
        << "\"conformanceSubminor\":" << identity.conformanceSubminor << ','
        << "\"conformancePatch\":" << identity.conformancePatch << ','
        << "\"deviceName\":\"" << jsonEscape(identity.deviceName) << "\","
        << "\"vendorId\":" << identity.vendorId << ','
        << "\"deviceId\":" << identity.deviceId << ','
        << "\"apiVersion\":" << identity.apiVersion << ','
        << "\"driverVersion\":" << identity.driverVersion << ','
        << "\"timestampPeriod\":" << identity.timestampPeriod << ','
        << "\"timestampValidBits\":" << identity.timestampValidBits << ','
        << "\"maxImageDimension2D\":" << identity.maxImageDimension2D << ','
        << "\"maxColorAttachments\":" << identity.maxColorAttachments << ','
        << "\"framebufferColorSampleCounts\":" << identity.framebufferColorSampleCounts << ','
        << "\"framebufferDepthSampleCounts\":" << identity.framebufferDepthSampleCounts << ','
        << "\"deviceLocalHeapBytes\":" << identity.deviceLocalHeapBytes << ','
        << "\"validationEnabled\":" << (context.validationEnabled ? "true" : "false") << ','
        << "\"usedAdrenotools\":" << (context.icd.usedAdrenotools ? "true" : "false") << ','
        << "\"instanceExtensions\":" << jsonStringArray(identity.instanceExtensions) << ','
        << "\"deviceExtensions\":" << jsonStringArray(identity.deviceExtensions)
        << '}';
    return out.str();
}

}  // namespace amaral
