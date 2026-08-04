#include <jni.h>

#include <adrenotools/driver.h>
#include <dlfcn.h>
#include <vulkan/vulkan.h>

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstddef>
#include <cstring>
#include <iomanip>
#include <fstream>
#include <limits>
#include <numeric>
#include <sstream>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

namespace {

constexpr char kShaderCompileId[] = "shader_compile_pipeline";
constexpr char kRenderPassTilingId[] = "renderpass_tiling_gmem";
constexpr char kComputeArithmeticId[] = "compute_arithmetic";
constexpr char kStableSceneId[] = "stable_scene_frametime";
constexpr char kThermalSustainId[] = "thermal_sustain_efficiency";
constexpr char kTraceReplayId[] = "vulkan_command_trace_replay";
constexpr char kMixedTraceId[] = "mixed_graphics_compute_barrier";
constexpr char kComputeChainTraceId[] = "compute_dependency_chain";
constexpr uint32_t kTraceFormatVersion = 1;
constexpr uint32_t kTraceVersion = 1;
constexpr uint32_t kTraceWidth = 320;
constexpr uint32_t kTraceHeight = 180;
constexpr uint32_t kTraceComputeWords = 65536;
constexpr uint32_t kWorkloadVersion = 1;
constexpr uint32_t kShaderPipelineCount = 24;
constexpr uint32_t kTilingDrawCount = 2048;
constexpr uint32_t kStableDrawCount = 512;
constexpr uint32_t kRenderWidth = 640;
constexpr uint32_t kRenderHeight = 360;
constexpr uint32_t kComputeElementCount = 1U << 20U;
constexpr uint32_t kComputeIterations = 256;
constexpr uint32_t kComputeDispatchesPerSample = 4;
constexpr uint32_t kComputeOperationsPerIteration = 8;
constexpr uint32_t kThermalWindowSeconds = 5;

static const uint32_t kDrawVertexSpirv[] =
#include "phase2_draw_vert.inc"
;
static const uint32_t kAluFragmentSpirv[] =
#include "phase2_alu_frag.inc"
;
static const uint32_t kBranchFragmentSpirv[] =
#include "phase2_branch_frag.inc"
;
static const uint32_t kComputeSpirv[] =
#include "phase2_compute_comp.inc"
;
static const uint32_t kTraceSolidFragmentSpirv[] =
#include "trace_solid_frag.inc"
;
static const uint32_t kTraceIntegerComputeSpirv[] =
#include "trace_integer_comp.inc"
;
static const uint32_t kDiagnosticBranchSpirv[] =
#include "diag_branch_comp.inc"
;
static const uint32_t kDiagnosticAtomicSpirv[] =
#include "diag_atomic_comp.inc"
;
static const uint32_t kDiagnosticSharedSpirv[] =
#include "diag_shared_comp.inc"
;
static const uint32_t kDiagnosticLargeSpirv[] =
#include "diag_large_comp.inc"
;
static const uint32_t kDiagnosticEmptySpirv[] =
#include "diag_empty_comp.inc"
;

using Clock = std::chrono::steady_clock;

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

std::string withTrailingSlash(std::string path) {
    if (!path.empty() && path.back() != '/') path.push_back('/');
    return path;
}

double elapsedMs(Clock::time_point start, Clock::time_point end = Clock::now()) {
    return std::chrono::duration<double, std::milli>(end - start).count();
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

double median(const std::vector<double> &values) {
    return percentile(values, 0.5);
}

double mean(const std::vector<double> &values) {
    if (values.empty()) return std::numeric_limits<double>::quiet_NaN();
    return std::accumulate(values.begin(), values.end(), 0.0) /
           static_cast<double>(values.size());
}

void appendDoubleArray(std::ostringstream &json, const std::vector<double> &values) {
    json << '[';
    for (size_t index = 0; index < values.size(); ++index) {
        if (index > 0) json << ',';
        json << std::setprecision(12) << values[index];
    }
    json << ']';
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

bool hasExtension(const std::vector<VkExtensionProperties> &extensions, const char *name) {
    return std::any_of(extensions.begin(), extensions.end(), [name](const auto &extension) {
        return std::strcmp(extension.extensionName, name) == 0;
    });
}

std::string versionString(uint32_t version) {
    std::ostringstream output;
    output << VK_VERSION_MAJOR(version) << '.' << VK_VERSION_MINOR(version)
           << '.' << VK_VERSION_PATCH(version);
    return output.str();
}

std::pair<int, int> parseMesaMajorMinor(const std::string &value) {
    size_t position = value.find("Mesa");
    if (position == std::string::npos) return {-1, -1};
    position += 4;
    while (position < value.size() && (value[position] < '0' || value[position] > '9')) ++position;
    if (position >= value.size()) return {-1, -1};
    int major = 0;
    while (position < value.size() && value[position] >= '0' && value[position] <= '9') {
        major = major * 10 + (value[position++] - '0');
    }
    if (position >= value.size() || value[position] != '.') return {major, -1};
    ++position;
    int minor = 0;
    bool found = false;
    while (position < value.size() && value[position] >= '0' && value[position] <= '9') {
        found = true;
        minor = minor * 10 + (value[position++] - '0');
    }
    return {major, found ? minor : -1};
}

const char *driverIdName(VkDriverId id) {
    switch (id) {
        case VK_DRIVER_ID_QUALCOMM_PROPRIETARY: return "QUALCOMM_PROPRIETARY";
        case VK_DRIVER_ID_MESA_TURNIP: return "MESA_TURNIP";
        case VK_DRIVER_ID_GOOGLE_SWIFTSHADER: return "GOOGLE_SWIFTSHADER";
        case VK_DRIVER_ID_MESA_LLVMPIPE: return "MESA_LLVMPIPE";
        default: return "OTHER_OR_UNKNOWN";
    }
}

struct BufferResource {
    VkBuffer buffer = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkDeviceSize allocationSize = 0;
    VkMemoryPropertyFlags memoryFlags = 0;
};

struct ImageResource {
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkImageView view = VK_NULL_HANDLE;
};

struct TimestampQuery {
    VkQueryPool pool = VK_NULL_HANDLE;
    bool supported = false;
};

class VulkanContext {
public:
    ~VulkanContext() { cleanup(); }

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
        auto enumerateInstanceVersion = reinterpret_cast<PFN_vkEnumerateInstanceVersion>(
                dlsym(library, "vkEnumerateInstanceVersion"));
        uint32_t loaderVersion = VK_API_VERSION_1_0;
        if (enumerateInstanceVersion != nullptr
                && enumerateInstanceVersion(&loaderVersion) != VK_SUCCESS) {
            loaderVersion = VK_API_VERSION_1_0;
        }

        VkApplicationInfo app{};
        app.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
        app.pApplicationName = "Amaral Driver Lab";
        app.applicationVersion = VK_MAKE_VERSION(0, 3, 0);
        app.pEngineName = "Phase 2 real workloads";
        app.engineVersion = VK_MAKE_VERSION(1, 0, 0);
        app.apiVersion = loaderVersion >= VK_API_VERSION_1_1
                ? VK_API_VERSION_1_1 : VK_API_VERSION_1_0;
        VkInstanceCreateInfo instanceInfo{};
        instanceInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
        instanceInfo.pApplicationInfo = &app;
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
        getPhysicalDeviceImageFormatProperties =
                requireInstance<PFN_vkGetPhysicalDeviceImageFormatProperties>(
                        getInstanceProcAddr, instance,
                        "vkGetPhysicalDeviceImageFormatProperties");
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
        uint32_t count = 0;
        check(enumeratePhysicalDevices(instance, &count, nullptr),
              "vkEnumeratePhysicalDevices(count)");
        if (count == 0) throw std::runtime_error("Driver exposed no Vulkan device");
        std::vector<VkPhysicalDevice> devices(count);
        check(enumeratePhysicalDevices(instance, &count, devices.data()),
              "vkEnumeratePhysicalDevices(list)");
        physicalDevice = devices.front();
        getPhysicalDeviceProperties(physicalDevice, &properties);
        getPhysicalDeviceFeatures(physicalDevice, &features);
        getPhysicalDeviceMemoryProperties(physicalDevice, &memoryProperties);

        uint32_t extensionCount = 0;
        check(enumerateDeviceExtensionProperties(physicalDevice, nullptr, &extensionCount, nullptr),
              "vkEnumerateDeviceExtensionProperties(count)");
        extensions.resize(extensionCount);
        if (extensionCount > 0) {
            check(enumerateDeviceExtensionProperties(physicalDevice, nullptr, &extensionCount,
                                                     extensions.data()),
                  "vkEnumerateDeviceExtensionProperties(list)");
        }
        std::sort(extensions.begin(), extensions.end(), [](const auto &left, const auto &right) {
            return std::strcmp(left.extensionName, right.extensionName) < 0;
        });

        uint32_t queueCount = 0;
        getPhysicalDeviceQueueFamilyProperties(physicalDevice, &queueCount, nullptr);
        std::vector<VkQueueFamilyProperties> queues(queueCount);
        getPhysicalDeviceQueueFamilyProperties(physicalDevice, &queueCount, queues.data());
        bool found = false;
        for (uint32_t index = 0; index < queueCount; ++index) {
            const VkQueueFlags required = VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT;
            if (queues[index].queueCount > 0 && (queues[index].queueFlags & required) == required) {
                queueFamilyIndex = index;
                queueFamilyProperties = queues[index];
                found = true;
                break;
            }
        }
        if (!found) throw std::runtime_error("Driver exposed no graphics+compute queue");

        std::vector<const char *> enabledExtensions;
        void *devicePNext = nullptr;
#if defined(VK_EXT_pipeline_creation_cache_control)
        VkPhysicalDevicePipelineCreationCacheControlFeaturesEXT cacheControlFeatures{};
        cacheControlFeatures.sType =
                VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PIPELINE_CREATION_CACHE_CONTROL_FEATURES_EXT;
        if (hasExtension(extensions, VK_EXT_PIPELINE_CREATION_CACHE_CONTROL_EXTENSION_NAME)
                && getPhysicalDeviceFeatures2 != nullptr) {
            VkPhysicalDeviceFeatures2 featureQuery{};
            featureQuery.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
            featureQuery.pNext = &cacheControlFeatures;
            getPhysicalDeviceFeatures2(physicalDevice, &featureQuery);
            if (cacheControlFeatures.pipelineCreationCacheControl == VK_TRUE) {
                enabledExtensions.push_back(VK_EXT_PIPELINE_CREATION_CACHE_CONTROL_EXTENSION_NAME);
                cacheControlFeatures.pipelineCreationCacheControl = VK_TRUE;
                cacheControlEnabled = true;
                devicePNext = &cacheControlFeatures;
            }
        }
#endif

        float priority = 1.0F;
        VkDeviceQueueCreateInfo queueInfo{};
        queueInfo.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
        queueInfo.queueFamilyIndex = queueFamilyIndex;
        queueInfo.queueCount = 1;
        queueInfo.pQueuePriorities = &priority;
        VkDeviceCreateInfo deviceInfo{};
        deviceInfo.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
        deviceInfo.pNext = devicePNext;
        deviceInfo.queueCreateInfoCount = 1;
        deviceInfo.pQueueCreateInfos = &queueInfo;
        deviceInfo.enabledExtensionCount = static_cast<uint32_t>(enabledExtensions.size());
        deviceInfo.ppEnabledExtensionNames = enabledExtensions.data();
        stage = "create_device";
        check(createDevice(physicalDevice, &deviceInfo, nullptr, &device), "vkCreateDevice");
        loadDeviceFunctions();
        vkGetDeviceQueue(device, queueFamilyIndex, 0, &queue);
        capabilities = buildCapabilitiesJson();
    }

    void setStage(const std::string &value) { stage = value; }
    const std::string &currentStage() const { return stage; }
    bool isCustomDriver() const { return customDriver; }
    bool timestampsSupported() const {
        return queueFamilyProperties.timestampValidBits > 0 && properties.limits.timestampPeriod > 0.0F;
    }

    bool supportsExtension(const char *name) const {
        return hasExtension(extensions, name);
    }

    VkResult queryImageFormatProperties(VkFormat format, VkImageType type,
                                        VkImageTiling tiling, VkImageUsageFlags usage,
                                        VkImageCreateFlags flags,
                                        VkImageFormatProperties *output) const {
        return getPhysicalDeviceImageFormatProperties(
                physicalDevice, format, type, tiling, usage, flags, output);
    }

    void queryFormatProperties(VkFormat format, VkFormatProperties *output) const {
        getPhysicalDeviceFormatProperties(physicalDevice, format, output);
    }

    uint32_t findMemoryType(uint32_t bits, VkMemoryPropertyFlags required,
                            VkMemoryPropertyFlags preferred,
                            VkMemoryPropertyFlags *selectedFlags = nullptr) const {
        int fallback = -1;
        for (uint32_t index = 0; index < memoryProperties.memoryTypeCount; ++index) {
            if ((bits & (1U << index)) == 0) continue;
            VkMemoryPropertyFlags flags = memoryProperties.memoryTypes[index].propertyFlags;
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

    BufferResource createBuffer(VkDeviceSize size, VkBufferUsageFlags usage,
                                VkMemoryPropertyFlags required,
                                VkMemoryPropertyFlags preferred = 0) {
        BufferResource output;
        VkBufferCreateInfo info{};
        info.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
        info.size = size;
        info.usage = usage;
        info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        check(vkCreateBuffer(device, &info, nullptr, &output.buffer), "vkCreateBuffer");
        VkMemoryRequirements requirements{};
        vkGetBufferMemoryRequirements(device, output.buffer, &requirements);
        VkMemoryAllocateInfo allocation{};
        allocation.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        allocation.allocationSize = requirements.size;
        allocation.memoryTypeIndex = findMemoryType(requirements.memoryTypeBits, required,
                                                     preferred, &output.memoryFlags);
        check(vkAllocateMemory(device, &allocation, nullptr, &output.memory),
              "vkAllocateMemory(buffer)");
        check(vkBindBufferMemory(device, output.buffer, output.memory, 0), "vkBindBufferMemory");
        output.allocationSize = requirements.size;
        return output;
    }

    void destroyBuffer(BufferResource &resource) {
        if (resource.buffer != VK_NULL_HANDLE) vkDestroyBuffer(device, resource.buffer, nullptr);
        if (resource.memory != VK_NULL_HANDLE) vkFreeMemory(device, resource.memory, nullptr);
        resource = {};
    }

    ImageResource createImage(uint32_t width, uint32_t height, VkFormat format,
                              VkSampleCountFlagBits samples, VkImageUsageFlags usage,
                              VkImageAspectFlags aspect) {
        ImageResource output;
        VkImageCreateInfo info{};
        info.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
        info.imageType = VK_IMAGE_TYPE_2D;
        info.format = format;
        info.extent = {width, height, 1};
        info.mipLevels = 1;
        info.arrayLayers = 1;
        info.samples = samples;
        info.tiling = VK_IMAGE_TILING_OPTIMAL;
        info.usage = usage;
        info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        info.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        check(vkCreateImage(device, &info, nullptr, &output.image), "vkCreateImage");
        VkMemoryRequirements requirements{};
        vkGetImageMemoryRequirements(device, output.image, &requirements);
        VkMemoryAllocateInfo allocation{};
        allocation.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        allocation.allocationSize = requirements.size;
        allocation.memoryTypeIndex = findMemoryType(requirements.memoryTypeBits, 0,
                                                     VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        check(vkAllocateMemory(device, &allocation, nullptr, &output.memory),
              "vkAllocateMemory(image)");
        check(vkBindImageMemory(device, output.image, output.memory, 0), "vkBindImageMemory");
        VkImageViewCreateInfo view{};
        view.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
        view.image = output.image;
        view.viewType = VK_IMAGE_VIEW_TYPE_2D;
        view.format = format;
        view.subresourceRange.aspectMask = aspect;
        view.subresourceRange.levelCount = 1;
        view.subresourceRange.layerCount = 1;
        check(vkCreateImageView(device, &view, nullptr, &output.view), "vkCreateImageView");
        return output;
    }

    void destroyImage(ImageResource &resource) {
        if (resource.view != VK_NULL_HANDLE) vkDestroyImageView(device, resource.view, nullptr);
        if (resource.image != VK_NULL_HANDLE) vkDestroyImage(device, resource.image, nullptr);
        if (resource.memory != VK_NULL_HANDLE) vkFreeMemory(device, resource.memory, nullptr);
        resource = {};
    }

    VkFormat depthFormat() const {
        const std::array<VkFormat, 3> candidates{
                VK_FORMAT_D32_SFLOAT,
                VK_FORMAT_D24_UNORM_S8_UINT,
                VK_FORMAT_D16_UNORM};
        for (VkFormat format : candidates) {
            VkFormatProperties formatProperties{};
            getPhysicalDeviceFormatProperties(physicalDevice, format, &formatProperties);
            if ((formatProperties.optimalTilingFeatures
                    & VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT) != 0) return format;
        }
        throw std::runtime_error("No supported depth attachment format");
    }

    VkShaderModule createShader(const uint32_t *code, size_t size) {
        VkShaderModuleCreateInfo info{};
        info.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
        info.codeSize = size;
        info.pCode = code;
        VkShaderModule shader = VK_NULL_HANDLE;
        check(vkCreateShaderModule(device, &info, nullptr, &shader), "vkCreateShaderModule");
        return shader;
    }

    VkCommandPool createCommandPool() {
        VkCommandPoolCreateInfo info{};
        info.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
        info.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
        info.queueFamilyIndex = queueFamilyIndex;
        VkCommandPool pool = VK_NULL_HANDLE;
        check(vkCreateCommandPool(device, &info, nullptr, &pool), "vkCreateCommandPool");
        return pool;
    }

    VkCommandBuffer allocateCommandBuffer(VkCommandPool pool) {
        VkCommandBufferAllocateInfo info{};
        info.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
        info.commandPool = pool;
        info.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        info.commandBufferCount = 1;
        VkCommandBuffer command = VK_NULL_HANDLE;
        check(vkAllocateCommandBuffers(device, &info, &command), "vkAllocateCommandBuffers");
        return command;
    }

    TimestampQuery createTimestampQuery() {
        TimestampQuery query;
        query.supported = timestampsSupported();
        if (!query.supported) return query;
        VkQueryPoolCreateInfo info{};
        info.sType = VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO;
        info.queryType = VK_QUERY_TYPE_TIMESTAMP;
        info.queryCount = 2;
        check(vkCreateQueryPool(device, &info, nullptr, &query.pool), "vkCreateQueryPool");
        return query;
    }

    void destroyTimestampQuery(TimestampQuery &query) {
        if (query.pool != VK_NULL_HANDLE) vkDestroyQueryPool(device, query.pool, nullptr);
        query = {};
    }

    double submitTimed(VkCommandBuffer command, TimestampQuery &query) {
        auto start = Clock::now();
        VkSubmitInfo submit{};
        submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        submit.commandBufferCount = 1;
        submit.pCommandBuffers = &command;
        check(vkQueueSubmit(queue, 1, &submit, VK_NULL_HANDLE), "vkQueueSubmit");
        check(vkQueueWaitIdle(queue), "vkQueueWaitIdle");
        const double wallMs = elapsedMs(start);
        if (!query.supported || query.pool == VK_NULL_HANDLE) return wallMs;
        std::array<uint64_t, 2> timestamps{};
        VkResult result = vkGetQueryPoolResults(
                device, query.pool, 0, 2, sizeof(timestamps), timestamps.data(),
                sizeof(uint64_t), VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WAIT_BIT);
        if (result != VK_SUCCESS || timestamps[1] <= timestamps[0]) return wallMs;
        const uint64_t mask = queueFamilyProperties.timestampValidBits >= 64
                ? std::numeric_limits<uint64_t>::max()
                : ((1ULL << queueFamilyProperties.timestampValidBits) - 1ULL);
        const uint64_t delta = (timestamps[1] - timestamps[0]) & mask;
        const double gpuMs = static_cast<double>(delta) * properties.limits.timestampPeriod / 1.0e6;
        return gpuMs > 0.0 && std::isfinite(gpuMs) ? gpuMs : wallMs;
    }

    void beginTimedCommand(VkCommandBuffer command, TimestampQuery &query) {
        VkCommandBufferBeginInfo begin{};
        begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
        begin.flags = VK_COMMAND_BUFFER_USAGE_SIMULTANEOUS_USE_BIT;
        check(vkBeginCommandBuffer(command, &begin), "vkBeginCommandBuffer");
        if (query.supported) {
            vkCmdResetQueryPool(command, query.pool, 0, 2);
            vkCmdWriteTimestamp(command, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, query.pool, 0);
        }
    }

    void endTimedCommand(VkCommandBuffer command, TimestampQuery &query) {
        if (query.supported) {
            vkCmdWriteTimestamp(command, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, query.pool, 1);
        }
        check(vkEndCommandBuffer(command), "vkEndCommandBuffer");
    }

    std::string failureJson(const std::string &workloadId, const std::exception &error) const {
        std::string type = "native_error";
        int resultCode = std::numeric_limits<int>::max();
        std::string operation;
        if (const auto *failure = dynamic_cast<const VulkanFailure *>(&error)) {
            resultCode = static_cast<int>(failure->result);
            operation = failure->operation;
            type = failure->result == VK_ERROR_DEVICE_LOST
                    ? "vk_error_device_lost" : "vulkan_error";
        }
        std::ostringstream json;
        json << "{\"success\":false"
             << ",\"workload_id\":\"" << jsonEscape(workloadId) << "\""
             << ",\"workload_version\":" << kWorkloadVersion
             << ",\"custom_driver\":" << (customDriver ? "true" : "false")
             << ",\"failure_type\":\"" << type << "\""
             << ",\"failure_stage\":\"" << jsonEscape(stage) << "\""
             << ",\"error\":\"" << jsonEscape(error.what()) << "\"";
        if (!operation.empty()) json << ",\"vulkan_operation\":\"" << jsonEscape(operation) << "\"";
        if (resultCode != std::numeric_limits<int>::max()) json << ",\"vk_result\":" << resultCode;
        if (!capabilities.empty()) json << ",\"capabilities\":" << capabilities;
        json << '}';
        return json.str();
    }

    std::string capabilities;
    VkPhysicalDeviceProperties properties{};
    VkPhysicalDeviceFeatures features{};
    VkPhysicalDeviceMemoryProperties memoryProperties{};
    VkQueueFamilyProperties queueFamilyProperties{};
    bool cacheControlEnabled = false;

    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    uint32_t queueFamilyIndex = 0;

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
    PFN_vkCreateComputePipelines vkCreateComputePipelines = nullptr;
    PFN_vkDestroyPipeline vkDestroyPipeline = nullptr;
    PFN_vkCreatePipelineCache vkCreatePipelineCache = nullptr;
    PFN_vkDestroyPipelineCache vkDestroyPipelineCache = nullptr;
    PFN_vkGetPipelineCacheData vkGetPipelineCacheData = nullptr;
    PFN_vkCreateDescriptorSetLayout vkCreateDescriptorSetLayout = nullptr;
    PFN_vkDestroyDescriptorSetLayout vkDestroyDescriptorSetLayout = nullptr;
    PFN_vkCreateDescriptorPool vkCreateDescriptorPool = nullptr;
    PFN_vkDestroyDescriptorPool vkDestroyDescriptorPool = nullptr;
    PFN_vkAllocateDescriptorSets vkAllocateDescriptorSets = nullptr;
    PFN_vkUpdateDescriptorSets vkUpdateDescriptorSets = nullptr;
    PFN_vkCreateCommandPool vkCreateCommandPool = nullptr;
    PFN_vkDestroyCommandPool vkDestroyCommandPool = nullptr;
    PFN_vkAllocateCommandBuffers vkAllocateCommandBuffers = nullptr;
    PFN_vkBeginCommandBuffer vkBeginCommandBuffer = nullptr;
    PFN_vkEndCommandBuffer vkEndCommandBuffer = nullptr;
    PFN_vkCmdResetQueryPool vkCmdResetQueryPool = nullptr;
    PFN_vkCmdWriteTimestamp vkCmdWriteTimestamp = nullptr;
    PFN_vkCmdPipelineBarrier vkCmdPipelineBarrier = nullptr;
    PFN_vkCmdBeginRenderPass vkCmdBeginRenderPass = nullptr;
    PFN_vkCmdEndRenderPass vkCmdEndRenderPass = nullptr;
    PFN_vkCmdBindPipeline vkCmdBindPipeline = nullptr;
    PFN_vkCmdPushConstants vkCmdPushConstants = nullptr;
    PFN_vkCmdDraw vkCmdDraw = nullptr;
    PFN_vkCmdBindDescriptorSets vkCmdBindDescriptorSets = nullptr;
    PFN_vkCmdDispatch vkCmdDispatch = nullptr;
    PFN_vkCmdCopyBuffer vkCmdCopyBuffer = nullptr;
    PFN_vkCmdCopyImageToBuffer vkCmdCopyImageToBuffer = nullptr;
    PFN_vkQueueSubmit vkQueueSubmit = nullptr;
    PFN_vkQueueWaitIdle vkQueueWaitIdle = nullptr;
    PFN_vkDeviceWaitIdle vkDeviceWaitIdle = nullptr;
    PFN_vkCreateQueryPool vkCreateQueryPool = nullptr;
    PFN_vkDestroyQueryPool vkDestroyQueryPool = nullptr;
    PFN_vkGetQueryPoolResults vkGetQueryPoolResults = nullptr;
    PFN_vkCreateFence vkCreateFence = nullptr;
    PFN_vkDestroyFence vkDestroyFence = nullptr;
    PFN_vkWaitForFences vkWaitForFences = nullptr;
    PFN_vkResetFences vkResetFences = nullptr;
    PFN_vkCreateSemaphore vkCreateSemaphore = nullptr;
    PFN_vkDestroySemaphore vkDestroySemaphore = nullptr;
    PFN_vkCmdFillBuffer vkCmdFillBuffer = nullptr;

private:
    void loadDeviceFunctions() {
#define LOAD(name) name = requireDevice<PFN_##name>(getDeviceProcAddr, device, #name)
        LOAD(vkDestroyDevice);
        LOAD(vkGetDeviceQueue);
        LOAD(vkCreateBuffer);
        LOAD(vkDestroyBuffer);
        LOAD(vkGetBufferMemoryRequirements);
        LOAD(vkAllocateMemory);
        LOAD(vkFreeMemory);
        LOAD(vkBindBufferMemory);
        LOAD(vkMapMemory);
        LOAD(vkUnmapMemory);
        LOAD(vkFlushMappedMemoryRanges);
        LOAD(vkInvalidateMappedMemoryRanges);
        LOAD(vkCreateImage);
        LOAD(vkDestroyImage);
        LOAD(vkGetImageMemoryRequirements);
        LOAD(vkBindImageMemory);
        LOAD(vkCreateImageView);
        LOAD(vkDestroyImageView);
        LOAD(vkCreateRenderPass);
        LOAD(vkDestroyRenderPass);
        LOAD(vkCreateFramebuffer);
        LOAD(vkDestroyFramebuffer);
        LOAD(vkCreateShaderModule);
        LOAD(vkDestroyShaderModule);
        LOAD(vkCreatePipelineLayout);
        LOAD(vkDestroyPipelineLayout);
        LOAD(vkCreateGraphicsPipelines);
        LOAD(vkCreateComputePipelines);
        LOAD(vkDestroyPipeline);
        LOAD(vkCreatePipelineCache);
        LOAD(vkDestroyPipelineCache);
        LOAD(vkGetPipelineCacheData);
        LOAD(vkCreateDescriptorSetLayout);
        LOAD(vkDestroyDescriptorSetLayout);
        LOAD(vkCreateDescriptorPool);
        LOAD(vkDestroyDescriptorPool);
        LOAD(vkAllocateDescriptorSets);
        LOAD(vkUpdateDescriptorSets);
        LOAD(vkCreateCommandPool);
        LOAD(vkDestroyCommandPool);
        LOAD(vkAllocateCommandBuffers);
        LOAD(vkBeginCommandBuffer);
        LOAD(vkEndCommandBuffer);
        LOAD(vkCmdResetQueryPool);
        LOAD(vkCmdWriteTimestamp);
        LOAD(vkCmdPipelineBarrier);
        LOAD(vkCmdBeginRenderPass);
        LOAD(vkCmdEndRenderPass);
        LOAD(vkCmdBindPipeline);
        LOAD(vkCmdPushConstants);
        LOAD(vkCmdDraw);
        LOAD(vkCmdBindDescriptorSets);
        LOAD(vkCmdDispatch);
        LOAD(vkCmdCopyBuffer);
        LOAD(vkCmdCopyImageToBuffer);
        LOAD(vkQueueSubmit);
        LOAD(vkQueueWaitIdle);
        LOAD(vkDeviceWaitIdle);
        LOAD(vkCreateQueryPool);
        LOAD(vkDestroyQueryPool);
        LOAD(vkGetQueryPoolResults);
        LOAD(vkCreateFence);
        LOAD(vkDestroyFence);
        LOAD(vkWaitForFences);
        LOAD(vkResetFences);
        LOAD(vkCreateSemaphore);
        LOAD(vkDestroySemaphore);
        LOAD(vkCmdFillBuffer);
#undef LOAD
    }

    std::string buildCapabilitiesJson() {
        VkPhysicalDeviceDriverProperties driverProperties{};
        bool hasDriverProperties = false;
        driverProperties.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DRIVER_PROPERTIES;
        if (getPhysicalDeviceProperties2 != nullptr
                && (properties.apiVersion >= VK_API_VERSION_1_2
                    || hasExtension(extensions, VK_KHR_DRIVER_PROPERTIES_EXTENSION_NAME))) {
            VkPhysicalDeviceProperties2 properties2{};
            properties2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2;
            properties2.pNext = &driverProperties;
            getPhysicalDeviceProperties2(physicalDevice, &properties2);
            properties = properties2.properties;
            hasDriverProperties = true;
        }
        std::ostringstream json;
        json << '{'
             << "\"gpu_name\":\"" << jsonEscape(properties.deviceName) << "\""
             << ",\"vendor_id\":" << properties.vendorID
             << ",\"device_id\":" << properties.deviceID
             << ",\"api_version\":\"" << versionString(properties.apiVersion) << "\""
             << ",\"driver_version_raw\":" << properties.driverVersion;
        if (hasDriverProperties) {
            const std::string info(driverProperties.driverInfo);
            const auto mesa = parseMesaMajorMinor(info);
            json << ",\"driver_id\":" << static_cast<int>(driverProperties.driverID)
                 << ",\"driver_id_name\":\"" << driverIdName(driverProperties.driverID) << "\""
                 << ",\"driver_name\":\"" << jsonEscape(driverProperties.driverName) << "\""
                 << ",\"driver_info\":\"" << jsonEscape(info) << "\""
                 << ",\"conformance_version\":\""
                 << static_cast<unsigned int>(driverProperties.conformanceVersion.major) << '.'
                 << static_cast<unsigned int>(driverProperties.conformanceVersion.minor) << '.'
                 << static_cast<unsigned int>(driverProperties.conformanceVersion.subminor) << '.'
                 << static_cast<unsigned int>(driverProperties.conformanceVersion.patch) << "\""
                 << ",\"mesa_version_major\":"
                 << (mesa.first >= 0 ? std::to_string(mesa.first) : "null")
                 << ",\"mesa_version_minor\":"
                 << (mesa.second >= 0 ? std::to_string(mesa.second) : "null");
        } else {
            json << ",\"driver_id\":null,\"driver_id_name\":null"
                 << ",\"driver_name\":null,\"driver_info\":null"
                 << ",\"conformance_version\":null"
                 << ",\"mesa_version_major\":null,\"mesa_version_minor\":null";
        }
        json << ",\"extensions\":[";
        for (size_t index = 0; index < extensions.size(); ++index) {
            if (index > 0) json << ',';
            json << '"' << jsonEscape(extensions[index].extensionName) << '"';
        }
        json << "]";
        json << ",\"features\":{"
             << "\"geometryShader\":" << (features.geometryShader ? "true" : "false")
             << ",\"tessellationShader\":" << (features.tessellationShader ? "true" : "false")
             << ",\"sampleRateShading\":" << (features.sampleRateShading ? "true" : "false")
             << ",\"pipelineStatisticsQuery\":"
             << (features.pipelineStatisticsQuery ? "true" : "false")
             << ",\"shaderInt64\":" << (features.shaderInt64 ? "true" : "false")
             << ",\"shaderFloat64\":" << (features.shaderFloat64 ? "true" : "false")
             << '}';
        json << ",\"limits\":{"
             << "\"max_bound_descriptor_sets\":" << properties.limits.maxBoundDescriptorSets
             << ",\"max_per_stage_resources\":" << properties.limits.maxPerStageResources
             << ",\"max_compute_shared_memory_size\":"
             << properties.limits.maxComputeSharedMemorySize
             << ",\"max_compute_work_group_invocations\":"
             << properties.limits.maxComputeWorkGroupInvocations
             << ",\"framebuffer_color_sample_counts\":"
             << properties.limits.framebufferColorSampleCounts
             << ",\"framebuffer_depth_sample_counts\":"
             << properties.limits.framebufferDepthSampleCounts
             << ",\"timestamp_period_ns\":" << properties.limits.timestampPeriod
             << '}';
        json << '}';
        return json.str();
    }

    void cleanup() {
        if (device != VK_NULL_HANDLE && vkDeviceWaitIdle != nullptr) vkDeviceWaitIdle(device);
        if (device != VK_NULL_HANDLE && vkDestroyDevice != nullptr) vkDestroyDevice(device, nullptr);
        device = VK_NULL_HANDLE;
        if (instance != VK_NULL_HANDLE && destroyInstance != nullptr) destroyInstance(instance, nullptr);
        instance = VK_NULL_HANDLE;
        if (library != nullptr) dlclose(library);
        library = nullptr;
    }

    std::string stage = "not_started";
    bool customDriver = false;
    void *library = nullptr;
    std::vector<VkExtensionProperties> extensions;
    PFN_vkGetInstanceProcAddr getInstanceProcAddr = nullptr;
    PFN_vkCreateInstance createInstance = nullptr;
    PFN_vkDestroyInstance destroyInstance = nullptr;
    PFN_vkEnumeratePhysicalDevices enumeratePhysicalDevices = nullptr;
    PFN_vkGetPhysicalDeviceProperties getPhysicalDeviceProperties = nullptr;
    PFN_vkGetPhysicalDeviceFeatures getPhysicalDeviceFeatures = nullptr;
    PFN_vkGetPhysicalDeviceProperties2 getPhysicalDeviceProperties2 = nullptr;
    PFN_vkGetPhysicalDeviceFeatures2 getPhysicalDeviceFeatures2 = nullptr;
    PFN_vkGetPhysicalDeviceQueueFamilyProperties getPhysicalDeviceQueueFamilyProperties = nullptr;
    PFN_vkGetPhysicalDeviceMemoryProperties getPhysicalDeviceMemoryProperties = nullptr;
    PFN_vkGetPhysicalDeviceFormatProperties getPhysicalDeviceFormatProperties = nullptr;
    PFN_vkGetPhysicalDeviceImageFormatProperties getPhysicalDeviceImageFormatProperties = nullptr;
    PFN_vkEnumerateDeviceExtensionProperties enumerateDeviceExtensionProperties = nullptr;
    PFN_vkCreateDevice createDevice = nullptr;
    PFN_vkGetDeviceProcAddr getDeviceProcAddr = nullptr;
};

struct DrawPush {
    float transform[4];
    float color[4];
};

struct RenderSetup {
    VkRenderPass renderPass = VK_NULL_HANDLE;
    VkFramebuffer framebuffer = VK_NULL_HANDLE;
    VkPipelineLayout layout = VK_NULL_HANDLE;
    VkPipeline pipeline = VK_NULL_HANDLE;
    VkCommandPool commandPool = VK_NULL_HANDLE;
    VkCommandBuffer command = VK_NULL_HANDLE;
    TimestampQuery query;
    ImageResource color;
    ImageResource depth;
};

class Phase2Workloads {
public:
    explicit Phase2Workloads(VulkanContext &contextValue) : context(contextValue) {
        drawVertex = context.createShader(kDrawVertexSpirv, sizeof(kDrawVertexSpirv));
        aluFragment = context.createShader(kAluFragmentSpirv, sizeof(kAluFragmentSpirv));
        branchFragment = context.createShader(kBranchFragmentSpirv, sizeof(kBranchFragmentSpirv));
        computeShader = context.createShader(kComputeSpirv, sizeof(kComputeSpirv));
        traceFragment = context.createShader(kTraceSolidFragmentSpirv, sizeof(kTraceSolidFragmentSpirv));
        traceCompute = context.createShader(kTraceIntegerComputeSpirv, sizeof(kTraceIntegerComputeSpirv));
    }

    ~Phase2Workloads() {
        if (traceCompute != VK_NULL_HANDLE) context.vkDestroyShaderModule(context.device, traceCompute, nullptr);
        if (traceFragment != VK_NULL_HANDLE) context.vkDestroyShaderModule(context.device, traceFragment, nullptr);
        if (computeShader != VK_NULL_HANDLE) context.vkDestroyShaderModule(context.device, computeShader, nullptr);
        if (branchFragment != VK_NULL_HANDLE) context.vkDestroyShaderModule(context.device, branchFragment, nullptr);
        if (aluFragment != VK_NULL_HANDLE) context.vkDestroyShaderModule(context.device, aluFragment, nullptr);
        if (drawVertex != VK_NULL_HANDLE) context.vkDestroyShaderModule(context.device, drawVertex, nullptr);
    }

    std::string run(const std::string &workloadId, int warmupSeconds, int measureSeconds) {
        if (workloadId == kShaderCompileId) return runShaderCompile();
        if (workloadId == kRenderPassTilingId) {
            return runRenderPassTiling(std::max(0, warmupSeconds), std::max(1, measureSeconds));
        }
        if (workloadId == kComputeArithmeticId) {
            return runCompute(std::max(0, warmupSeconds), std::max(1, measureSeconds), false);
        }
        if (workloadId == kStableSceneId) {
            return runStableScene(std::max(0, warmupSeconds), std::max(1, measureSeconds));
        }
        if (workloadId == kThermalSustainId) {
            return runCompute(std::max(0, warmupSeconds),
                              std::max(30, std::min(measureSeconds, 900)), true);
        }
        throw std::invalid_argument("Unsupported phase two workload: " + workloadId);
    }

    std::string runTrace(const std::string &traceId, int warmupSeconds, int measureSeconds,
                         const std::string &rawOutputPath) {
        if (traceId != kMixedTraceId && traceId != kComputeChainTraceId) {
            throw std::invalid_argument("Unsupported trace: " + traceId);
        }
        return runTraceReplay(traceId, std::max(0, warmupSeconds),
                              std::max(1, measureSeconds), rawOutputPath);
    }

private:
    VkPipelineLayout createDrawLayout() {
        VkPushConstantRange range{};
        range.stageFlags = VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT;
        range.offset = 0;
        range.size = sizeof(DrawPush);
        VkPipelineLayoutCreateInfo info{};
        info.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
        info.pushConstantRangeCount = 1;
        info.pPushConstantRanges = &range;
        VkPipelineLayout layout = VK_NULL_HANDLE;
        check(context.vkCreatePipelineLayout(context.device, &info, nullptr, &layout),
              "vkCreatePipelineLayout(draw)");
        return layout;
    }

    VkRenderPass createSimpleRenderPass(VkFormat colorFormat) {
        VkAttachmentDescription attachment{};
        attachment.format = colorFormat;
        attachment.samples = VK_SAMPLE_COUNT_1_BIT;
        attachment.loadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
        attachment.storeOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
        attachment.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        attachment.finalLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
        VkAttachmentReference reference{};
        reference.attachment = 0;
        reference.layout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
        VkSubpassDescription subpass{};
        subpass.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
        subpass.colorAttachmentCount = 1;
        subpass.pColorAttachments = &reference;
        VkRenderPassCreateInfo info{};
        info.sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO;
        info.attachmentCount = 1;
        info.pAttachments = &attachment;
        info.subpassCount = 1;
        info.pSubpasses = &subpass;
        VkRenderPass renderPass = VK_NULL_HANDLE;
        check(context.vkCreateRenderPass(context.device, &info, nullptr, &renderPass),
              "vkCreateRenderPass(shader_compile)");
        return renderPass;
    }

    VkPipeline createGraphicsPipeline(VkPipelineCache cache, VkRenderPass renderPass,
                                      VkPipelineLayout layout, uint32_t variant,
                                      VkShaderModule fragment, VkSampleCountFlagBits samples,
                                      bool depthEnabled, VkPipelineCreateFlags flags = 0) {
        VkSpecializationMapEntry vertexEntry{};
        vertexEntry.constantID = 0;
        vertexEntry.offset = 0;
        vertexEntry.size = sizeof(uint32_t);
        VkSpecializationInfo vertexSpecialization{};
        vertexSpecialization.mapEntryCount = 1;
        vertexSpecialization.pMapEntries = &vertexEntry;
        vertexSpecialization.dataSize = sizeof(variant);
        vertexSpecialization.pData = &variant;
        VkSpecializationMapEntry fragmentEntry{};
        fragmentEntry.constantID = 1;
        fragmentEntry.offset = 0;
        fragmentEntry.size = sizeof(uint32_t);
        VkSpecializationInfo fragmentSpecialization{};
        fragmentSpecialization.mapEntryCount = 1;
        fragmentSpecialization.pMapEntries = &fragmentEntry;
        fragmentSpecialization.dataSize = sizeof(variant);
        fragmentSpecialization.pData = &variant;

        std::array<VkPipelineShaderStageCreateInfo, 2> stages{};
        stages[0].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        stages[0].stage = VK_SHADER_STAGE_VERTEX_BIT;
        stages[0].module = drawVertex;
        stages[0].pName = "main";
        stages[0].pSpecializationInfo = &vertexSpecialization;
        stages[1].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        stages[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT;
        stages[1].module = fragment;
        stages[1].pName = "main";
        stages[1].pSpecializationInfo = &fragmentSpecialization;

        VkPipelineVertexInputStateCreateInfo vertexInput{};
        vertexInput.sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;
        VkPipelineInputAssemblyStateCreateInfo assembly{};
        assembly.sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO;
        assembly.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
        VkViewport viewport{};
        viewport.width = static_cast<float>(kRenderWidth);
        viewport.height = static_cast<float>(kRenderHeight);
        viewport.maxDepth = 1.0F;
        VkRect2D scissor{{0, 0}, {kRenderWidth, kRenderHeight}};
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
        multisample.rasterizationSamples = samples;
        VkPipelineDepthStencilStateCreateInfo depth{};
        depth.sType = VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO;
        depth.depthTestEnable = depthEnabled ? VK_TRUE : VK_FALSE;
        depth.depthWriteEnable = depthEnabled ? VK_TRUE : VK_FALSE;
        depth.depthCompareOp = VK_COMPARE_OP_LESS_OR_EQUAL;
        VkPipelineColorBlendAttachmentState attachment{};
        attachment.colorWriteMask = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT
                | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT;
        VkPipelineColorBlendStateCreateInfo blend{};
        blend.sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO;
        blend.attachmentCount = 1;
        blend.pAttachments = &attachment;
        VkGraphicsPipelineCreateInfo info{};
        info.sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO;
        info.flags = flags;
        info.stageCount = static_cast<uint32_t>(stages.size());
        info.pStages = stages.data();
        info.pVertexInputState = &vertexInput;
        info.pInputAssemblyState = &assembly;
        info.pViewportState = &viewportState;
        info.pRasterizationState = &raster;
        info.pMultisampleState = &multisample;
        info.pDepthStencilState = &depth;
        info.pColorBlendState = &blend;
        info.layout = layout;
        info.renderPass = renderPass;
        VkPipeline pipeline = VK_NULL_HANDLE;
        VkResult result = context.vkCreateGraphicsPipelines(
                context.device, cache, 1, &info, nullptr, &pipeline);
        if (result != VK_SUCCESS) throw VulkanFailure("vkCreateGraphicsPipelines", result);
        return pipeline;
    }

    VkDescriptorSetLayout createComputeSetLayout() {
        VkDescriptorSetLayoutBinding binding{};
        binding.binding = 0;
        binding.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        binding.descriptorCount = 1;
        binding.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
        VkDescriptorSetLayoutCreateInfo info{};
        info.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
        info.bindingCount = 1;
        info.pBindings = &binding;
        VkDescriptorSetLayout layout = VK_NULL_HANDLE;
        check(context.vkCreateDescriptorSetLayout(context.device, &info, nullptr, &layout),
              "vkCreateDescriptorSetLayout");
        return layout;
    }

    VkPipelineLayout createComputeLayout(VkDescriptorSetLayout setLayout) {
        VkPipelineLayoutCreateInfo info{};
        info.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
        info.setLayoutCount = 1;
        info.pSetLayouts = &setLayout;
        VkPipelineLayout layout = VK_NULL_HANDLE;
        check(context.vkCreatePipelineLayout(context.device, &info, nullptr, &layout),
              "vkCreatePipelineLayout(compute)");
        return layout;
    }

    VkPipeline createComputePipeline(VkPipelineCache cache, VkPipelineLayout layout,
                                     uint32_t iterations, uint32_t variant,
                                     VkPipelineCreateFlags flags = 0) {
        std::array<VkSpecializationMapEntry, 2> entries{};
        entries[0].constantID = 0;
        entries[0].offset = 0;
        entries[0].size = sizeof(uint32_t);
        entries[1].constantID = 1;
        entries[1].offset = sizeof(uint32_t);
        entries[1].size = sizeof(uint32_t);
        std::array<uint32_t, 2> data{iterations, variant};
        VkSpecializationInfo specialization{};
        specialization.mapEntryCount = static_cast<uint32_t>(entries.size());
        specialization.pMapEntries = entries.data();
        specialization.dataSize = sizeof(data);
        specialization.pData = data.data();
        VkPipelineShaderStageCreateInfo stage{};
        stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
        stage.module = computeShader;
        stage.pName = "main";
        stage.pSpecializationInfo = &specialization;
        VkComputePipelineCreateInfo info{};
        info.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
        info.flags = flags;
        info.stage = stage;
        info.layout = layout;
        VkPipeline pipeline = VK_NULL_HANDLE;
        VkResult result = context.vkCreateComputePipelines(
                context.device, cache, 1, &info, nullptr, &pipeline);
        if (result != VK_SUCCESS) throw VulkanFailure("vkCreateComputePipelines", result);
        return pipeline;
    }

    VkPipelineCache createPipelineCache(const std::vector<uint8_t> &data = {}) {
        VkPipelineCacheCreateInfo info{};
        info.sType = VK_STRUCTURE_TYPE_PIPELINE_CACHE_CREATE_INFO;
        info.initialDataSize = data.size();
        info.pInitialData = data.empty() ? nullptr : data.data();
        VkPipelineCache cache = VK_NULL_HANDLE;
        check(context.vkCreatePipelineCache(context.device, &info, nullptr, &cache),
              "vkCreatePipelineCache");
        return cache;
    }

    std::vector<uint8_t> serializeCache(VkPipelineCache cache) {
        size_t size = 0;
        check(context.vkGetPipelineCacheData(context.device, cache, &size, nullptr),
              "vkGetPipelineCacheData(size)");
        std::vector<uint8_t> data(size);
        if (size > 0) {
            check(context.vkGetPipelineCacheData(context.device, cache, &size, data.data()),
                  "vkGetPipelineCacheData(data)");
            data.resize(size);
        }
        return data;
    }

    VkPipeline createCompilePipeline(uint32_t index, VkPipelineCache cache,
                                     VkRenderPass renderPass, VkPipelineLayout drawLayout,
                                     VkPipelineLayout computeLayout,
                                     VkPipelineCreateFlags flags = 0) {
        if (index < kShaderPipelineCount / 2) {
            VkShaderModule fragment = (index & 1U) == 0 ? aluFragment : branchFragment;
            return createGraphicsPipeline(cache, renderPass, drawLayout, index, fragment,
                                          VK_SAMPLE_COUNT_1_BIT, false, flags);
        }
        return createComputePipeline(cache, computeLayout, 64U + (index % 6U) * 32U,
                                     index, flags);
    }

    std::string runShaderCompile() {
        context.setStage("shader_compile_setup");
        VkRenderPass renderPass = createSimpleRenderPass(VK_FORMAT_R8G8B8A8_UNORM);
        VkPipelineLayout drawLayout = createDrawLayout();
        VkDescriptorSetLayout computeSetLayout = createComputeSetLayout();
        VkPipelineLayout computeLayout = createComputeLayout(computeSetLayout);
        VkPipelineCache coldCache = VK_NULL_HANDLE;
        VkPipelineCache warmCache = VK_NULL_HANDLE;
        VkPipelineCache probeCache = VK_NULL_HANDLE;
        std::vector<double> coldTimes;
        std::vector<double> warmTimes;
        std::vector<uint8_t> cacheData;
        int cacheHits = 0;
        int cacheMisses = 0;
        bool hitRateAvailable = false;
        try {
            context.setStage("shader_compile_cold");
            coldCache = createPipelineCache();
            for (uint32_t index = 0; index < kShaderPipelineCount; ++index) {
                auto start = Clock::now();
                VkPipeline pipeline = createCompilePipeline(index, coldCache, renderPass,
                                                            drawLayout, computeLayout);
                coldTimes.push_back(elapsedMs(start));
                context.vkDestroyPipeline(context.device, pipeline, nullptr);
            }
            cacheData = serializeCache(coldCache);
            context.vkDestroyPipelineCache(context.device, coldCache, nullptr);
            coldCache = VK_NULL_HANDLE;

            context.setStage("shader_compile_warm");
            warmCache = createPipelineCache(cacheData);
            for (uint32_t index = 0; index < kShaderPipelineCount; ++index) {
                auto start = Clock::now();
                VkPipeline pipeline = createCompilePipeline(index, warmCache, renderPass,
                                                            drawLayout, computeLayout);
                warmTimes.push_back(elapsedMs(start));
                context.vkDestroyPipeline(context.device, pipeline, nullptr);
            }

#if defined(VK_EXT_pipeline_creation_cache_control)
            if (context.cacheControlEnabled) {
                context.setStage("shader_compile_cache_probe");
                hitRateAvailable = true;
                probeCache = createPipelineCache(cacheData);
                for (uint32_t index = 0; index < kShaderPipelineCount; ++index) {
                    VkPipeline pipeline = VK_NULL_HANDLE;
                    try {
                        pipeline = createCompilePipeline(
                                index, probeCache, renderPass, drawLayout, computeLayout,
                                VK_PIPELINE_CREATE_FAIL_ON_PIPELINE_COMPILE_REQUIRED_BIT_EXT);
                        ++cacheHits;
                    } catch (const VulkanFailure &failure) {
                        if (failure.result == VK_PIPELINE_COMPILE_REQUIRED_EXT) ++cacheMisses;
                        else throw;
                    }
                    if (pipeline != VK_NULL_HANDLE) {
                        context.vkDestroyPipeline(context.device, pipeline, nullptr);
                    }
                }
            }
#endif
        } catch (...) {
            if (probeCache != VK_NULL_HANDLE) context.vkDestroyPipelineCache(context.device, probeCache, nullptr);
            if (warmCache != VK_NULL_HANDLE) context.vkDestroyPipelineCache(context.device, warmCache, nullptr);
            if (coldCache != VK_NULL_HANDLE) context.vkDestroyPipelineCache(context.device, coldCache, nullptr);
            context.vkDestroyPipelineLayout(context.device, computeLayout, nullptr);
            context.vkDestroyDescriptorSetLayout(context.device, computeSetLayout, nullptr);
            context.vkDestroyPipelineLayout(context.device, drawLayout, nullptr);
            context.vkDestroyRenderPass(context.device, renderPass, nullptr);
            throw;
        }
        if (probeCache != VK_NULL_HANDLE) context.vkDestroyPipelineCache(context.device, probeCache, nullptr);
        if (warmCache != VK_NULL_HANDLE) context.vkDestroyPipelineCache(context.device, warmCache, nullptr);
        context.vkDestroyPipelineLayout(context.device, computeLayout, nullptr);
        context.vkDestroyDescriptorSetLayout(context.device, computeSetLayout, nullptr);
        context.vkDestroyPipelineLayout(context.device, drawLayout, nullptr);
        context.vkDestroyRenderPass(context.device, renderPass, nullptr);

        const double coldTotal = std::accumulate(coldTimes.begin(), coldTimes.end(), 0.0);
        const double warmTotal = std::accumulate(warmTimes.begin(), warmTimes.end(), 0.0);
        std::ostringstream json;
        json << "{\"success\":true"
             << ",\"workload_id\":\"" << kShaderCompileId << "\""
             << ",\"workload_version\":" << kWorkloadVersion
             << ",\"custom_driver\":" << (context.isCustomDriver() ? "true" : "false")
             << ",\"pipeline_count\":" << kShaderPipelineCount
             << ",\"graphics_pipeline_count\":" << kShaderPipelineCount / 2
             << ",\"compute_pipeline_count\":" << kShaderPipelineCount / 2
             << ",\"cold_definition\":\"empty application VkPipelineCache in a fresh runner process\""
             << ",\"cold_total_ms\":" << coldTotal
             << ",\"cold_p50_pipeline_ms\":" << percentile(coldTimes, 0.50)
             << ",\"cold_p95_pipeline_ms\":" << percentile(coldTimes, 0.95)
             << ",\"warm_total_ms\":" << warmTotal
             << ",\"warm_p50_pipeline_ms\":" << percentile(warmTimes, 0.50)
             << ",\"warm_p95_pipeline_ms\":" << percentile(warmTimes, 0.95)
             << ",\"warm_speedup_percent\":"
             << (coldTotal > 0.0 ? (coldTotal / warmTotal - 1.0) * 100.0 : 0.0)
             << ",\"cache_serialized_bytes\":" << cacheData.size()
             << ",\"cache_hit_rate_available\":" << (hitRateAvailable ? "true" : "false")
             << ",\"cache_hit_rate_method\":\""
             << (hitRateAvailable ? "VK_EXT_pipeline_creation_cache_control probe"
                                  : "unavailable: extension/feature not exposed") << "\""
             << ",\"cache_hit_count\":" << cacheHits
             << ",\"cache_miss_count\":" << cacheMisses
             << ",\"cache_hit_rate_percent\":";
        if (hitRateAvailable && cacheHits + cacheMisses > 0) {
            json << static_cast<double>(cacheHits) * 100.0 /
                    static_cast<double>(cacheHits + cacheMisses);
        } else {
            json << "null";
        }
        json << ",\"cold_pipeline_times_ms\":";
        appendDoubleArray(json, coldTimes);
        json << ",\"warm_pipeline_times_ms\":";
        appendDoubleArray(json, warmTimes);
        json << ",\"gpu_timestamps_used\":false"
             << ",\"timing_clock\":\"steady_clock host pipeline creation latency\""
             << ",\"capabilities\":" << context.capabilities
             << ",\"metric_note\":\"Fixed SPIR-V pipeline creation only; it does not reproduce every game shader, driver cache, or stutter source.\""
             << '}';
        return json.str();
    }

    RenderSetup createRenderSetup(VkSampleCountFlagBits samples, bool depthEnabled,
                                  uint32_t drawCount, VkShaderModule fragment) {
        RenderSetup setup;
        const VkFormat colorFormat = VK_FORMAT_R8G8B8A8_UNORM;
        setup.color = context.createImage(
                kRenderWidth, kRenderHeight, colorFormat, samples,
                VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT, VK_IMAGE_ASPECT_COLOR_BIT);
        VkFormat depthFormat = VK_FORMAT_UNDEFINED;
        if (depthEnabled) {
            depthFormat = context.depthFormat();
            VkImageAspectFlags aspect = VK_IMAGE_ASPECT_DEPTH_BIT;
            if (depthFormat == VK_FORMAT_D24_UNORM_S8_UINT) aspect |= VK_IMAGE_ASPECT_STENCIL_BIT;
            setup.depth = context.createImage(
                    kRenderWidth, kRenderHeight, depthFormat, samples,
                    VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT, aspect);
        }

        std::array<VkAttachmentDescription, 2> attachments{};
        attachments[0].format = colorFormat;
        attachments[0].samples = samples;
        attachments[0].loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
        attachments[0].storeOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
        attachments[0].initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        attachments[0].finalLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
        attachments[1].format = depthFormat;
        attachments[1].samples = samples;
        attachments[1].loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
        attachments[1].storeOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
        attachments[1].stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
        attachments[1].stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
        attachments[1].initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        attachments[1].finalLayout = VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL;
        VkAttachmentReference colorReference{};
        colorReference.attachment = 0;
        colorReference.layout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
        VkAttachmentReference depthReference{};
        depthReference.attachment = 1;
        depthReference.layout = VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL;
        VkSubpassDescription subpass{};
        subpass.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
        subpass.colorAttachmentCount = 1;
        subpass.pColorAttachments = &colorReference;
        subpass.pDepthStencilAttachment = depthEnabled ? &depthReference : nullptr;
        VkRenderPassCreateInfo renderPassInfo{};
        renderPassInfo.sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO;
        renderPassInfo.attachmentCount = depthEnabled ? 2U : 1U;
        renderPassInfo.pAttachments = attachments.data();
        renderPassInfo.subpassCount = 1;
        renderPassInfo.pSubpasses = &subpass;
        check(context.vkCreateRenderPass(context.device, &renderPassInfo, nullptr,
                                         &setup.renderPass), "vkCreateRenderPass(phase2)");

        std::array<VkImageView, 2> views{setup.color.view, setup.depth.view};
        VkFramebufferCreateInfo framebufferInfo{};
        framebufferInfo.sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
        framebufferInfo.renderPass = setup.renderPass;
        framebufferInfo.attachmentCount = depthEnabled ? 2U : 1U;
        framebufferInfo.pAttachments = views.data();
        framebufferInfo.width = kRenderWidth;
        framebufferInfo.height = kRenderHeight;
        framebufferInfo.layers = 1;
        check(context.vkCreateFramebuffer(context.device, &framebufferInfo, nullptr,
                                          &setup.framebuffer), "vkCreateFramebuffer(phase2)");
        setup.layout = createDrawLayout();
        setup.pipeline = createGraphicsPipeline(VK_NULL_HANDLE, setup.renderPass, setup.layout,
                                                depthEnabled ? 7U : 3U, fragment, samples,
                                                depthEnabled);
        setup.commandPool = context.createCommandPool();
        setup.command = context.allocateCommandBuffer(setup.commandPool);
        setup.query = context.createTimestampQuery();
        context.beginTimedCommand(setup.command, setup.query);
        std::array<VkClearValue, 2> clears{};
        clears[0].color.float32[0] = 0.02F;
        clears[0].color.float32[1] = 0.03F;
        clears[0].color.float32[2] = 0.05F;
        clears[0].color.float32[3] = 1.0F;
        clears[1].depthStencil = {1.0F, 0};
        VkRenderPassBeginInfo begin{};
        begin.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
        begin.renderPass = setup.renderPass;
        begin.framebuffer = setup.framebuffer;
        begin.renderArea.extent = {kRenderWidth, kRenderHeight};
        begin.clearValueCount = depthEnabled ? 2U : 1U;
        begin.pClearValues = clears.data();
        context.vkCmdBeginRenderPass(setup.command, &begin, VK_SUBPASS_CONTENTS_INLINE);
        context.vkCmdBindPipeline(setup.command, VK_PIPELINE_BIND_POINT_GRAPHICS, setup.pipeline);
        const uint32_t columns = 32;
        const uint32_t rows = std::max(1U, (drawCount + columns - 1U) / columns);
        for (uint32_t index = 0; index < drawCount; ++index) {
            const uint32_t column = index % columns;
            const uint32_t row = index / columns;
            DrawPush push{};
            push.transform[0] = 1.7F / static_cast<float>(columns);
            push.transform[1] = 1.7F / static_cast<float>(rows);
            push.transform[2] = -0.85F + (static_cast<float>(column) + 0.5F) * 1.7F /
                    static_cast<float>(columns);
            push.transform[3] = -0.85F + (static_cast<float>(row) + 0.5F) * 1.7F /
                    static_cast<float>(rows);
            push.color[0] = static_cast<float>((index * 17U) & 255U) / 255.0F;
            push.color[1] = static_cast<float>((index * 29U) & 255U) / 255.0F;
            push.color[2] = static_cast<float>((index * 43U) & 255U) / 255.0F;
            push.color[3] = 1.0F;
            context.vkCmdPushConstants(setup.command, setup.layout,
                                       VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT,
                                       0, sizeof(push), &push);
            context.vkCmdDraw(setup.command, 3, 1, 0, 0);
        }
        context.vkCmdEndRenderPass(setup.command);
        context.endTimedCommand(setup.command, setup.query);
        return setup;
    }

    void destroyRenderSetup(RenderSetup &setup) {
        if (setup.commandPool != VK_NULL_HANDLE) {
            context.vkDestroyCommandPool(context.device, setup.commandPool, nullptr);
        }
        context.destroyTimestampQuery(setup.query);
        if (setup.pipeline != VK_NULL_HANDLE) context.vkDestroyPipeline(context.device, setup.pipeline, nullptr);
        if (setup.layout != VK_NULL_HANDLE) context.vkDestroyPipelineLayout(context.device, setup.layout, nullptr);
        if (setup.framebuffer != VK_NULL_HANDLE) context.vkDestroyFramebuffer(context.device, setup.framebuffer, nullptr);
        if (setup.renderPass != VK_NULL_HANDLE) context.vkDestroyRenderPass(context.device, setup.renderPass, nullptr);
        context.destroyImage(setup.depth);
        context.destroyImage(setup.color);
        setup = {};
    }

    std::vector<double> sampleRender(RenderSetup &setup, int warmupSeconds, int measureSeconds) {
        auto warmupStart = Clock::now();
        do {
            context.submitTimed(setup.command, setup.query);
        } while (elapsedMs(warmupStart) < static_cast<double>(warmupSeconds) * 1000.0);
        std::vector<double> samples;
        auto measureStart = Clock::now();
        do {
            samples.push_back(context.submitTimed(setup.command, setup.query));
        } while ((elapsedMs(measureStart) < static_cast<double>(measureSeconds) * 1000.0
                  || samples.size() < 8U) && samples.size() < 600U);
        return samples;
    }

    std::string runRenderPassTiling(int warmupSeconds, int measureSeconds) {
        context.setStage("renderpass_tiling_setup");
        struct Variant {
            VkSampleCountFlagBits samples;
            bool depth;
            const char *name;
        };
        std::vector<Variant> variants{{VK_SAMPLE_COUNT_1_BIT, false, "msaa1_depth_off"},
                                      {VK_SAMPLE_COUNT_1_BIT, true, "msaa1_depth_on"}};
        const VkSampleCountFlags supported = context.properties.limits.framebufferColorSampleCounts
                & context.properties.limits.framebufferDepthSampleCounts;
        if ((supported & VK_SAMPLE_COUNT_4_BIT) != 0) {
            variants.push_back({VK_SAMPLE_COUNT_4_BIT, false, "msaa4_depth_off"});
            variants.push_back({VK_SAMPLE_COUNT_4_BIT, true, "msaa4_depth_on"});
        }
        std::vector<double> aggregate;
        std::ostringstream variantJson;
        variantJson << '[';
        for (size_t index = 0; index < variants.size(); ++index) {
            context.setStage(std::string("renderpass_tiling_") + variants[index].name);
            RenderSetup setup = createRenderSetup(variants[index].samples, variants[index].depth,
                                                  kTilingDrawCount, aluFragment);
            std::vector<double> samples;
            try {
                samples = sampleRender(setup,
                                       std::max(0, warmupSeconds / static_cast<int>(variants.size())),
                                       std::max(1, measureSeconds / static_cast<int>(variants.size())));
            } catch (...) {
                destroyRenderSetup(setup);
                throw;
            }
            const bool timestamps = setup.query.supported;
            destroyRenderSetup(setup);
            aggregate.insert(aggregate.end(), samples.begin(), samples.end());
            if (index > 0) variantJson << ',';
            variantJson << "{\"name\":\"" << variants[index].name << "\""
                        << ",\"sample_count\":" << samples.size()
                        << ",\"median_frame_ms\":" << median(samples)
                        << ",\"p95_frame_ms\":" << percentile(samples, 0.95)
                        << ",\"p99_frame_ms\":" << percentile(samples, 0.99)
                        << ",\"gpu_timestamps_used\":" << (timestamps ? "true" : "false")
                        << ",\"frame_times_ms\":";
            appendDoubleArray(variantJson, samples);
            variantJson << '}';
        }
        variantJson << ']';
        std::ostringstream json;
        json << "{\"success\":true"
             << ",\"workload_id\":\"" << kRenderPassTilingId << "\""
             << ",\"workload_version\":" << kWorkloadVersion
             << ",\"custom_driver\":" << (context.isCustomDriver() ? "true" : "false")
             << ",\"width\":" << kRenderWidth
             << ",\"height\":" << kRenderHeight
             << ",\"draws_per_frame\":" << kTilingDrawCount
             << ",\"variant_count\":" << variants.size()
             << ",\"median_frame_ms\":" << median(aggregate)
             << ",\"p95_frame_ms\":" << percentile(aggregate, 0.95)
             << ",\"p99_frame_ms\":" << percentile(aggregate, 0.99)
             << ",\"variants\":" << variantJson.str()
             << ",\"lrz_control\":\"not exposed by standard Vulkan; depth on/off is an LRZ-sensitive proxy\""
             << ",\"gmem_control\":\"driver managed; no direct GMEM/sysmem force is claimed\""
             << ",\"performance_counters_available\":false"
             << ",\"gpu_timestamps_used\":"
             << (context.timestampsSupported() ? "true" : "false")
             << ",\"capabilities\":" << context.capabilities
             << ",\"metric_note\":\"Many small draws and MSAA/depth variants stress render-pass tiling paths; this does not prove internal LRZ or GMEM mode and does not predict game FPS.\""
             << '}';
        return json.str();
    }

    struct ComputeSetup {
        BufferResource buffer;
        VkDescriptorSetLayout setLayout = VK_NULL_HANDLE;
        VkDescriptorPool descriptorPool = VK_NULL_HANDLE;
        VkDescriptorSet descriptorSet = VK_NULL_HANDLE;
        VkPipelineLayout layout = VK_NULL_HANDLE;
        VkPipeline pipeline = VK_NULL_HANDLE;
        VkCommandPool commandPool = VK_NULL_HANDLE;
        VkCommandBuffer command = VK_NULL_HANDLE;
        TimestampQuery query;
    };

    ComputeSetup createComputeSetup() {
        ComputeSetup setup;
        const VkDeviceSize bytes = static_cast<VkDeviceSize>(kComputeElementCount) * sizeof(uint32_t);
        setup.buffer = context.createBuffer(bytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                                            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT,
                                            VK_MEMORY_PROPERTY_HOST_COHERENT_BIT |
                                            VK_MEMORY_PROPERTY_HOST_CACHED_BIT);
        void *mapped = nullptr;
        check(context.vkMapMemory(context.device, setup.buffer.memory, 0,
                                  setup.buffer.allocationSize, 0, &mapped),
              "vkMapMemory(compute_init)");
        auto *words = static_cast<uint32_t *>(mapped);
        for (uint32_t index = 0; index < kComputeElementCount; ++index) {
            words[index] = 0x3f000000U ^ (index * 2654435761U);
        }
        if ((setup.buffer.memoryFlags & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) == 0) {
            VkMappedMemoryRange range{};
            range.sType = VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE;
            range.memory = setup.buffer.memory;
            range.offset = 0;
            range.size = VK_WHOLE_SIZE;
            check(context.vkFlushMappedMemoryRanges(context.device, 1, &range),
                  "vkFlushMappedMemoryRanges(compute_init)");
        }
        context.vkUnmapMemory(context.device, setup.buffer.memory);

        setup.setLayout = createComputeSetLayout();
        setup.layout = createComputeLayout(setup.setLayout);
        setup.pipeline = createComputePipeline(VK_NULL_HANDLE, setup.layout,
                                               kComputeIterations, 0);
        VkDescriptorPoolSize poolSize{};
        poolSize.type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        poolSize.descriptorCount = 1;
        VkDescriptorPoolCreateInfo poolInfo{};
        poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
        poolInfo.maxSets = 1;
        poolInfo.poolSizeCount = 1;
        poolInfo.pPoolSizes = &poolSize;
        check(context.vkCreateDescriptorPool(context.device, &poolInfo, nullptr,
                                             &setup.descriptorPool), "vkCreateDescriptorPool");
        VkDescriptorSetAllocateInfo allocate{};
        allocate.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
        allocate.descriptorPool = setup.descriptorPool;
        allocate.descriptorSetCount = 1;
        allocate.pSetLayouts = &setup.setLayout;
        check(context.vkAllocateDescriptorSets(context.device, &allocate, &setup.descriptorSet),
              "vkAllocateDescriptorSets");
        VkDescriptorBufferInfo bufferInfo{};
        bufferInfo.buffer = setup.buffer.buffer;
        bufferInfo.offset = 0;
        bufferInfo.range = bytes;
        VkWriteDescriptorSet write{};
        write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        write.dstSet = setup.descriptorSet;
        write.dstBinding = 0;
        write.descriptorCount = 1;
        write.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        write.pBufferInfo = &bufferInfo;
        context.vkUpdateDescriptorSets(context.device, 1, &write, 0, nullptr);

        setup.commandPool = context.createCommandPool();
        setup.command = context.allocateCommandBuffer(setup.commandPool);
        setup.query = context.createTimestampQuery();
        context.beginTimedCommand(setup.command, setup.query);
        VkBufferMemoryBarrier before{};
        before.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        before.srcAccessMask = VK_ACCESS_HOST_WRITE_BIT;
        before.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
        before.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        before.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        before.buffer = setup.buffer.buffer;
        before.offset = 0;
        before.size = VK_WHOLE_SIZE;
        context.vkCmdPipelineBarrier(setup.command, VK_PIPELINE_STAGE_HOST_BIT,
                                     VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0,
                                     0, nullptr, 1, &before, 0, nullptr);
        context.vkCmdBindPipeline(setup.command, VK_PIPELINE_BIND_POINT_COMPUTE, setup.pipeline);
        context.vkCmdBindDescriptorSets(setup.command, VK_PIPELINE_BIND_POINT_COMPUTE,
                                        setup.layout, 0, 1, &setup.descriptorSet, 0, nullptr);
        for (uint32_t index = 0; index < kComputeDispatchesPerSample; ++index) {
            context.vkCmdDispatch(setup.command, kComputeElementCount / 256U, 1, 1);
        }
        VkBufferMemoryBarrier after{};
        after.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        after.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        after.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
        after.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        after.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        after.buffer = setup.buffer.buffer;
        after.offset = 0;
        after.size = VK_WHOLE_SIZE;
        context.vkCmdPipelineBarrier(setup.command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                                     VK_PIPELINE_STAGE_HOST_BIT, 0,
                                     0, nullptr, 1, &after, 0, nullptr);
        context.endTimedCommand(setup.command, setup.query);
        return setup;
    }

    void destroyComputeSetup(ComputeSetup &setup) {
        if (setup.commandPool != VK_NULL_HANDLE) context.vkDestroyCommandPool(context.device, setup.commandPool, nullptr);
        context.destroyTimestampQuery(setup.query);
        if (setup.pipeline != VK_NULL_HANDLE) context.vkDestroyPipeline(context.device, setup.pipeline, nullptr);
        if (setup.layout != VK_NULL_HANDLE) context.vkDestroyPipelineLayout(context.device, setup.layout, nullptr);
        if (setup.descriptorPool != VK_NULL_HANDLE) context.vkDestroyDescriptorPool(context.device, setup.descriptorPool, nullptr);
        if (setup.setLayout != VK_NULL_HANDLE) context.vkDestroyDescriptorSetLayout(context.device, setup.setLayout, nullptr);
        context.destroyBuffer(setup.buffer);
        setup = {};
    }

    uint64_t computeChecksum(ComputeSetup &setup) {
        void *mapped = nullptr;
        check(context.vkMapMemory(context.device, setup.buffer.memory, 0,
                                  setup.buffer.allocationSize, 0, &mapped),
              "vkMapMemory(compute_validate)");
        if ((setup.buffer.memoryFlags & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) == 0) {
            VkMappedMemoryRange range{};
            range.sType = VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE;
            range.memory = setup.buffer.memory;
            range.offset = 0;
            range.size = VK_WHOLE_SIZE;
            check(context.vkInvalidateMappedMemoryRanges(context.device, 1, &range),
                  "vkInvalidateMappedMemoryRanges(compute_validate)");
        }
        const auto *words = static_cast<const uint32_t *>(mapped);
        uint64_t checksum = 0;
        for (uint32_t index = 0; index < 256; ++index) {
            checksum = (checksum * 1099511628211ULL) ^ words[index * 4093U % kComputeElementCount];
        }
        context.vkUnmapMemory(context.device, setup.buffer.memory);
        return checksum;
    }

    double operationsPerSampleGiga() const {
        const double operations = static_cast<double>(kComputeElementCount)
                * static_cast<double>(kComputeIterations)
                * static_cast<double>(kComputeOperationsPerIteration)
                * static_cast<double>(kComputeDispatchesPerSample);
        return operations / 1.0e9;
    }

    std::string runCompute(int warmupSeconds, int measureSeconds, bool thermal) {
        context.setStage(thermal ? "thermal_compute_setup" : "compute_setup");
        ComputeSetup setup = createComputeSetup();
        const double operationGiga = operationsPerSampleGiga();
        try {
            auto warmStart = Clock::now();
            do {
                context.submitTimed(setup.command, setup.query);
            } while (elapsedMs(warmStart) < static_cast<double>(warmupSeconds) * 1000.0);

            if (!thermal) {
                context.setStage("compute_measure");
                std::vector<double> durations;
                std::vector<double> throughputs;
                auto measureStart = Clock::now();
                do {
                    double duration = context.submitTimed(setup.command, setup.query);
                    durations.push_back(duration);
                    throughputs.push_back(operationGiga / (duration / 1000.0));
                } while ((elapsedMs(measureStart) < static_cast<double>(measureSeconds) * 1000.0
                          || durations.size() < 8U) && durations.size() < 500U);
                const uint64_t checksum = computeChecksum(setup);
                const bool timestamps = setup.query.supported;
                destroyComputeSetup(setup);
                std::ostringstream json;
                json << "{\"success\":true"
                     << ",\"workload_id\":\"" << kComputeArithmeticId << "\""
                     << ",\"workload_version\":" << kWorkloadVersion
                     << ",\"custom_driver\":" << (context.isCustomDriver() ? "true" : "false")
                     << ",\"element_count\":" << kComputeElementCount
                     << ",\"iterations_per_dispatch\":" << kComputeIterations
                     << ",\"dispatches_per_sample\":" << kComputeDispatchesPerSample
                     << ",\"declared_operations_per_iteration\":"
                     << kComputeOperationsPerIteration
                     << ",\"operation_counting\":\"source-level floating-point operations; compiler fusion may differ\""
                     << ",\"sample_count\":" << durations.size()
                     << ",\"throughput_gops\":" << median(throughputs)
                     << ",\"p50_throughput_gops\":" << percentile(throughputs, 0.50)
                     << ",\"p95_throughput_gops\":" << percentile(throughputs, 0.95)
                     << ",\"median_dispatch_ms\":" << median(durations)
                     << ",\"validation_checksum\":" << checksum
                     << ",\"gpu_timestamps_used\":" << (timestamps ? "true" : "false")
                     << ",\"sample_durations_ms\":";
                appendDoubleArray(json, durations);
                json << ",\"sample_throughputs_gops\":";
                appendDoubleArray(json, throughputs);
                json << ",\"capabilities\":" << context.capabilities
                     << ",\"metric_note\":\"Fixed compute arithmetic only; it is separate from transfer and does not represent general GPU or game performance.\""
                     << '}';
                return json.str();
            }

            context.setStage("thermal_sustain_measure");
            struct Window {
                double startSeconds;
                double endSeconds;
                double throughput;
                double operationsGiga;
                size_t sampleCount;
            };
            std::vector<Window> windows;
            std::vector<double> windowThroughputs;
            auto testStart = Clock::now();
            double totalOperationsGiga = 0.0;
            while (elapsedMs(testStart) < static_cast<double>(measureSeconds) * 1000.0) {
                const double windowStart = elapsedMs(testStart) / 1000.0;
                auto windowClock = Clock::now();
                double windowOperations = 0.0;
                size_t count = 0;
                do {
                    context.submitTimed(setup.command, setup.query);
                    windowOperations += operationGiga;
                    ++count;
                } while (elapsedMs(windowClock) < static_cast<double>(kThermalWindowSeconds) * 1000.0
                         && elapsedMs(testStart) < static_cast<double>(measureSeconds) * 1000.0);
                const double windowElapsed = elapsedMs(windowClock) / 1000.0;
                const double throughput = windowElapsed > 0.0 ? windowOperations / windowElapsed : 0.0;
                const double windowEnd = elapsedMs(testStart) / 1000.0;
                windows.push_back({windowStart, windowEnd, throughput, windowOperations, count});
                windowThroughputs.push_back(throughput);
                totalOperationsGiga += windowOperations;
            }
            const uint64_t checksum = computeChecksum(setup);
            const bool timestamps = setup.query.supported;
            destroyComputeSetup(setup);
            const size_t edge = std::max<size_t>(1, windows.size() / 5);
            std::vector<double> first(windowThroughputs.begin(), windowThroughputs.begin() + edge);
            std::vector<double> last(windowThroughputs.end() - edge, windowThroughputs.end());
            const double firstMean = mean(first);
            const double lastMean = mean(last);
            const double retention = firstMean > 0.0 ? lastMean / firstMean * 100.0 : 0.0;
            double sumX = 0.0;
            double sumY = 0.0;
            double sumXY = 0.0;
            double sumXX = 0.0;
            for (const Window &window : windows) {
                const double x = (window.startSeconds + window.endSeconds) * 0.5 / 60.0;
                sumX += x;
                sumY += window.throughput;
                sumXY += x * window.throughput;
                sumXX += x * x;
            }
            const double count = static_cast<double>(windows.size());
            const double denominator = count * sumXX - sumX * sumX;
            const double slope = denominator == 0.0 ? 0.0
                    : (count * sumXY - sumX * sumY) / denominator;
            std::ostringstream json;
            json << "{\"success\":true"
                 << ",\"workload_id\":\"" << kThermalSustainId << "\""
                 << ",\"workload_version\":" << kWorkloadVersion
                 << ",\"custom_driver\":" << (context.isCustomDriver() ? "true" : "false")
                 << ",\"duration_seconds\":" << measureSeconds
                 << ",\"window_seconds\":" << kThermalWindowSeconds
                 << ",\"window_count\":" << windows.size()
                 << ",\"sustained_throughput_gops\":" << median(last)
                 << ",\"initial_throughput_gops\":" << firstMean
                 << ",\"final_throughput_gops\":" << lastMean
                 << ",\"throughput_retention_percent\":" << retention
                 << ",\"throughput_slope_gops_per_minute\":" << slope
                 << ",\"total_operations_giga\":" << totalOperationsGiga
                 << ",\"validation_checksum\":" << checksum
                 << ",\"gpu_timestamps_used\":" << (timestamps ? "true" : "false")
                 << ",\"windows\":[";
            for (size_t index = 0; index < windows.size(); ++index) {
                if (index > 0) json << ',';
                json << "{\"start_seconds\":" << windows[index].startSeconds
                     << ",\"end_seconds\":" << windows[index].endSeconds
                     << ",\"throughput_gops\":" << windows[index].throughput
                     << ",\"operations_giga\":" << windows[index].operationsGiga
                     << ",\"sample_count\":" << windows[index].sampleCount
                     << '}';
            }
            json << ']'
                 << ",\"capabilities\":" << context.capabilities
                 << ",\"metric_note\":\"Sustained fixed compute load and whole-device energy only; sensors may be unavailable and the result does not represent games or battery life.\""
                 << '}';
            return json.str();
        } catch (...) {
            destroyComputeSetup(setup);
            throw;
        }
    }

    struct TraceSetup {
        BufferResource seed;
        BufferResource working;
        BufferResource readback;
        VkDescriptorSetLayout setLayout = VK_NULL_HANDLE;
        VkDescriptorPool descriptorPool = VK_NULL_HANDLE;
        VkDescriptorSet descriptorSet = VK_NULL_HANDLE;
        VkPipelineLayout computeLayout = VK_NULL_HANDLE;
        VkPipeline computePipeline = VK_NULL_HANDLE;
        ImageResource color;
        VkRenderPass renderPass = VK_NULL_HANDLE;
        VkFramebuffer framebuffer = VK_NULL_HANDLE;
        VkPipelineLayout graphicsLayout = VK_NULL_HANDLE;
        VkPipeline graphicsPipeline = VK_NULL_HANDLE;
        VkCommandPool commandPool = VK_NULL_HANDLE;
        VkCommandBuffer command = VK_NULL_HANDLE;
        TimestampQuery query;
        VkDeviceSize graphicsBytes = 0;
        VkDeviceSize computeBytes = 0;
        VkDeviceSize totalBytes = 0;
        uint32_t drawCount = 0;
        uint32_t dispatchCount = 0;
        bool graphics = false;
    };

    VkRenderPass createTraceRenderPass() {
        VkAttachmentDescription attachment{};
        attachment.format = VK_FORMAT_R8G8B8A8_UNORM;
        attachment.samples = VK_SAMPLE_COUNT_1_BIT;
        attachment.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
        attachment.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
        attachment.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
        attachment.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
        attachment.initialLayout = VK_IMAGE_LAYOUT_GENERAL;
        attachment.finalLayout = VK_IMAGE_LAYOUT_GENERAL;
        VkAttachmentReference reference{};
        reference.attachment = 0;
        reference.layout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
        VkSubpassDescription subpass{};
        subpass.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
        subpass.colorAttachmentCount = 1;
        subpass.pColorAttachments = &reference;
        VkSubpassDependency dependencies[2]{};
        dependencies[0].srcSubpass = VK_SUBPASS_EXTERNAL;
        dependencies[0].dstSubpass = 0;
        dependencies[0].srcStageMask = VK_PIPELINE_STAGE_TRANSFER_BIT;
        dependencies[0].dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        dependencies[0].srcAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
        dependencies[0].dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
        dependencies[1].srcSubpass = 0;
        dependencies[1].dstSubpass = VK_SUBPASS_EXTERNAL;
        dependencies[1].srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        dependencies[1].dstStageMask = VK_PIPELINE_STAGE_TRANSFER_BIT;
        dependencies[1].srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
        dependencies[1].dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
        VkRenderPassCreateInfo info{};
        info.sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO;
        info.attachmentCount = 1;
        info.pAttachments = &attachment;
        info.subpassCount = 1;
        info.pSubpasses = &subpass;
        info.dependencyCount = 2;
        info.pDependencies = dependencies;
        VkRenderPass renderPass = VK_NULL_HANDLE;
        check(context.vkCreateRenderPass(context.device, &info, nullptr, &renderPass),
              "vkCreateRenderPass(trace)");
        return renderPass;
    }

    VkPipeline createTraceGraphicsPipeline(VkRenderPass renderPass, VkPipelineLayout layout) {
        std::array<VkPipelineShaderStageCreateInfo, 2> stages{};
        stages[0].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        stages[0].stage = VK_SHADER_STAGE_VERTEX_BIT;
        stages[0].module = drawVertex;
        stages[0].pName = "main";
        stages[1].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        stages[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT;
        stages[1].module = traceFragment;
        stages[1].pName = "main";
        VkPipelineVertexInputStateCreateInfo vertexInput{};
        vertexInput.sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;
        VkPipelineInputAssemblyStateCreateInfo assembly{};
        assembly.sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO;
        assembly.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
        VkViewport viewport{};
        viewport.width = static_cast<float>(kTraceWidth);
        viewport.height = static_cast<float>(kTraceHeight);
        viewport.maxDepth = 1.0F;
        VkRect2D scissor{{0, 0}, {kTraceWidth, kTraceHeight}};
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
        VkPipelineColorBlendAttachmentState attachment{};
        attachment.colorWriteMask = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT
                | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT;
        VkPipelineColorBlendStateCreateInfo blend{};
        blend.sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO;
        blend.attachmentCount = 1;
        blend.pAttachments = &attachment;
        VkGraphicsPipelineCreateInfo info{};
        info.sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO;
        info.stageCount = static_cast<uint32_t>(stages.size());
        info.pStages = stages.data();
        info.pVertexInputState = &vertexInput;
        info.pInputAssemblyState = &assembly;
        info.pViewportState = &viewportState;
        info.pRasterizationState = &raster;
        info.pMultisampleState = &multisample;
        info.pColorBlendState = &blend;
        info.layout = layout;
        info.renderPass = renderPass;
        VkPipeline pipeline = VK_NULL_HANDLE;
        check(context.vkCreateGraphicsPipelines(context.device, VK_NULL_HANDLE, 1, &info,
                                                nullptr, &pipeline),
              "vkCreateGraphicsPipelines(trace)");
        return pipeline;
    }

    VkPipeline createTraceComputePipeline(VkPipelineLayout layout, uint32_t variant) {
        struct Specialization { uint32_t steps; uint32_t variant; } values{16U, variant};
        std::array<VkSpecializationMapEntry, 2> entries{};
        entries[0] = {0, offsetof(Specialization, steps), sizeof(uint32_t)};
        entries[1] = {1, offsetof(Specialization, variant), sizeof(uint32_t)};
        VkSpecializationInfo specialization{};
        specialization.mapEntryCount = static_cast<uint32_t>(entries.size());
        specialization.pMapEntries = entries.data();
        specialization.dataSize = sizeof(values);
        specialization.pData = &values;
        VkPipelineShaderStageCreateInfo stage{};
        stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
        stage.module = traceCompute;
        stage.pName = "main";
        stage.pSpecializationInfo = &specialization;
        VkComputePipelineCreateInfo info{};
        info.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
        info.stage = stage;
        info.layout = layout;
        VkPipeline pipeline = VK_NULL_HANDLE;
        check(context.vkCreateComputePipelines(context.device, VK_NULL_HANDLE, 1, &info,
                                               nullptr, &pipeline),
              "vkCreateComputePipelines(trace)");
        return pipeline;
    }

    TraceSetup createTraceSetup(const std::string &traceId) {
        TraceSetup setup;
        setup.graphics = traceId == kMixedTraceId;
        setup.drawCount = setup.graphics ? 64U : 0U;
        setup.dispatchCount = setup.graphics ? 4U : 12U;
        setup.graphicsBytes = setup.graphics
                ? static_cast<VkDeviceSize>(kTraceWidth) * kTraceHeight * 4U : 0U;
        setup.computeBytes = static_cast<VkDeviceSize>(kTraceComputeWords) * sizeof(uint32_t);
        setup.totalBytes = setup.graphicsBytes + setup.computeBytes;
        setup.seed = context.createBuffer(setup.computeBytes, VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                                          VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT,
                                          VK_MEMORY_PROPERTY_HOST_COHERENT_BIT |
                                          VK_MEMORY_PROPERTY_HOST_CACHED_BIT);
        setup.working = context.createBuffer(setup.computeBytes,
                VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT
                        | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                0, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        setup.readback = context.createBuffer(setup.totalBytes, VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                                              VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT,
                                              VK_MEMORY_PROPERTY_HOST_COHERENT_BIT |
                                              VK_MEMORY_PROPERTY_HOST_CACHED_BIT);
        void *mapped = nullptr;
        check(context.vkMapMemory(context.device, setup.seed.memory, 0,
                                  setup.seed.allocationSize, 0, &mapped),
              "vkMapMemory(trace_seed)");
        auto *words = static_cast<uint32_t *>(mapped);
        for (uint32_t index = 0; index < kTraceComputeWords; ++index) {
            words[index] = 0x6d2b79f5U ^ (index * 0x9e3779b9U);
        }
        if ((setup.seed.memoryFlags & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) == 0) {
            VkMappedMemoryRange range{};
            range.sType = VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE;
            range.memory = setup.seed.memory;
            range.offset = 0;
            range.size = VK_WHOLE_SIZE;
            check(context.vkFlushMappedMemoryRanges(context.device, 1, &range),
                  "vkFlushMappedMemoryRanges(trace_seed)");
        }
        context.vkUnmapMemory(context.device, setup.seed.memory);

        setup.setLayout = createComputeSetLayout();
        setup.computeLayout = createComputeLayout(setup.setLayout);
        setup.computePipeline = createTraceComputePipeline(
                setup.computeLayout, setup.graphics ? 1U : 2U);
        VkDescriptorPoolSize poolSize{};
        poolSize.type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        poolSize.descriptorCount = 1;
        VkDescriptorPoolCreateInfo poolInfo{};
        poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
        poolInfo.maxSets = 1;
        poolInfo.poolSizeCount = 1;
        poolInfo.pPoolSizes = &poolSize;
        check(context.vkCreateDescriptorPool(context.device, &poolInfo, nullptr,
                                             &setup.descriptorPool),
              "vkCreateDescriptorPool(trace)");
        VkDescriptorSetAllocateInfo allocate{};
        allocate.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
        allocate.descriptorPool = setup.descriptorPool;
        allocate.descriptorSetCount = 1;
        allocate.pSetLayouts = &setup.setLayout;
        check(context.vkAllocateDescriptorSets(context.device, &allocate, &setup.descriptorSet),
              "vkAllocateDescriptorSets(trace)");
        VkDescriptorBufferInfo bufferInfo{};
        bufferInfo.buffer = setup.working.buffer;
        bufferInfo.offset = 0;
        bufferInfo.range = setup.computeBytes;
        VkWriteDescriptorSet write{};
        write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        write.dstSet = setup.descriptorSet;
        write.dstBinding = 0;
        write.descriptorCount = 1;
        write.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        write.pBufferInfo = &bufferInfo;
        context.vkUpdateDescriptorSets(context.device, 1, &write, 0, nullptr);

        if (setup.graphics) {
            setup.color = context.createImage(kTraceWidth, kTraceHeight,
                    VK_FORMAT_R8G8B8A8_UNORM, VK_SAMPLE_COUNT_1_BIT,
                    VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT,
                    VK_IMAGE_ASPECT_COLOR_BIT);
            setup.renderPass = createTraceRenderPass();
            VkFramebufferCreateInfo framebuffer{};
            framebuffer.sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
            framebuffer.renderPass = setup.renderPass;
            framebuffer.attachmentCount = 1;
            framebuffer.pAttachments = &setup.color.view;
            framebuffer.width = kTraceWidth;
            framebuffer.height = kTraceHeight;
            framebuffer.layers = 1;
            check(context.vkCreateFramebuffer(context.device, &framebuffer, nullptr,
                                              &setup.framebuffer),
                  "vkCreateFramebuffer(trace)");
            setup.graphicsLayout = createDrawLayout();
            setup.graphicsPipeline = createTraceGraphicsPipeline(
                    setup.renderPass, setup.graphicsLayout);
        }

        setup.commandPool = context.createCommandPool();
        if (setup.graphics) {
            VkCommandBuffer initialize = context.allocateCommandBuffer(setup.commandPool);
            TimestampQuery noQuery{};
            context.beginTimedCommand(initialize, noQuery);
            VkImageMemoryBarrier image{};
            image.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
            image.srcAccessMask = 0;
            image.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT
                    | VK_ACCESS_TRANSFER_READ_BIT;
            image.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
            image.newLayout = VK_IMAGE_LAYOUT_GENERAL;
            image.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            image.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            image.image = setup.color.image;
            image.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
            image.subresourceRange.levelCount = 1;
            image.subresourceRange.layerCount = 1;
            context.vkCmdPipelineBarrier(initialize, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                    VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT,
                    0, 0, nullptr, 0, nullptr, 1, &image);
            context.endTimedCommand(initialize, noQuery);
            context.submitTimed(initialize, noQuery);
        }

        setup.command = context.allocateCommandBuffer(setup.commandPool);
        setup.query = context.createTimestampQuery();
        context.beginTimedCommand(setup.command, setup.query);
        VkBufferCopy seedCopy{0, 0, setup.computeBytes};
        context.vkCmdCopyBuffer(setup.command, setup.seed.buffer, setup.working.buffer,
                                1, &seedCopy);
        VkBufferMemoryBarrier toCompute{};
        toCompute.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        toCompute.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        toCompute.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
        toCompute.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        toCompute.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        toCompute.buffer = setup.working.buffer;
        toCompute.offset = 0;
        toCompute.size = setup.computeBytes;
        context.vkCmdPipelineBarrier(setup.command, VK_PIPELINE_STAGE_TRANSFER_BIT,
                                     VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0,
                                     0, nullptr, 1, &toCompute, 0, nullptr);

        if (setup.graphics) {
            VkClearValue clear{};
            clear.color = {{0.0F, 0.0F, 0.0F, 1.0F}};
            VkRenderPassBeginInfo begin{};
            begin.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
            begin.renderPass = setup.renderPass;
            begin.framebuffer = setup.framebuffer;
            begin.renderArea = {{0, 0}, {kTraceWidth, kTraceHeight}};
            begin.clearValueCount = 1;
            begin.pClearValues = &clear;
            context.vkCmdBeginRenderPass(setup.command, &begin, VK_SUBPASS_CONTENTS_INLINE);
            context.vkCmdBindPipeline(setup.command, VK_PIPELINE_BIND_POINT_GRAPHICS,
                                      setup.graphicsPipeline);
            for (uint32_t index = 0; index < setup.drawCount; ++index) {
                const uint32_t column = index % 8U;
                const uint32_t row = index / 8U;
                DrawPush push{{0.14F, 0.14F,
                               -0.84F + static_cast<float>(column) * 0.24F,
                               -0.82F + static_cast<float>(row) * 0.23F},
                              {0.0F, 0.0F, 0.0F, 1.0F}};
                context.vkCmdPushConstants(setup.command, setup.graphicsLayout,
                        VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT,
                        0, sizeof(push), &push);
                context.vkCmdDraw(setup.command, 3, 1, 0, 0);
            }
            context.vkCmdEndRenderPass(setup.command);
        }

        context.vkCmdBindPipeline(setup.command, VK_PIPELINE_BIND_POINT_COMPUTE,
                                  setup.computePipeline);
        context.vkCmdBindDescriptorSets(setup.command, VK_PIPELINE_BIND_POINT_COMPUTE,
                                        setup.computeLayout, 0, 1, &setup.descriptorSet,
                                        0, nullptr);
        for (uint32_t index = 0; index < setup.dispatchCount; ++index) {
            context.vkCmdDispatch(setup.command, kTraceComputeWords / 256U, 1, 1);
            if (index + 1U < setup.dispatchCount) {
                VkBufferMemoryBarrier dependency{};
                dependency.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
                dependency.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
                dependency.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
                dependency.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
                dependency.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
                dependency.buffer = setup.working.buffer;
                dependency.offset = 0;
                dependency.size = setup.computeBytes;
                context.vkCmdPipelineBarrier(setup.command,
                        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0,
                        0, nullptr, 1, &dependency, 0, nullptr);
            }
        }
        VkBufferMemoryBarrier computeToTransfer{};
        computeToTransfer.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        computeToTransfer.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        computeToTransfer.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
        computeToTransfer.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        computeToTransfer.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        computeToTransfer.buffer = setup.working.buffer;
        computeToTransfer.offset = 0;
        computeToTransfer.size = setup.computeBytes;
        context.vkCmdPipelineBarrier(setup.command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                                     VK_PIPELINE_STAGE_TRANSFER_BIT, 0,
                                     0, nullptr, 1, &computeToTransfer, 0, nullptr);
        if (setup.graphics) {
            VkBufferImageCopy region{};
            region.bufferOffset = 0;
            region.bufferRowLength = 0;
            region.bufferImageHeight = 0;
            region.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
            region.imageSubresource.layerCount = 1;
            region.imageExtent = {kTraceWidth, kTraceHeight, 1};
            context.vkCmdCopyImageToBuffer(setup.command, setup.color.image,
                    VK_IMAGE_LAYOUT_GENERAL, setup.readback.buffer, 1, &region);
        }
        VkBufferCopy outputCopy{0, setup.graphicsBytes, setup.computeBytes};
        context.vkCmdCopyBuffer(setup.command, setup.working.buffer, setup.readback.buffer,
                                1, &outputCopy);
        VkBufferMemoryBarrier toHost{};
        toHost.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        toHost.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        toHost.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
        toHost.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        toHost.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        toHost.buffer = setup.readback.buffer;
        toHost.offset = 0;
        toHost.size = setup.totalBytes;
        context.vkCmdPipelineBarrier(setup.command, VK_PIPELINE_STAGE_TRANSFER_BIT,
                                     VK_PIPELINE_STAGE_HOST_BIT, 0,
                                     0, nullptr, 1, &toHost, 0, nullptr);
        context.endTimedCommand(setup.command, setup.query);
        return setup;
    }

    void destroyTraceSetup(TraceSetup &setup) {
        if (setup.commandPool != VK_NULL_HANDLE) context.vkDestroyCommandPool(context.device, setup.commandPool, nullptr);
        context.destroyTimestampQuery(setup.query);
        if (setup.graphicsPipeline != VK_NULL_HANDLE) context.vkDestroyPipeline(context.device, setup.graphicsPipeline, nullptr);
        if (setup.graphicsLayout != VK_NULL_HANDLE) context.vkDestroyPipelineLayout(context.device, setup.graphicsLayout, nullptr);
        if (setup.framebuffer != VK_NULL_HANDLE) context.vkDestroyFramebuffer(context.device, setup.framebuffer, nullptr);
        if (setup.renderPass != VK_NULL_HANDLE) context.vkDestroyRenderPass(context.device, setup.renderPass, nullptr);
        context.destroyImage(setup.color);
        if (setup.computePipeline != VK_NULL_HANDLE) context.vkDestroyPipeline(context.device, setup.computePipeline, nullptr);
        if (setup.computeLayout != VK_NULL_HANDLE) context.vkDestroyPipelineLayout(context.device, setup.computeLayout, nullptr);
        if (setup.descriptorPool != VK_NULL_HANDLE) context.vkDestroyDescriptorPool(context.device, setup.descriptorPool, nullptr);
        if (setup.setLayout != VK_NULL_HANDLE) context.vkDestroyDescriptorSetLayout(context.device, setup.setLayout, nullptr);
        context.destroyBuffer(setup.readback);
        context.destroyBuffer(setup.working);
        context.destroyBuffer(setup.seed);
        setup = {};
    }

    std::string runTraceReplay(const std::string &traceId, int warmupSeconds,
                               int measureSeconds, const std::string &rawOutputPath) {
        context.setStage("trace_replay_setup");
        if (rawOutputPath.empty()) throw std::invalid_argument("Trace output path is missing");
        TraceSetup setup = createTraceSetup(traceId);
        try {
            auto warmStart = Clock::now();
            do {
                context.submitTimed(setup.command, setup.query);
            } while (elapsedMs(warmStart) < static_cast<double>(warmupSeconds) * 1000.0);

            context.setStage("trace_replay_measure");
            std::vector<double> durations;
            auto measureStart = Clock::now();
            do {
                durations.push_back(context.submitTimed(setup.command, setup.query));
            } while ((elapsedMs(measureStart) < static_cast<double>(measureSeconds) * 1000.0
                      || durations.size() < 8U) && durations.size() < 500U);

            context.setStage("trace_replay_readback");
            void *mapped = nullptr;
            check(context.vkMapMemory(context.device, setup.readback.memory, 0,
                                      setup.readback.allocationSize, 0, &mapped),
                  "vkMapMemory(trace_readback)");
            if ((setup.readback.memoryFlags & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) == 0) {
                VkMappedMemoryRange range{};
                range.sType = VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE;
                range.memory = setup.readback.memory;
                range.offset = 0;
                range.size = VK_WHOLE_SIZE;
                check(context.vkInvalidateMappedMemoryRanges(context.device, 1, &range),
                      "vkInvalidateMappedMemoryRanges(trace_readback)");
            }
            const auto *bytes = static_cast<const uint8_t *>(mapped);
            uint64_t checksum = 1469598103934665603ULL;
            for (VkDeviceSize offset = setup.graphicsBytes; offset < setup.totalBytes; offset += 257U) {
                checksum ^= bytes[offset];
                checksum *= 1099511628211ULL;
            }
            std::ofstream output(rawOutputPath, std::ios::binary | std::ios::trunc);
            if (!output) {
                context.vkUnmapMemory(context.device, setup.readback.memory);
                throw std::runtime_error("Unable to open trace output file");
            }
            output.write(reinterpret_cast<const char *>(bytes),
                         static_cast<std::streamsize>(setup.totalBytes));
            output.flush();
            const bool outputOk = static_cast<bool>(output);
            output.close();
            context.vkUnmapMemory(context.device, setup.readback.memory);
            if (!outputOk) throw std::runtime_error("Unable to persist trace output");

            const bool timestamps = setup.query.supported;
            const VkDeviceSize graphicsBytes = setup.graphicsBytes;
            const VkDeviceSize computeBytes = setup.computeBytes;
            const VkDeviceSize totalBytes = setup.totalBytes;
            const uint32_t drawCount = setup.drawCount;
            const uint32_t dispatchCount = setup.dispatchCount;
            destroyTraceSetup(setup);

            std::ostringstream json;
            json << "{\"success\":true"
                 << ",\"workload_id\":\"" << kTraceReplayId << "\""
                 << ",\"workload_version\":" << kWorkloadVersion
                 << ",\"trace_id\":\"" << jsonEscape(traceId) << "\""
                 << ",\"trace_version\":" << kTraceVersion
                 << ",\"trace_format_version\":" << kTraceFormatVersion
                 << ",\"custom_driver\":" << (context.isCustomDriver() ? "true" : "false")
                 << ",\"sample_count\":" << durations.size()
                 << ",\"median_replay_ms\":" << median(durations)
                 << ",\"p95_replay_ms\":" << percentile(durations, 0.95)
                 << ",\"p99_replay_ms\":" << percentile(durations, 0.99)
                 << ",\"gpu_timestamps_used\":" << (timestamps ? "true" : "false")
                 << ",\"draw_count\":" << drawCount
                 << ",\"dispatch_count\":" << dispatchCount
                 << ",\"graphics_width\":" << (graphicsBytes > 0 ? kTraceWidth : 0)
                 << ",\"graphics_height\":" << (graphicsBytes > 0 ? kTraceHeight : 0)
                 << ",\"graphics_output_bytes\":" << graphicsBytes
                 << ",\"compute_output_bytes\":" << computeBytes
                 << ",\"output_bytes\":" << totalBytes
                 << ",\"output_format\":\""
                 << (graphicsBytes > 0 ? "RGBA8_UNORM+UINT32" : "UINT32") << "\""
                 << ",\"validation_checksum\":" << checksum
                 << ",\"sample_durations_ms\":";
            appendDoubleArray(json, durations);
            json << ",\"capabilities\":" << context.capabilities
                 << ",\"metric_note\":\"Versioned in-app Vulkan command trace only; it is not a game capture and does not represent application FPS.\""
                 << '}';
            return json.str();
        } catch (...) {
            destroyTraceSetup(setup);
            throw;
        }
    }

    std::string runStableScene(int warmupSeconds, int measureSeconds) {
        context.setStage("stable_scene_setup");
        RenderSetup setup = createRenderSetup(VK_SAMPLE_COUNT_1_BIT, true,
                                              kStableDrawCount, branchFragment);
        std::vector<double> samples;
        bool timestamps = false;
        try {
            samples = sampleRender(setup, warmupSeconds, measureSeconds);
            timestamps = setup.query.supported;
        } catch (...) {
            destroyRenderSetup(setup);
            throw;
        }
        destroyRenderSetup(setup);
        const double p99 = percentile(samples, 0.99);
        const double onePercentLow = p99 > 0.0 ? 1000.0 / p99 : 0.0;
        std::ostringstream json;
        json << "{\"success\":true"
             << ",\"workload_id\":\"" << kStableSceneId << "\""
             << ",\"workload_version\":" << kWorkloadVersion
             << ",\"custom_driver\":" << (context.isCustomDriver() ? "true" : "false")
             << ",\"width\":" << kRenderWidth
             << ",\"height\":" << kRenderHeight
             << ",\"draws_per_frame\":" << kStableDrawCount
             << ",\"sample_count\":" << samples.size()
             << ",\"median_frame_ms\":" << median(samples)
             << ",\"p50_frame_ms\":" << percentile(samples, 0.50)
             << ",\"p95_frame_ms\":" << percentile(samples, 0.95)
             << ",\"p99_frame_ms\":" << p99
             << ",\"one_percent_low_fps\":" << onePercentLow
             << ",\"gpu_timestamps_used\":" << (timestamps ? "true" : "false")
             << ",\"frame_times_ms\":";
        appendDoubleArray(json, samples);
        json << ",\"capabilities\":" << context.capabilities
             << ",\"metric_note\":\"Stable offscreen scene frametimes only; no CPU, I/O, dynamic shader compilation, or game logic is included.\""
             << '}';
        return json.str();
    }

    VulkanContext &context;
    VkShaderModule drawVertex = VK_NULL_HANDLE;
    VkShaderModule aluFragment = VK_NULL_HANDLE;
    VkShaderModule branchFragment = VK_NULL_HANDLE;
    VkShaderModule computeShader = VK_NULL_HANDLE;
    VkShaderModule traceFragment = VK_NULL_HANDLE;
    VkShaderModule traceCompute = VK_NULL_HANDLE;
};

struct DiagnosticFormatDefinition {
    VkFormat format;
    const char *name;
    bool depth;
    bool compressed;
};

const char *booleanJson(bool value) { return value ? "true" : "false"; }

void appendSampleCounts(std::ostringstream &json, VkSampleCountFlags flags) {
    const std::array<std::pair<VkSampleCountFlagBits, int>, 7> counts{{
            {VK_SAMPLE_COUNT_1_BIT, 1}, {VK_SAMPLE_COUNT_2_BIT, 2},
            {VK_SAMPLE_COUNT_4_BIT, 4}, {VK_SAMPLE_COUNT_8_BIT, 8},
            {VK_SAMPLE_COUNT_16_BIT, 16}, {VK_SAMPLE_COUNT_32_BIT, 32},
            {VK_SAMPLE_COUNT_64_BIT, 64}}};
    json << '[';
    bool first = true;
    for (const auto &entry : counts) {
        if ((flags & entry.first) == 0) continue;
        if (!first) json << ',';
        first = false;
        json << entry.second;
    }
    json << ']';
}

uint64_t fnv1a64(const uint32_t *values, size_t count) {
    uint64_t hash = 1469598103934665603ULL;
    for (size_t index = 0; index < count; ++index) {
        uint32_t value = values[index];
        for (int shift = 0; shift < 32; shift += 8) {
            hash ^= static_cast<uint8_t>((value >> shift) & 0xffU);
            hash *= 1099511628211ULL;
        }
    }
    return hash;
}

class DeepDiagnostics {
public:
    explicit DeepDiagnostics(VulkanContext &value) : context(value) {}

    std::string run(const std::string &mode, int cycles, int requestedMemoryMiB) {
        context.setStage("phase10_begin");
        const int safeCycles = std::max(1, std::min(cycles, 50));
        const int safeMemoryMiB = std::max(16, std::min(requestedMemoryMiB, 256));
        std::ostringstream json;
        json << "{\"success\":true"
             << ",\"deep_diagnostic_schema_version\":1"
             << ",\"profile_id\":\"turnip_deep_diagnostics\""
             << ",\"profile_version\":1"
             << ",\"mode\":\"" << jsonEscape(mode) << "\""
             << ",\"custom_driver\":" << booleanJson(context.isCustomDriver())
             << ",\"capabilities\":" << context.capabilities;
        if (mode == "soak") {
            json << ",\"soak\":" << soak(safeCycles, safeMemoryMiB);
        } else {
            json << ",\"format_matrix\":" << formatMatrix()
                 << ",\"shader_pipeline_corpus\":" << shaderPipelineCorpus()
                 << ",\"memory_pressure\":" << memoryPressure(safeMemoryMiB)
                 << ",\"synchronization\":" << synchronization()
                 << ",\"reliability_probe\":" << soak(std::min(safeCycles, 5),
                                                          std::min(safeMemoryMiB, 32));
        }
        json << ",\"limitations\":\"Synthetic Vulkan diagnostics; safe memory caps and serialized submissions do not reproduce games, emulator CPU load, system compositor, or unrestricted out-of-memory behavior.\"}";
        return json.str();
    }

private:
    std::string formatMatrix() {
        context.setStage("phase10_format_matrix");
        const std::array<DiagnosticFormatDefinition, 26> formats{{
                {VK_FORMAT_R8_UNORM, "R8_UNORM", false, false},
                {VK_FORMAT_R8G8_UNORM, "R8G8_UNORM", false, false},
                {VK_FORMAT_R8G8B8A8_UNORM, "R8G8B8A8_UNORM", false, false},
                {VK_FORMAT_R8G8B8A8_SRGB, "R8G8B8A8_SRGB", false, false},
                {VK_FORMAT_B8G8R8A8_UNORM, "B8G8R8A8_UNORM", false, false},
                {VK_FORMAT_B8G8R8A8_SRGB, "B8G8R8A8_SRGB", false, false},
                {VK_FORMAT_A2B10G10R10_UNORM_PACK32, "A2B10G10R10_UNORM_PACK32", false, false},
                {VK_FORMAT_B10G11R11_UFLOAT_PACK32, "R11G11B10_UFLOAT_PACK32", false, false},
                {VK_FORMAT_R16G16_SFLOAT, "R16G16_SFLOAT", false, false},
                {VK_FORMAT_R16G16B16A16_SFLOAT, "R16G16B16A16_SFLOAT", false, false},
                {VK_FORMAT_R32_SFLOAT, "R32_SFLOAT", false, false},
                {VK_FORMAT_R32G32B32A32_SFLOAT, "R32G32B32A32_SFLOAT", false, false},
                {VK_FORMAT_D16_UNORM, "D16_UNORM", true, false},
                {VK_FORMAT_D24_UNORM_S8_UINT, "D24_UNORM_S8_UINT", true, false},
                {VK_FORMAT_D32_SFLOAT, "D32_SFLOAT", true, false},
                {VK_FORMAT_D32_SFLOAT_S8_UINT, "D32_SFLOAT_S8_UINT", true, false},
                {VK_FORMAT_ETC2_R8G8B8_UNORM_BLOCK, "ETC2_R8G8B8_UNORM_BLOCK", false, true},
                {VK_FORMAT_ETC2_R8G8B8A8_UNORM_BLOCK, "ETC2_R8G8B8A8_UNORM_BLOCK", false, true},
                {VK_FORMAT_ASTC_4x4_UNORM_BLOCK, "ASTC_4x4_UNORM_BLOCK", false, true},
                {VK_FORMAT_ASTC_6x6_UNORM_BLOCK, "ASTC_6x6_UNORM_BLOCK", false, true},
                {VK_FORMAT_ASTC_8x8_UNORM_BLOCK, "ASTC_8x8_UNORM_BLOCK", false, true},
                {VK_FORMAT_BC1_RGBA_UNORM_BLOCK, "BC1_RGBA_UNORM_BLOCK", false, true},
                {VK_FORMAT_BC3_UNORM_BLOCK, "BC3_UNORM_BLOCK", false, true},
                {VK_FORMAT_BC7_UNORM_BLOCK, "BC7_UNORM_BLOCK", false, true},
                {VK_FORMAT_E5B9G9R9_UFLOAT_PACK32, "E5B9G9R9_UFLOAT_PACK32", false, false},
                {VK_FORMAT_R64_UINT, "R64_UINT", false, false}}};
        std::ostringstream json;
        json << "{\"format_matrix_version\":1,\"format_count\":" << formats.size()
             << ",\"formats\":[";
        for (size_t index = 0; index < formats.size(); ++index) {
            const auto &definition = formats[index];
            if (index > 0) json << ',';
            VkFormatProperties properties{};
            context.queryFormatProperties(definition.format, &properties);
            const VkFormatFeatureFlags optimal = properties.optimalTilingFeatures;
            const VkFormatFeatureFlags linear = properties.linearTilingFeatures;
            const VkFormatFeatureFlags buffer = properties.bufferFeatures;
            VkImageFormatProperties sampledProperties{};
            VkResult sampledResult = context.queryImageFormatProperties(
                    definition.format, VK_IMAGE_TYPE_2D, VK_IMAGE_TILING_OPTIMAL,
                    VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT
                            | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
                    0, &sampledProperties);
            VkImageFormatProperties attachmentProperties{};
            const VkImageUsageFlags attachmentUsage = definition.depth
                    ? VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT
                    : VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
            VkResult attachmentResult = definition.compressed ? VK_ERROR_FORMAT_NOT_SUPPORTED
                    : context.queryImageFormatProperties(
                            definition.format, VK_IMAGE_TYPE_2D, VK_IMAGE_TILING_OPTIMAL,
                            attachmentUsage, 0, &attachmentProperties);
            VkImageFormatProperties storageProperties{};
            VkResult storageResult = definition.depth || definition.compressed
                    ? VK_ERROR_FORMAT_NOT_SUPPORTED
                    : context.queryImageFormatProperties(
                            definition.format, VK_IMAGE_TYPE_2D, VK_IMAGE_TILING_OPTIMAL,
                            VK_IMAGE_USAGE_STORAGE_BIT, 0, &storageProperties);
            json << "{\"format\":\"" << definition.name << "\""
                 << ",\"format_value\":" << static_cast<int>(definition.format)
                 << ",\"depth_stencil\":" << booleanJson(definition.depth)
                 << ",\"compressed\":" << booleanJson(definition.compressed)
                 << ",\"linear_sampled\":"
                 << booleanJson((linear & VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT) != 0)
                 << ",\"optimal_sampled\":"
                 << booleanJson((optimal & VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT) != 0)
                 << ",\"optimal_linear_filter\":"
                 << booleanJson((optimal & VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT) != 0)
                 << ",\"optimal_color_attachment\":"
                 << booleanJson((optimal & VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT) != 0)
                 << ",\"optimal_depth_stencil_attachment\":"
                 << booleanJson((optimal & VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT) != 0)
                 << ",\"optimal_storage_image\":"
                 << booleanJson((optimal & VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT) != 0)
                 << ",\"optimal_blit_src\":"
                 << booleanJson((optimal & VK_FORMAT_FEATURE_BLIT_SRC_BIT) != 0)
                 << ",\"optimal_blit_dst\":"
                 << booleanJson((optimal & VK_FORMAT_FEATURE_BLIT_DST_BIT) != 0)
                 << ",\"buffer_vertex\":"
                 << booleanJson((buffer & VK_FORMAT_FEATURE_VERTEX_BUFFER_BIT) != 0)
                 << ",\"buffer_storage_texel\":"
                 << booleanJson((buffer & VK_FORMAT_FEATURE_STORAGE_TEXEL_BUFFER_BIT) != 0)
                 << ",\"sampled_image_supported\":"
                 << booleanJson(sampledResult == VK_SUCCESS)
                 << ",\"attachment_image_supported\":"
                 << booleanJson(attachmentResult == VK_SUCCESS)
                 << ",\"storage_image_supported\":"
                 << booleanJson(storageResult == VK_SUCCESS)
                 << ",\"attachment_sample_counts\":";
            appendSampleCounts(json, attachmentResult == VK_SUCCESS
                    ? attachmentProperties.sampleCounts : 0);
            json << '}';
        }
        json << "],\"limitations\":\"Queries Vulkan format properties and representative image usages; it does not exhaust every image type, create flag, modifier, external-memory path, or driver-internal tiling decision.\"}";
        return json.str();
    }

    struct CorpusCase {
        const char *id;
        const uint32_t *code;
        size_t codeSize;
        uint32_t groups;
    };

    std::string shaderPipelineCorpus() {
        context.setStage("phase10_shader_pipeline_corpus");
        const std::array<CorpusCase, 6> cases{{
                {"float_fma_loop", kComputeSpirv, sizeof(kComputeSpirv), 4},
                {"integer_dependency", kTraceIntegerComputeSpirv,
                 sizeof(kTraceIntegerComputeSpirv), 4},
                {"divergent_branch_loop", kDiagnosticBranchSpirv,
                 sizeof(kDiagnosticBranchSpirv), 16},
                {"storage_atomic", kDiagnosticAtomicSpirv,
                 sizeof(kDiagnosticAtomicSpirv), 16},
                {"shared_memory_barrier", kDiagnosticSharedSpirv,
                 sizeof(kDiagnosticSharedSpirv), 16},
                {"large_mixed_arithmetic", kDiagnosticLargeSpirv,
                 sizeof(kDiagnosticLargeSpirv), 16}}};

        VkDescriptorSetLayout descriptorLayout = VK_NULL_HANDLE;
        VkPipelineLayout pipelineLayout = VK_NULL_HANDLE;
        VkPipelineCache cache = VK_NULL_HANDLE;
        VkDescriptorPool descriptorPool = VK_NULL_HANDLE;
        VkDescriptorSet descriptorSet = VK_NULL_HANDLE;
        VkCommandPool commandPool = VK_NULL_HANDLE;
        BufferResource buffer;
        std::vector<uint8_t> cacheData;
        std::ostringstream caseJson;
        int successes = 0;
        double coldTotal = 0.0;
        double warmTotal = 0.0;
        try {
            VkDescriptorSetLayoutBinding binding{};
            binding.binding = 0;
            binding.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            binding.descriptorCount = 1;
            binding.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
            VkDescriptorSetLayoutCreateInfo layoutInfo{};
            layoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
            layoutInfo.bindingCount = 1;
            layoutInfo.pBindings = &binding;
            check(context.vkCreateDescriptorSetLayout(
                    context.device, &layoutInfo, nullptr, &descriptorLayout),
                  "vkCreateDescriptorSetLayout(phase10)");
            VkPipelineLayoutCreateInfo pipelineLayoutInfo{};
            pipelineLayoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
            pipelineLayoutInfo.setLayoutCount = 1;
            pipelineLayoutInfo.pSetLayouts = &descriptorLayout;
            check(context.vkCreatePipelineLayout(
                    context.device, &pipelineLayoutInfo, nullptr, &pipelineLayout),
                  "vkCreatePipelineLayout(phase10)");
            VkPipelineCacheCreateInfo cacheInfo{};
            cacheInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_CACHE_CREATE_INFO;
            check(context.vkCreatePipelineCache(context.device, &cacheInfo, nullptr, &cache),
                  "vkCreatePipelineCache(phase10)");

            buffer = context.createBuffer(
                    4096U * sizeof(uint32_t),
                    VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT
                            | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT,
                    VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
            VkDescriptorPoolSize poolSize{};
            poolSize.type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            poolSize.descriptorCount = 1;
            VkDescriptorPoolCreateInfo poolInfo{};
            poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
            poolInfo.maxSets = 1;
            poolInfo.poolSizeCount = 1;
            poolInfo.pPoolSizes = &poolSize;
            check(context.vkCreateDescriptorPool(
                    context.device, &poolInfo, nullptr, &descriptorPool),
                  "vkCreateDescriptorPool(phase10)");
            VkDescriptorSetAllocateInfo setInfo{};
            setInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
            setInfo.descriptorPool = descriptorPool;
            setInfo.descriptorSetCount = 1;
            setInfo.pSetLayouts = &descriptorLayout;
            check(context.vkAllocateDescriptorSets(context.device, &setInfo, &descriptorSet),
                  "vkAllocateDescriptorSets(phase10)");
            VkDescriptorBufferInfo bufferInfo{};
            bufferInfo.buffer = buffer.buffer;
            bufferInfo.offset = 0;
            bufferInfo.range = 4096U * sizeof(uint32_t);
            VkWriteDescriptorSet write{};
            write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
            write.dstSet = descriptorSet;
            write.dstBinding = 0;
            write.descriptorCount = 1;
            write.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            write.pBufferInfo = &bufferInfo;
            context.vkUpdateDescriptorSets(context.device, 1, &write, 0, nullptr);
            commandPool = context.createCommandPool();

            caseJson << '[';
            for (size_t index = 0; index < cases.size(); ++index) {
                if (index > 0) caseJson << ',';
                const auto &entry = cases[index];
                VkShaderModule shader = VK_NULL_HANDLE;
                VkPipeline coldPipeline = VK_NULL_HANDLE;
                VkPipeline warmPipeline = VK_NULL_HANDLE;
                try {
                    auto shaderStart = Clock::now();
                    shader = context.createShader(entry.code, entry.codeSize);
                    const double shaderModuleMs = elapsedMs(shaderStart);
                    VkPipelineShaderStageCreateInfo stage{};
                    stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
                    stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
                    stage.module = shader;
                    stage.pName = "main";
                    VkComputePipelineCreateInfo pipelineInfo{};
                    pipelineInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
                    pipelineInfo.stage = stage;
                    pipelineInfo.layout = pipelineLayout;
                    auto coldStart = Clock::now();
                    check(context.vkCreateComputePipelines(
                            context.device, cache, 1, &pipelineInfo, nullptr, &coldPipeline),
                          "vkCreateComputePipelines(cold phase10)");
                    const double coldMs = elapsedMs(coldStart);
                    auto warmStart = Clock::now();
                    check(context.vkCreateComputePipelines(
                            context.device, cache, 1, &pipelineInfo, nullptr, &warmPipeline),
                          "vkCreateComputePipelines(warm phase10)");
                    const double warmMs = elapsedMs(warmStart);
                    initializeBuffer(buffer, 4096);
                    VkCommandBuffer command = context.allocateCommandBuffer(commandPool);
                    VkCommandBufferBeginInfo begin{};
                    begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
                    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
                    check(context.vkBeginCommandBuffer(command, &begin),
                          "vkBeginCommandBuffer(shader corpus)");
                    context.vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_COMPUTE,
                                              warmPipeline);
                    context.vkCmdBindDescriptorSets(command, VK_PIPELINE_BIND_POINT_COMPUTE,
                                                    pipelineLayout, 0, 1, &descriptorSet,
                                                    0, nullptr);
                    context.vkCmdDispatch(command, entry.groups, 1, 1);
                    check(context.vkEndCommandBuffer(command),
                          "vkEndCommandBuffer(shader corpus)");
                    VkSubmitInfo submit{};
                    submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
                    submit.commandBufferCount = 1;
                    submit.pCommandBuffers = &command;
                    auto executeStart = Clock::now();
                    check(context.vkQueueSubmit(context.queue, 1, &submit, VK_NULL_HANDLE),
                          "vkQueueSubmit(shader corpus)");
                    check(context.vkQueueWaitIdle(context.queue),
                          "vkQueueWaitIdle(shader corpus)");
                    const double executeMs = elapsedMs(executeStart);
                    const uint64_t outputHash = hashBuffer(buffer, 1025);
                    caseJson << "{\"case_id\":\"" << entry.id << "\""
                             << ",\"success\":true"
                             << ",\"shader_module_ms\":" << shaderModuleMs
                             << ",\"cold_pipeline_ms\":" << coldMs
                             << ",\"warm_pipeline_ms\":" << warmMs
                             << ",\"execute_wall_ms\":" << executeMs
                             << ",\"output_fnv1a64\":\"" << std::hex << outputHash
                             << std::dec << "\"}";
                    successes++;
                    coldTotal += coldMs;
                    warmTotal += warmMs;
                } catch (const std::exception &error) {
                    caseJson << "{\"case_id\":\"" << entry.id
                             << "\",\"success\":false,\"error\":\""
                             << jsonEscape(error.what()) << "\"}";
                }
                if (warmPipeline != VK_NULL_HANDLE) {
                    context.vkDestroyPipeline(context.device, warmPipeline, nullptr);
                }
                if (coldPipeline != VK_NULL_HANDLE) {
                    context.vkDestroyPipeline(context.device, coldPipeline, nullptr);
                }
                if (shader != VK_NULL_HANDLE) {
                    context.vkDestroyShaderModule(context.device, shader, nullptr);
                }
            }
            caseJson << ']';
            size_t cacheSize = 0;
            VkResult cacheSizeResult = context.vkGetPipelineCacheData(
                    context.device, cache, &cacheSize, nullptr);
            if (cacheSizeResult == VK_SUCCESS && cacheSize > 0 && cacheSize <= 16U * 1024U * 1024U) {
                cacheData.resize(cacheSize);
                VkResult dataResult = context.vkGetPipelineCacheData(
                        context.device, cache, &cacheSize, cacheData.data());
                if (dataResult != VK_SUCCESS) cacheData.clear();
                else cacheData.resize(cacheSize);
            }
        } catch (...) {
            cleanupPipelineResources(descriptorLayout, pipelineLayout, cache, descriptorPool,
                                     commandPool, buffer);
            throw;
        }
        cleanupPipelineResources(descriptorLayout, pipelineLayout, cache, descriptorPool,
                                 commandPool, buffer);
        std::ostringstream json;
        json << "{\"shader_corpus_version\":1"
             << ",\"pipeline_cache_diagnostic_version\":1"
             << ",\"case_count\":" << cases.size()
             << ",\"successful_cases\":" << successes
             << ",\"all_cases_passed\":" << booleanJson(successes == static_cast<int>(cases.size()))
             << ",\"cold_pipeline_total_ms\":" << coldTotal
             << ",\"warm_pipeline_total_ms\":" << warmTotal
             << ",\"warm_vs_cold_percent\":"
             << (coldTotal > 0.0 ? (warmTotal / coldTotal - 1.0) * 100.0 : 0.0)
             << ",\"pipeline_cache_serialized_bytes\":" << cacheData.size()
             << ",\"cases\":" << caseJson.str()
             << ",\"coverage\":{\"compute_pipeline_execution\":true"
             << ",\"graphics_fragment_module_only\":false"
             << ",\"subgroup_execution\":false}"
             << ",\"limitations\":\"Version 1 executes a deterministic compute corpus covering floating point, integer dependencies, divergent branches, atomics, shared memory and a large mixed shader. Graphics-only derivatives, texture gathers and subgroup execution are not yet part of this corpus.\"}";
        return json.str();
    }

    void initializeBuffer(BufferResource &buffer, size_t words) {
        void *mapped = nullptr;
        check(context.vkMapMemory(context.device, buffer.memory, 0, VK_WHOLE_SIZE,
                                  0, &mapped), "vkMapMemory(phase10 initialize)");
        auto *values = static_cast<uint32_t *>(mapped);
        for (size_t index = 0; index < words; ++index) {
            values[index] = static_cast<uint32_t>(index * 2654435761U) ^ 0x6d2b79f5U;
        }
        if ((buffer.memoryFlags & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) == 0) {
            VkMappedMemoryRange range{};
            range.sType = VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE;
            range.memory = buffer.memory;
            range.offset = 0;
            range.size = VK_WHOLE_SIZE;
            check(context.vkFlushMappedMemoryRanges(context.device, 1, &range),
                  "vkFlushMappedMemoryRanges(phase10)");
        }
        context.vkUnmapMemory(context.device, buffer.memory);
    }

    uint64_t hashBuffer(BufferResource &buffer, size_t words) {
        void *mapped = nullptr;
        check(context.vkMapMemory(context.device, buffer.memory, 0, VK_WHOLE_SIZE,
                                  0, &mapped), "vkMapMemory(phase10 hash)");
        if ((buffer.memoryFlags & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) == 0) {
            VkMappedMemoryRange range{};
            range.sType = VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE;
            range.memory = buffer.memory;
            range.offset = 0;
            range.size = VK_WHOLE_SIZE;
            check(context.vkInvalidateMappedMemoryRanges(context.device, 1, &range),
                  "vkInvalidateMappedMemoryRanges(phase10)");
        }
        const uint64_t hash = fnv1a64(static_cast<uint32_t *>(mapped), words);
        context.vkUnmapMemory(context.device, buffer.memory);
        return hash;
    }

    void cleanupPipelineResources(VkDescriptorSetLayout descriptorLayout,
                                  VkPipelineLayout pipelineLayout,
                                  VkPipelineCache cache,
                                  VkDescriptorPool descriptorPool,
                                  VkCommandPool commandPool,
                                  BufferResource &buffer) {
        if (context.device != VK_NULL_HANDLE) context.vkDeviceWaitIdle(context.device);
        if (commandPool != VK_NULL_HANDLE) {
            context.vkDestroyCommandPool(context.device, commandPool, nullptr);
        }
        if (descriptorPool != VK_NULL_HANDLE) {
            context.vkDestroyDescriptorPool(context.device, descriptorPool, nullptr);
        }
        context.destroyBuffer(buffer);
        if (cache != VK_NULL_HANDLE) context.vkDestroyPipelineCache(context.device, cache, nullptr);
        if (pipelineLayout != VK_NULL_HANDLE) {
            context.vkDestroyPipelineLayout(context.device, pipelineLayout, nullptr);
        }
        if (descriptorLayout != VK_NULL_HANDLE) {
            context.vkDestroyDescriptorSetLayout(context.device, descriptorLayout, nullptr);
        }
    }

    std::string memoryPressure(int requestedMemoryMiB) {
        context.setStage("phase10_memory_pressure");
        const uint64_t requested = static_cast<uint64_t>(requestedMemoryMiB) * 1024ULL * 1024ULL;
        uint64_t largestDeviceHeap = 0;
        uint64_t largestHeap = 0;
        for (uint32_t index = 0; index < context.memoryProperties.memoryHeapCount; ++index) {
            const auto &heap = context.memoryProperties.memoryHeaps[index];
            largestHeap = std::max(largestHeap, static_cast<uint64_t>(heap.size));
            if ((heap.flags & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) != 0) {
                largestDeviceHeap = std::max(largestDeviceHeap, static_cast<uint64_t>(heap.size));
            }
        }
        const uint64_t referenceHeap = largestDeviceHeap > 0 ? largestDeviceHeap : largestHeap;
        const uint64_t heapCap = referenceHeap > 0 ? std::max<uint64_t>(16ULL * 1024ULL * 1024ULL,
                                                                        referenceHeap / 8ULL)
                                                    : requested;
        const uint64_t safeTarget = std::min(requested, heapCap);
        const uint64_t chunk = 8ULL * 1024ULL * 1024ULL;
        std::vector<BufferResource> buffers;
        uint64_t allocated = 0;
        uint64_t peak = 0;
        std::string firstFailure;
        auto start = Clock::now();
        while (allocated < safeTarget) {
            const uint64_t next = std::min(chunk, safeTarget - allocated);
            try {
                BufferResource resource = context.createBuffer(
                        next, VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                        0, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
                allocated += resource.allocationSize;
                peak = std::max(peak, allocated);
                buffers.push_back(resource);
            } catch (const std::exception &error) {
                firstFailure = error.what();
                break;
            }
        }
        uint64_t freedForFragmentation = 0;
        for (size_t index = 0; index < buffers.size(); index += 2) {
            freedForFragmentation += buffers[index].allocationSize;
            allocated -= buffers[index].allocationSize;
            context.destroyBuffer(buffers[index]);
        }
        int replacementCount = 0;
        uint64_t replacementBytes = 0;
        while (replacementBytes + 4ULL * 1024ULL * 1024ULL <= freedForFragmentation) {
            try {
                BufferResource resource = context.createBuffer(
                        4ULL * 1024ULL * 1024ULL,
                        VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                        0, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
                replacementBytes += resource.allocationSize;
                allocated += resource.allocationSize;
                peak = std::max(peak, allocated);
                buffers.push_back(resource);
                replacementCount++;
            } catch (const std::exception &error) {
                if (firstFailure.empty()) firstFailure = error.what();
                break;
            }
        }
        for (auto &resource : buffers) context.destroyBuffer(resource);
        const double durationMs = elapsedMs(start);
        std::ostringstream json;
        json << "{\"memory_pressure_version\":1"
             << ",\"requested_bytes\":" << requested
             << ",\"safe_target_bytes\":" << safeTarget
             << ",\"largest_device_local_heap_bytes\":" << largestDeviceHeap
             << ",\"peak_allocated_bytes\":" << peak
             << ",\"initial_allocation_count\":" << buffers.size()
             << ",\"fragmentation_replacement_count\":" << replacementCount
             << ",\"fragmentation_replacement_bytes\":" << replacementBytes
             << ",\"completed_safe_target\":" << booleanJson(peak >= safeTarget)
             << ",\"duration_ms\":" << durationMs;
        if (firstFailure.empty()) json << ",\"first_failure\":null";
        else json << ",\"first_failure\":\"" << jsonEscape(firstFailure) << "\"";
        json << ",\"limitations\":\"Allocation is capped to the lower of the user request and one eighth of the largest relevant Vulkan heap. It diagnoses allocator behavior without intentionally exhausting Android memory.\"}";
        return json.str();
    }

    bool validatePattern(BufferResource &buffer, uint32_t expected) {
        void *mapped = nullptr;
        check(context.vkMapMemory(context.device, buffer.memory, 0, VK_WHOLE_SIZE,
                                  0, &mapped), "vkMapMemory(sync validate)");
        if ((buffer.memoryFlags & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) == 0) {
            VkMappedMemoryRange range{};
            range.sType = VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE;
            range.memory = buffer.memory;
            range.offset = 0;
            range.size = VK_WHOLE_SIZE;
            check(context.vkInvalidateMappedMemoryRanges(context.device, 1, &range),
                  "vkInvalidateMappedMemoryRanges(sync validate)");
        }
        const auto *words = static_cast<const uint32_t *>(mapped);
        bool valid = words[0] == expected && words[(1024U * 1024U / 4U) - 1U] == expected;
        context.vkUnmapMemory(context.device, buffer.memory);
        return valid;
    }

    std::string synchronization() {
        context.setStage("phase10_synchronization");
        BufferResource source;
        BufferResource destination;
        VkCommandPool pool = VK_NULL_HANDLE;
        VkFence fence = VK_NULL_HANDLE;
        VkSemaphore semaphore = VK_NULL_HANDLE;
        std::vector<double> fenceTimes;
        bool fenceCopyValid = false;
        bool binarySemaphoreValid = false;
        try {
            source = context.createBuffer(
                    1024U * 1024U,
                    VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT,
                    VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
            destination = context.createBuffer(
                    1024U * 1024U,
                    VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT,
                    VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
            pool = context.createCommandPool();
            VkFenceCreateInfo fenceInfo{};
            fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
            check(context.vkCreateFence(context.device, &fenceInfo, nullptr, &fence),
                  "vkCreateFence(phase10)");
            VkSemaphoreCreateInfo semaphoreInfo{};
            semaphoreInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
            check(context.vkCreateSemaphore(
                    context.device, &semaphoreInfo, nullptr, &semaphore),
                  "vkCreateSemaphore(phase10)");

            VkCommandBuffer command = context.allocateCommandBuffer(pool);
            VkCommandBufferBeginInfo begin{};
            begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
            begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
            check(context.vkBeginCommandBuffer(command, &begin),
                  "vkBeginCommandBuffer(fence copy)");
            context.vkCmdFillBuffer(command, source.buffer, 0, VK_WHOLE_SIZE, 0x5a17c0deU);
            VkBufferMemoryBarrier barrier{};
            barrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
            barrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
            barrier.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
            barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            barrier.buffer = source.buffer;
            barrier.offset = 0;
            barrier.size = VK_WHOLE_SIZE;
            context.vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TRANSFER_BIT,
                                         VK_PIPELINE_STAGE_TRANSFER_BIT, 0,
                                         0, nullptr, 1, &barrier, 0, nullptr);
            VkBufferCopy copy{};
            copy.size = 1024U * 1024U;
            context.vkCmdCopyBuffer(command, source.buffer, destination.buffer, 1, &copy);
            check(context.vkEndCommandBuffer(command), "vkEndCommandBuffer(fence copy)");
            VkSubmitInfo submit{};
            submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
            submit.commandBufferCount = 1;
            submit.pCommandBuffers = &command;
            check(context.vkQueueSubmit(context.queue, 1, &submit, fence),
                  "vkQueueSubmit(fence copy)");
            check(context.vkWaitForFences(context.device, 1, &fence, VK_TRUE,
                                          5ULL * 1000ULL * 1000ULL * 1000ULL),
                  "vkWaitForFences(fence copy)");
            fenceCopyValid = validatePattern(destination, 0x5a17c0deU);

            VkCommandBuffer signalCommand = context.allocateCommandBuffer(pool);
            check(context.vkBeginCommandBuffer(signalCommand, &begin),
                  "vkBeginCommandBuffer(semaphore signal)");
            context.vkCmdFillBuffer(signalCommand, source.buffer, 0, VK_WHOLE_SIZE, 0x3c6ef372U);
            check(context.vkEndCommandBuffer(signalCommand),
                  "vkEndCommandBuffer(semaphore signal)");
            VkSubmitInfo signalSubmit{};
            signalSubmit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
            signalSubmit.commandBufferCount = 1;
            signalSubmit.pCommandBuffers = &signalCommand;
            signalSubmit.signalSemaphoreCount = 1;
            signalSubmit.pSignalSemaphores = &semaphore;
            check(context.vkQueueSubmit(context.queue, 1, &signalSubmit, VK_NULL_HANDLE),
                  "vkQueueSubmit(semaphore signal)");

            VkCommandBuffer waitCommand = context.allocateCommandBuffer(pool);
            check(context.vkBeginCommandBuffer(waitCommand, &begin),
                  "vkBeginCommandBuffer(semaphore wait)");
            context.vkCmdCopyBuffer(waitCommand, source.buffer, destination.buffer, 1, &copy);
            check(context.vkEndCommandBuffer(waitCommand),
                  "vkEndCommandBuffer(semaphore wait)");
            check(context.vkResetFences(context.device, 1, &fence),
                  "vkResetFences(semaphore)");
            VkPipelineStageFlags waitStage = VK_PIPELINE_STAGE_TRANSFER_BIT;
            VkSubmitInfo waitSubmit{};
            waitSubmit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
            waitSubmit.waitSemaphoreCount = 1;
            waitSubmit.pWaitSemaphores = &semaphore;
            waitSubmit.pWaitDstStageMask = &waitStage;
            waitSubmit.commandBufferCount = 1;
            waitSubmit.pCommandBuffers = &waitCommand;
            check(context.vkQueueSubmit(context.queue, 1, &waitSubmit, fence),
                  "vkQueueSubmit(semaphore wait)");
            check(context.vkWaitForFences(context.device, 1, &fence, VK_TRUE,
                                          5ULL * 1000ULL * 1000ULL * 1000ULL),
                  "vkWaitForFences(semaphore wait)");
            binarySemaphoreValid = validatePattern(destination, 0x3c6ef372U);

            VkCommandBuffer empty = context.allocateCommandBuffer(pool);
            check(context.vkBeginCommandBuffer(empty, &begin),
                  "vkBeginCommandBuffer(empty sync)");
            check(context.vkEndCommandBuffer(empty), "vkEndCommandBuffer(empty sync)");
            VkSubmitInfo emptySubmit{};
            emptySubmit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
            emptySubmit.commandBufferCount = 1;
            emptySubmit.pCommandBuffers = &empty;
            for (int iteration = 0; iteration < 32; ++iteration) {
                check(context.vkResetFences(context.device, 1, &fence),
                      "vkResetFences(latency)");
                auto start = Clock::now();
                check(context.vkQueueSubmit(context.queue, 1, &emptySubmit, fence),
                      "vkQueueSubmit(latency)");
                check(context.vkWaitForFences(context.device, 1, &fence, VK_TRUE,
                                              5ULL * 1000ULL * 1000ULL * 1000ULL),
                      "vkWaitForFences(latency)");
                fenceTimes.push_back(elapsedMs(start));
            }
        } catch (...) {
            cleanupSynchronization(pool, fence, semaphore, source, destination);
            throw;
        }
        cleanupSynchronization(pool, fence, semaphore, source, destination);
        std::ostringstream json;
        json << "{\"synchronization_version\":1"
             << ",\"fence_copy_valid\":" << booleanJson(fenceCopyValid)
             << ",\"binary_semaphore_chain_valid\":" << booleanJson(binarySemaphoreValid)
             << ",\"fence_submit_wait_count\":" << fenceTimes.size()
             << ",\"fence_submit_wait_p50_ms\":" << percentile(fenceTimes, 0.50)
             << ",\"fence_submit_wait_p95_ms\":" << percentile(fenceTimes, 0.95)
             << ",\"fence_submit_wait_p99_ms\":" << percentile(fenceTimes, 0.99)
             << ",\"timeline_semaphore_extension_exposed\":"
             << booleanJson(context.supportsExtension(VK_KHR_TIMELINE_SEMAPHORE_EXTENSION_NAME))
             << ",\"timeline_semaphore_executed\":false"
             << ",\"queue_ownership_transition_executed\":false"
             << ",\"ownership_transition_reason\":\"v1 uses one graphics+compute queue family to avoid changing existing device feature negotiation\""
             << ",\"passed\":" << booleanJson(fenceCopyValid && binarySemaphoreValid)
             << ",\"limitations\":\"Version 1 validates fences, binary semaphores and transfer barriers on one queue family. Timeline semaphore and cross-family ownership execution are reported as future coverage rather than silently enabled in existing workloads.\"}";
        return json.str();
    }

    void cleanupSynchronization(VkCommandPool pool, VkFence fence, VkSemaphore semaphore,
                                BufferResource &source, BufferResource &destination) {
        if (context.device != VK_NULL_HANDLE) context.vkDeviceWaitIdle(context.device);
        if (fence != VK_NULL_HANDLE) context.vkDestroyFence(context.device, fence, nullptr);
        if (semaphore != VK_NULL_HANDLE) {
            context.vkDestroySemaphore(context.device, semaphore, nullptr);
        }
        if (pool != VK_NULL_HANDLE) context.vkDestroyCommandPool(context.device, pool, nullptr);
        context.destroyBuffer(source);
        context.destroyBuffer(destination);
    }

    void quickCycle(int memoryMiB) {
        VkPipelineLayout layout = VK_NULL_HANDLE;
        VkPipelineCache cache = VK_NULL_HANDLE;
        VkShaderModule shader = VK_NULL_HANDLE;
        VkPipeline pipeline = VK_NULL_HANDLE;
        VkCommandPool pool = VK_NULL_HANDLE;
        VkFence fence = VK_NULL_HANDLE;
        std::vector<BufferResource> buffers;
        try {
            VkPipelineLayoutCreateInfo layoutInfo{};
            layoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
            check(context.vkCreatePipelineLayout(context.device, &layoutInfo, nullptr, &layout),
                  "vkCreatePipelineLayout(soak)");
            VkPipelineCacheCreateInfo cacheInfo{};
            cacheInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_CACHE_CREATE_INFO;
            check(context.vkCreatePipelineCache(context.device, &cacheInfo, nullptr, &cache),
                  "vkCreatePipelineCache(soak)");
            shader = context.createShader(kDiagnosticEmptySpirv,
                                          sizeof(kDiagnosticEmptySpirv));
            VkPipelineShaderStageCreateInfo stage{};
            stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
            stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
            stage.module = shader;
            stage.pName = "main";
            VkComputePipelineCreateInfo pipelineInfo{};
            pipelineInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
            pipelineInfo.stage = stage;
            pipelineInfo.layout = layout;
            // The shader references a descriptor, so pipeline creation validates the interface;
            // execution is intentionally omitted from the quick soak cycle.
            check(context.vkCreateComputePipelines(context.device, cache, 1, &pipelineInfo,
                                                   nullptr, &pipeline),
                  "vkCreateComputePipelines(soak)");
            const int allocationCount = std::max(1, std::min(memoryMiB / 4, 8));
            for (int index = 0; index < allocationCount; ++index) {
                buffers.push_back(context.createBuffer(
                        4ULL * 1024ULL * 1024ULL,
                        VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                        0, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT));
            }
            pool = context.createCommandPool();
            VkCommandBuffer command = context.allocateCommandBuffer(pool);
            VkCommandBufferBeginInfo begin{};
            begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
            begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
            check(context.vkBeginCommandBuffer(command, &begin),
                  "vkBeginCommandBuffer(soak)");
            check(context.vkEndCommandBuffer(command), "vkEndCommandBuffer(soak)");
            VkFenceCreateInfo fenceInfo{};
            fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
            check(context.vkCreateFence(context.device, &fenceInfo, nullptr, &fence),
                  "vkCreateFence(soak)");
            VkSubmitInfo submit{};
            submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
            submit.commandBufferCount = 1;
            submit.pCommandBuffers = &command;
            check(context.vkQueueSubmit(context.queue, 1, &submit, fence),
                  "vkQueueSubmit(soak)");
            check(context.vkWaitForFences(context.device, 1, &fence, VK_TRUE,
                                          5ULL * 1000ULL * 1000ULL * 1000ULL),
                  "vkWaitForFences(soak)");
        } catch (...) {
            cleanupQuick(layout, cache, shader, pipeline, pool, fence, buffers);
            throw;
        }
        cleanupQuick(layout, cache, shader, pipeline, pool, fence, buffers);
    }

    void cleanupQuick(VkPipelineLayout layout, VkPipelineCache cache, VkShaderModule shader,
                      VkPipeline pipeline, VkCommandPool pool, VkFence fence,
                      std::vector<BufferResource> &buffers) {
        if (context.device != VK_NULL_HANDLE) context.vkDeviceWaitIdle(context.device);
        if (fence != VK_NULL_HANDLE) context.vkDestroyFence(context.device, fence, nullptr);
        if (pool != VK_NULL_HANDLE) context.vkDestroyCommandPool(context.device, pool, nullptr);
        for (auto &buffer : buffers) context.destroyBuffer(buffer);
        if (pipeline != VK_NULL_HANDLE) context.vkDestroyPipeline(context.device, pipeline, nullptr);
        if (shader != VK_NULL_HANDLE) {
            context.vkDestroyShaderModule(context.device, shader, nullptr);
        }
        if (cache != VK_NULL_HANDLE) context.vkDestroyPipelineCache(context.device, cache, nullptr);
        if (layout != VK_NULL_HANDLE) context.vkDestroyPipelineLayout(context.device, layout, nullptr);
    }

    std::string soak(int cycles, int memoryMiB) {
        context.setStage("phase10_soak");
        std::vector<double> durations;
        int completed = 0;
        int failureCycle = 0;
        std::string error;
        for (int cycle = 1; cycle <= cycles; ++cycle) {
            auto start = Clock::now();
            try {
                quickCycle(memoryMiB);
                durations.push_back(elapsedMs(start));
                completed++;
            } catch (const std::exception &failure) {
                failureCycle = cycle;
                error = failure.what();
                break;
            }
        }
        std::ostringstream json;
        json << "{\"soak_test_version\":1"
             << ",\"requested_cycles\":" << cycles
             << ",\"completed_cycles\":" << completed
             << ",\"failure_cycle\":" << (failureCycle == 0 ? "null" : std::to_string(failureCycle))
             << ",\"cycle_p50_ms\":" << (durations.empty() ? 0.0 : percentile(durations, 0.50))
             << ",\"cycle_p95_ms\":" << (durations.empty() ? 0.0 : percentile(durations, 0.95))
             << ",\"cycle_p99_ms\":" << (durations.empty() ? 0.0 : percentile(durations, 0.99))
             << ",\"passed\":" << booleanJson(completed == cycles);
        if (error.empty()) json << ",\"error\":null";
        else json << ",\"error\":\"" << jsonEscape(error) << "\"";
        json << ",\"limitations\":\"Each cycle recreates pipeline/cache objects, performs bounded memory churn and a fenced empty submit. It is a reliability probe, not a long game-session simulation or thermal endurance guarantee.\"}";
        return json.str();
    }

    VulkanContext &context;
};

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_amaral_driverlab_RunnerActivity_runNativePhase2Workload(
        JNIEnv *environment,
        jclass,
        jstring workloadId,
        jstring driverDirectory,
        jstring driverName,
        jstring nativeLibraryDirectory,
        jstring temporaryDirectory,
        jint warmupSeconds,
        jint measureSeconds) {
    const std::string workload = UtfString(environment, workloadId).string();
    VulkanContext context;
    try {
        context.initialize(UtfString(environment, driverDirectory).string(),
                           UtfString(environment, driverName).string(),
                           UtfString(environment, nativeLibraryDirectory).string(),
                           UtfString(environment, temporaryDirectory).string());
        Phase2Workloads workloads(context);
        const std::string result = workloads.run(
                workload, static_cast<int>(warmupSeconds), static_cast<int>(measureSeconds));
        return environment->NewStringUTF(result.c_str());
    } catch (const std::exception &error) {
        const std::string result = context.failureJson(workload, error);
        return environment->NewStringUTF(result.c_str());
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_amaral_driverlab_RunnerActivity_runNativeTraceReplay(
        JNIEnv *environment,
        jclass,
        jstring traceId,
        jstring driverDirectory,
        jstring driverName,
        jstring nativeLibraryDirectory,
        jstring temporaryDirectory,
        jint warmupSeconds,
        jint measureSeconds,
        jstring rawOutputPath) {
    const std::string trace = UtfString(environment, traceId).string();
    VulkanContext context;
    try {
        context.initialize(UtfString(environment, driverDirectory).string(),
                           UtfString(environment, driverName).string(),
                           UtfString(environment, nativeLibraryDirectory).string(),
                           UtfString(environment, temporaryDirectory).string());
        Phase2Workloads workloads(context);
        const std::string result = workloads.runTrace(
                trace, static_cast<int>(warmupSeconds), static_cast<int>(measureSeconds),
                UtfString(environment, rawOutputPath).string());
        return environment->NewStringUTF(result.c_str());
    } catch (const std::exception &error) {
        const std::string result = context.failureJson(kTraceReplayId, error);
        return environment->NewStringUTF(result.c_str());
    }
}


extern "C" JNIEXPORT jstring JNICALL
Java_com_amaral_driverlab_DeepDiagnosticsRunnerActivity_runNativeDeepDiagnostics(
        JNIEnv *environment,
        jclass,
        jstring mode,
        jstring driverDirectory,
        jstring driverName,
        jstring nativeLibraryDirectory,
        jstring temporaryDirectory,
        jint cycles,
        jint memoryMiB) {
    const std::string diagnosticMode = UtfString(environment, mode).string();
    VulkanContext context;
    try {
        context.initialize(UtfString(environment, driverDirectory).string(),
                           UtfString(environment, driverName).string(),
                           UtfString(environment, nativeLibraryDirectory).string(),
                           UtfString(environment, temporaryDirectory).string());
        DeepDiagnostics diagnostics(context);
        const std::string result = diagnostics.run(
                diagnosticMode == "soak" ? "soak" : "full",
                static_cast<int>(cycles), static_cast<int>(memoryMiB));
        return environment->NewStringUTF(result.c_str());
    } catch (const std::exception &error) {
        const std::string result = context.failureJson("turnip_deep_diagnostics", error);
        return environment->NewStringUTF(result.c_str());
    }
}
