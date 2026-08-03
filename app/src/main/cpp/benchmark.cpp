#include <jni.h>

#include <android/log.h>
#include <adrenotools/driver.h>
#include <dlfcn.h>
#include <vulkan/vulkan.h>

#include <algorithm>
#include <chrono>
#include <cstdint>
#include <iomanip>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>

namespace {

constexpr char kLogTag[] = "AmaralDriverLab";
constexpr VkDeviceSize kPreferredBufferSize = 32ULL * 1024ULL * 1024ULL;
constexpr uint32_t kCopyOperationsPerSubmit = 32;

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
    if (!path.empty() && path.back() != '/') {
        path.push_back('/');
    }
    return path;
}

class UtfString {
public:
    UtfString(JNIEnv *environment, jstring source) : env(environment), value(source) {
        chars = value == nullptr ? nullptr : env->GetStringUTFChars(value, nullptr);
    }

    ~UtfString() {
        if (chars != nullptr) {
            env->ReleaseStringUTFChars(value, chars);
        }
    }

    std::string string() const { return chars == nullptr ? std::string() : std::string(chars); }

private:
    JNIEnv *env;
    jstring value;
    const char *chars = nullptr;
};

template <typename Function>
Function requireSymbol(void *library, const char *name) {
    auto function = reinterpret_cast<Function>(dlsym(library, name));
    if (function == nullptr) {
        throw std::runtime_error(std::string("Símbolo Vulkan ausente: ") + name);
    }
    return function;
}

template <typename Function>
Function requireInstance(PFN_vkGetInstanceProcAddr getter, VkInstance instance, const char *name) {
    auto function = reinterpret_cast<Function>(getter(instance, name));
    if (function == nullptr) {
        throw std::runtime_error(std::string("Função de instância ausente: ") + name);
    }
    return function;
}

template <typename Function>
Function requireDevice(PFN_vkGetDeviceProcAddr getter, VkDevice device, const char *name) {
    auto function = reinterpret_cast<Function>(getter(device, name));
    if (function == nullptr) {
        throw std::runtime_error(std::string("Função de dispositivo ausente: ") + name);
    }
    return function;
}

void check(VkResult result, const char *operation) {
    if (result != VK_SUCCESS) {
        throw std::runtime_error(std::string(operation) + " falhou, VkResult="
                                 + std::to_string(static_cast<int>(result)));
    }
}

class VulkanBenchmark {
public:
    ~VulkanBenchmark() { cleanup(); }

    void initialize(const std::string &driverDirectory,
                    const std::string &driverName,
                    const std::string &nativeLibraryDirectory,
                    const std::string &temporaryDirectory) {
        customDriver = !driverDirectory.empty();
        if (customDriver) {
            if (driverName.empty()) {
                throw std::runtime_error("Nome da biblioteca customizada ausente");
            }
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
            if (library == nullptr) {
                library = dlopen("libvulkan.so.1", RTLD_NOW | RTLD_LOCAL);
            }
        }
        if (library == nullptr) {
            const char *message = dlerror();
            throw std::runtime_error(std::string("Falha ao abrir Vulkan: ")
                                     + (message == nullptr ? "erro desconhecido" : message));
        }

        getInstanceProcAddr = requireSymbol<PFN_vkGetInstanceProcAddr>(library, "vkGetInstanceProcAddr");
        createInstance = requireSymbol<PFN_vkCreateInstance>(library, "vkCreateInstance");

        VkApplicationInfo applicationInfo{};
        applicationInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
        applicationInfo.pApplicationName = "Amaral Driver Lab";
        applicationInfo.applicationVersion = VK_MAKE_VERSION(0, 1, 0);
        applicationInfo.pEngineName = "Vulkan transfer stress";
        applicationInfo.engineVersion = VK_MAKE_VERSION(0, 1, 0);
        applicationInfo.apiVersion = VK_API_VERSION_1_0;

        VkInstanceCreateInfo instanceInfo{};
        instanceInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
        instanceInfo.pApplicationInfo = &applicationInfo;
        check(createInstance(&instanceInfo, nullptr, &instance), "vkCreateInstance");

        destroyInstance = requireInstance<PFN_vkDestroyInstance>(
                getInstanceProcAddr, instance, "vkDestroyInstance");
        enumeratePhysicalDevices = requireInstance<PFN_vkEnumeratePhysicalDevices>(
                getInstanceProcAddr, instance, "vkEnumeratePhysicalDevices");
        getPhysicalDeviceProperties = requireInstance<PFN_vkGetPhysicalDeviceProperties>(
                getInstanceProcAddr, instance, "vkGetPhysicalDeviceProperties");
        getPhysicalDeviceQueueFamilyProperties =
                requireInstance<PFN_vkGetPhysicalDeviceQueueFamilyProperties>(
                        getInstanceProcAddr, instance, "vkGetPhysicalDeviceQueueFamilyProperties");
        getPhysicalDeviceMemoryProperties = requireInstance<PFN_vkGetPhysicalDeviceMemoryProperties>(
                getInstanceProcAddr, instance, "vkGetPhysicalDeviceMemoryProperties");
        createDevice = requireInstance<PFN_vkCreateDevice>(
                getInstanceProcAddr, instance, "vkCreateDevice");
        getDeviceProcAddr = requireInstance<PFN_vkGetDeviceProcAddr>(
                getInstanceProcAddr, instance, "vkGetDeviceProcAddr");

        uint32_t deviceCount = 0;
        check(enumeratePhysicalDevices(instance, &deviceCount, nullptr),
              "vkEnumeratePhysicalDevices(contagem)");
        if (deviceCount == 0) {
            throw std::runtime_error("Driver não enumerou nenhuma GPU Vulkan");
        }
        std::vector<VkPhysicalDevice> physicalDevices(deviceCount);
        check(enumeratePhysicalDevices(instance, &deviceCount, physicalDevices.data()),
              "vkEnumeratePhysicalDevices(lista)");
        physicalDevice = physicalDevices.front();
        getPhysicalDeviceProperties(physicalDevice, &properties);
        getPhysicalDeviceMemoryProperties(physicalDevice, &memoryProperties);

        uint32_t familyCount = 0;
        getPhysicalDeviceQueueFamilyProperties(physicalDevice, &familyCount, nullptr);
        std::vector<VkQueueFamilyProperties> families(familyCount);
        getPhysicalDeviceQueueFamilyProperties(physicalDevice, &familyCount, families.data());
        bool foundQueue = false;
        for (uint32_t index = 0; index < familyCount; ++index) {
            if (families[index].queueCount > 0
                    && (families[index].queueFlags & VK_QUEUE_TRANSFER_BIT) != 0) {
                queueFamilyIndex = index;
                queueFamilyProperties = families[index];
                foundQueue = true;
                if ((families[index].queueFlags & VK_QUEUE_GRAPHICS_BIT) != 0) {
                    break;
                }
            }
        }
        if (!foundQueue) {
            throw std::runtime_error("GPU não expõe uma fila Vulkan de transferência");
        }

        float priority = 1.0f;
        VkDeviceQueueCreateInfo queueInfo{};
        queueInfo.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
        queueInfo.queueFamilyIndex = queueFamilyIndex;
        queueInfo.queueCount = 1;
        queueInfo.pQueuePriorities = &priority;

        VkDeviceCreateInfo deviceInfo{};
        deviceInfo.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
        deviceInfo.queueCreateInfoCount = 1;
        deviceInfo.pQueueCreateInfos = &queueInfo;
        check(createDevice(physicalDevice, &deviceInfo, nullptr, &device), "vkCreateDevice");
        loadDeviceFunctions();
        getDeviceQueue(device, queueFamilyIndex, 0, &queue);

        VkDeviceSize requestedSize = kPreferredBufferSize;
        bool allocated = false;
        for (int attempt = 0; attempt < 3 && !allocated; ++attempt) {
            try {
                createBufferPair(requestedSize);
                allocated = true;
            } catch (const std::exception &) {
                destroyBufferPair();
                requestedSize /= 2;
            }
        }
        if (!allocated) {
            throw std::runtime_error("Não foi possível alocar buffers Vulkan de 8–32 MiB");
        }

        VkCommandPoolCreateInfo poolInfo{};
        poolInfo.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
        poolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
        poolInfo.queueFamilyIndex = queueFamilyIndex;
        check(createCommandPool(device, &poolInfo, nullptr, &commandPool), "vkCreateCommandPool");

        VkCommandBufferAllocateInfo allocateInfo{};
        allocateInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
        allocateInfo.commandPool = commandPool;
        allocateInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        allocateInfo.commandBufferCount = 1;
        check(allocateCommandBuffers(device, &allocateInfo, &commandBuffer),
              "vkAllocateCommandBuffers");

        timestampsSupported = queueFamilyProperties.timestampValidBits > 0;
        if (timestampsSupported) {
            VkQueryPoolCreateInfo queryInfo{};
            queryInfo.sType = VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO;
            queryInfo.queryType = VK_QUERY_TYPE_TIMESTAMP;
            queryInfo.queryCount = 2;
            VkResult queryResult = createQueryPool(device, &queryInfo, nullptr, &queryPool);
            if (queryResult != VK_SUCCESS) {
                timestampsSupported = false;
                queryPool = VK_NULL_HANDLE;
            }
        }
        recordCommands();
    }

    std::string run(int warmupSeconds, int measureSeconds) {
        warmupSeconds = std::clamp(warmupSeconds, 0, 30);
        measureSeconds = std::clamp(measureSeconds, 1, 120);

        uint64_t warmupSubmits = 0;
        const auto warmupDeadline = std::chrono::steady_clock::now()
                                    + std::chrono::seconds(warmupSeconds);
        while (std::chrono::steady_clock::now() < warmupDeadline) {
            submitOnce(nullptr);
            ++warmupSubmits;
        }

        const auto start = std::chrono::steady_clock::now();
        const auto deadline = start + std::chrono::seconds(measureSeconds);
        uint64_t submits = 0;
        uint64_t totalTimestampTicks = 0;
        do {
            uint64_t ticks = 0;
            submitOnce(timestampsSupported ? &ticks : nullptr);
            totalTimestampTicks += ticks;
            ++submits;
        } while (std::chrono::steady_clock::now() < deadline);
        const auto finish = std::chrono::steady_clock::now();

        const double wallSeconds = std::chrono::duration<double>(finish - start).count();
        const long double payloadBytes = static_cast<long double>(submits)
                * static_cast<long double>(bufferSize)
                * static_cast<long double>(kCopyOperationsPerSubmit + 1U);
        const double gpuSeconds = timestampsSupported
                ? static_cast<double>(static_cast<long double>(totalTimestampTicks)
                                      * properties.limits.timestampPeriod / 1.0e9L)
                : 0.0;
        const double timingSeconds = gpuSeconds > 0.0 ? gpuSeconds : wallSeconds;
        const double gibPerSecond = timingSeconds > 0.0
                ? static_cast<double>(payloadBytes / (1024.0L * 1024.0L * 1024.0L)
                                      / timingSeconds)
                : 0.0;

        std::ostringstream json;
        json << std::fixed << std::setprecision(6)
             << "{\"success\":true"
             << ",\"workload\":\"vulkan_transfer_stress_v1\""
             << ",\"custom_driver\":" << (customDriver ? "true" : "false")
             << ",\"gpu_name\":\"" << jsonEscape(properties.deviceName) << "\""
             << ",\"vendor_id\":" << properties.vendorID
             << ",\"device_id\":" << properties.deviceID
             << ",\"device_type\":" << properties.deviceType
             << ",\"vulkan_api_raw\":" << properties.apiVersion
             << ",\"vulkan_api\":\"" << VK_VERSION_MAJOR(properties.apiVersion) << "."
             << VK_VERSION_MINOR(properties.apiVersion) << "."
             << VK_VERSION_PATCH(properties.apiVersion) << "\""
             << ",\"driver_version_raw\":" << properties.driverVersion
             << ",\"driver_version_decoded\":\"" << VK_VERSION_MAJOR(properties.driverVersion)
             << "." << VK_VERSION_MINOR(properties.driverVersion)
             << "." << VK_VERSION_PATCH(properties.driverVersion) << "\""
             << ",\"queue_family_index\":" << queueFamilyIndex
             << ",\"queue_flags\":" << queueFamilyProperties.queueFlags
             << ",\"timestamp_valid_bits\":" << queueFamilyProperties.timestampValidBits
             << ",\"timestamp_period_ns\":" << properties.limits.timestampPeriod
             << ",\"gpu_timestamps_used\":" << (timestampsSupported ? "true" : "false")
             << ",\"buffer_size_bytes\":" << bufferSize
             << ",\"copy_operations_per_submit\":" << kCopyOperationsPerSubmit
             << ",\"warmup_submits\":" << warmupSubmits
             << ",\"measured_submits\":" << submits
             << ",\"logical_payload_bytes\":" << static_cast<unsigned long long>(payloadBytes)
             << ",\"wall_elapsed_seconds\":" << wallSeconds
             << ",\"gpu_elapsed_seconds\":" << gpuSeconds
             << ",\"transfer_payload_gib_s\":" << gibPerSecond
             << ",\"metric_note\":\"Carga sintética fill/copy; não equivale à largura de banda física da VRAM\""
             << "}";
        return json.str();
    }

private:
    void loadDeviceFunctions() {
        destroyDevice = requireDevice<PFN_vkDestroyDevice>(getDeviceProcAddr, device, "vkDestroyDevice");
        getDeviceQueue = requireDevice<PFN_vkGetDeviceQueue>(getDeviceProcAddr, device, "vkGetDeviceQueue");
        createBuffer = requireDevice<PFN_vkCreateBuffer>(getDeviceProcAddr, device, "vkCreateBuffer");
        destroyBuffer = requireDevice<PFN_vkDestroyBuffer>(getDeviceProcAddr, device, "vkDestroyBuffer");
        getBufferMemoryRequirements = requireDevice<PFN_vkGetBufferMemoryRequirements>(
                getDeviceProcAddr, device, "vkGetBufferMemoryRequirements");
        allocateMemory = requireDevice<PFN_vkAllocateMemory>(getDeviceProcAddr, device, "vkAllocateMemory");
        freeMemory = requireDevice<PFN_vkFreeMemory>(getDeviceProcAddr, device, "vkFreeMemory");
        bindBufferMemory = requireDevice<PFN_vkBindBufferMemory>(
                getDeviceProcAddr, device, "vkBindBufferMemory");
        createCommandPool = requireDevice<PFN_vkCreateCommandPool>(
                getDeviceProcAddr, device, "vkCreateCommandPool");
        destroyCommandPool = requireDevice<PFN_vkDestroyCommandPool>(
                getDeviceProcAddr, device, "vkDestroyCommandPool");
        allocateCommandBuffers = requireDevice<PFN_vkAllocateCommandBuffers>(
                getDeviceProcAddr, device, "vkAllocateCommandBuffers");
        beginCommandBuffer = requireDevice<PFN_vkBeginCommandBuffer>(
                getDeviceProcAddr, device, "vkBeginCommandBuffer");
        endCommandBuffer = requireDevice<PFN_vkEndCommandBuffer>(
                getDeviceProcAddr, device, "vkEndCommandBuffer");
        cmdFillBuffer = requireDevice<PFN_vkCmdFillBuffer>(
                getDeviceProcAddr, device, "vkCmdFillBuffer");
        cmdCopyBuffer = requireDevice<PFN_vkCmdCopyBuffer>(
                getDeviceProcAddr, device, "vkCmdCopyBuffer");
        cmdPipelineBarrier = requireDevice<PFN_vkCmdPipelineBarrier>(
                getDeviceProcAddr, device, "vkCmdPipelineBarrier");
        createQueryPool = requireDevice<PFN_vkCreateQueryPool>(
                getDeviceProcAddr, device, "vkCreateQueryPool");
        destroyQueryPool = requireDevice<PFN_vkDestroyQueryPool>(
                getDeviceProcAddr, device, "vkDestroyQueryPool");
        cmdResetQueryPool = requireDevice<PFN_vkCmdResetQueryPool>(
                getDeviceProcAddr, device, "vkCmdResetQueryPool");
        cmdWriteTimestamp = requireDevice<PFN_vkCmdWriteTimestamp>(
                getDeviceProcAddr, device, "vkCmdWriteTimestamp");
        getQueryPoolResults = requireDevice<PFN_vkGetQueryPoolResults>(
                getDeviceProcAddr, device, "vkGetQueryPoolResults");
        queueSubmit = requireDevice<PFN_vkQueueSubmit>(getDeviceProcAddr, device, "vkQueueSubmit");
        queueWaitIdle = requireDevice<PFN_vkQueueWaitIdle>(
                getDeviceProcAddr, device, "vkQueueWaitIdle");
    }

    uint32_t chooseMemoryType(uint32_t allowedTypes, VkMemoryPropertyFlags preferred) const {
        for (uint32_t index = 0; index < memoryProperties.memoryTypeCount; ++index) {
            if ((allowedTypes & (1U << index)) != 0
                    && (memoryProperties.memoryTypes[index].propertyFlags & preferred) == preferred) {
                return index;
            }
        }
        for (uint32_t index = 0; index < memoryProperties.memoryTypeCount; ++index) {
            if ((allowedTypes & (1U << index)) != 0) {
                return index;
            }
        }
        throw std::runtime_error("Nenhum tipo de memória Vulkan compatível");
    }

    void createOneBuffer(VkDeviceSize size, VkBuffer *buffer, VkDeviceMemory *memory) {
        VkBufferCreateInfo bufferInfo{};
        bufferInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
        bufferInfo.size = size;
        bufferInfo.usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        bufferInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        check(createBuffer(device, &bufferInfo, nullptr, buffer), "vkCreateBuffer");

        VkMemoryRequirements requirements{};
        getBufferMemoryRequirements(device, *buffer, &requirements);
        VkMemoryAllocateInfo allocation{};
        allocation.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        allocation.allocationSize = requirements.size;
        allocation.memoryTypeIndex = chooseMemoryType(
                requirements.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        check(allocateMemory(device, &allocation, nullptr, memory), "vkAllocateMemory");
        check(bindBufferMemory(device, *buffer, *memory, 0), "vkBindBufferMemory");
    }

    void createBufferPair(VkDeviceSize size) {
        createOneBuffer(size, &bufferA, &memoryA);
        createOneBuffer(size, &bufferB, &memoryB);
        bufferSize = size;
    }

    void destroyBufferPair() {
        if (device != VK_NULL_HANDLE && destroyBuffer != nullptr) {
            if (bufferB != VK_NULL_HANDLE) destroyBuffer(device, bufferB, nullptr);
            if (bufferA != VK_NULL_HANDLE) destroyBuffer(device, bufferA, nullptr);
        }
        bufferA = VK_NULL_HANDLE;
        bufferB = VK_NULL_HANDLE;
        if (device != VK_NULL_HANDLE && freeMemory != nullptr) {
            if (memoryB != VK_NULL_HANDLE) freeMemory(device, memoryB, nullptr);
            if (memoryA != VK_NULL_HANDLE) freeMemory(device, memoryA, nullptr);
        }
        memoryA = VK_NULL_HANDLE;
        memoryB = VK_NULL_HANDLE;
    }

    void transferBarrier(VkBuffer buffer) {
        VkBufferMemoryBarrier barrier{};
        barrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        barrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        barrier.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT;
        barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.buffer = buffer;
        barrier.offset = 0;
        barrier.size = bufferSize;
        cmdPipelineBarrier(
                commandBuffer,
                VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT,
                0,
                0, nullptr,
                1, &barrier,
                0, nullptr);
    }

    void recordCommands() {
        VkCommandBufferBeginInfo beginInfo{};
        beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
        beginInfo.flags = VK_COMMAND_BUFFER_USAGE_SIMULTANEOUS_USE_BIT;
        check(beginCommandBuffer(commandBuffer, &beginInfo), "vkBeginCommandBuffer");
        if (timestampsSupported) {
            cmdResetQueryPool(commandBuffer, queryPool, 0, 2);
            cmdWriteTimestamp(commandBuffer, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, queryPool, 0);
        }
        cmdFillBuffer(commandBuffer, bufferA, 0, bufferSize, 0x5a17c0deU);
        transferBarrier(bufferA);
        VkBufferCopy region{};
        region.size = bufferSize;
        for (uint32_t operation = 0; operation < kCopyOperationsPerSubmit; ++operation) {
            VkBuffer source = (operation % 2U == 0U) ? bufferA : bufferB;
            VkBuffer destination = (operation % 2U == 0U) ? bufferB : bufferA;
            cmdCopyBuffer(commandBuffer, source, destination, 1, &region);
            transferBarrier(destination);
        }
        if (timestampsSupported) {
            cmdWriteTimestamp(commandBuffer, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, queryPool, 1);
        }
        check(endCommandBuffer(commandBuffer), "vkEndCommandBuffer");
    }

    void submitOnce(uint64_t *timestampTicks) {
        VkSubmitInfo submitInfo{};
        submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        submitInfo.commandBufferCount = 1;
        submitInfo.pCommandBuffers = &commandBuffer;
        check(queueSubmit(queue, 1, &submitInfo, VK_NULL_HANDLE), "vkQueueSubmit");
        check(queueWaitIdle(queue), "vkQueueWaitIdle");
        if (timestampTicks != nullptr && timestampsSupported) {
            uint64_t values[2] = {0, 0};
            check(getQueryPoolResults(
                          device, queryPool, 0, 2, sizeof(values), values, sizeof(uint64_t),
                          VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WAIT_BIT),
                  "vkGetQueryPoolResults");
            const uint32_t validBits = queueFamilyProperties.timestampValidBits;
            if (validBits >= 64) {
                *timestampTicks = values[1] - values[0];
            } else {
                const uint64_t mask = (1ULL << validBits) - 1ULL;
                *timestampTicks = (values[1] - values[0]) & mask;
            }
        }
    }

    void cleanup() {
        if (device != VK_NULL_HANDLE && queue != VK_NULL_HANDLE && queueWaitIdle != nullptr) {
            queueWaitIdle(queue);
        }
        if (device != VK_NULL_HANDLE && queryPool != VK_NULL_HANDLE && destroyQueryPool != nullptr) {
            destroyQueryPool(device, queryPool, nullptr);
        }
        if (device != VK_NULL_HANDLE && commandPool != VK_NULL_HANDLE
                && destroyCommandPool != nullptr) {
            destroyCommandPool(device, commandPool, nullptr);
        }
        destroyBufferPair();
        if (device != VK_NULL_HANDLE && destroyDevice != nullptr) {
            destroyDevice(device, nullptr);
        }
        device = VK_NULL_HANDLE;
        if (instance != VK_NULL_HANDLE && destroyInstance != nullptr) {
            destroyInstance(instance, nullptr);
        }
        instance = VK_NULL_HANDLE;
        // libadrenotools and its hooks live until this short-lived process exits.
        library = nullptr;
    }

    bool customDriver = false;
    bool timestampsSupported = false;
    void *library = nullptr;
    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    VkBuffer bufferA = VK_NULL_HANDLE;
    VkBuffer bufferB = VK_NULL_HANDLE;
    VkDeviceMemory memoryA = VK_NULL_HANDLE;
    VkDeviceMemory memoryB = VK_NULL_HANDLE;
    VkCommandPool commandPool = VK_NULL_HANDLE;
    VkCommandBuffer commandBuffer = VK_NULL_HANDLE;
    VkQueryPool queryPool = VK_NULL_HANDLE;
    VkDeviceSize bufferSize = 0;
    uint32_t queueFamilyIndex = 0;
    VkPhysicalDeviceProperties properties{};
    VkPhysicalDeviceMemoryProperties memoryProperties{};
    VkQueueFamilyProperties queueFamilyProperties{};

    PFN_vkGetInstanceProcAddr getInstanceProcAddr = nullptr;
    PFN_vkGetDeviceProcAddr getDeviceProcAddr = nullptr;
    PFN_vkCreateInstance createInstance = nullptr;
    PFN_vkDestroyInstance destroyInstance = nullptr;
    PFN_vkEnumeratePhysicalDevices enumeratePhysicalDevices = nullptr;
    PFN_vkGetPhysicalDeviceProperties getPhysicalDeviceProperties = nullptr;
    PFN_vkGetPhysicalDeviceQueueFamilyProperties getPhysicalDeviceQueueFamilyProperties = nullptr;
    PFN_vkGetPhysicalDeviceMemoryProperties getPhysicalDeviceMemoryProperties = nullptr;
    PFN_vkCreateDevice createDevice = nullptr;
    PFN_vkDestroyDevice destroyDevice = nullptr;
    PFN_vkGetDeviceQueue getDeviceQueue = nullptr;
    PFN_vkCreateBuffer createBuffer = nullptr;
    PFN_vkDestroyBuffer destroyBuffer = nullptr;
    PFN_vkGetBufferMemoryRequirements getBufferMemoryRequirements = nullptr;
    PFN_vkAllocateMemory allocateMemory = nullptr;
    PFN_vkFreeMemory freeMemory = nullptr;
    PFN_vkBindBufferMemory bindBufferMemory = nullptr;
    PFN_vkCreateCommandPool createCommandPool = nullptr;
    PFN_vkDestroyCommandPool destroyCommandPool = nullptr;
    PFN_vkAllocateCommandBuffers allocateCommandBuffers = nullptr;
    PFN_vkBeginCommandBuffer beginCommandBuffer = nullptr;
    PFN_vkEndCommandBuffer endCommandBuffer = nullptr;
    PFN_vkCmdFillBuffer cmdFillBuffer = nullptr;
    PFN_vkCmdCopyBuffer cmdCopyBuffer = nullptr;
    PFN_vkCmdPipelineBarrier cmdPipelineBarrier = nullptr;
    PFN_vkCreateQueryPool createQueryPool = nullptr;
    PFN_vkDestroyQueryPool destroyQueryPool = nullptr;
    PFN_vkCmdResetQueryPool cmdResetQueryPool = nullptr;
    PFN_vkCmdWriteTimestamp cmdWriteTimestamp = nullptr;
    PFN_vkGetQueryPoolResults getQueryPoolResults = nullptr;
    PFN_vkQueueSubmit queueSubmit = nullptr;
    PFN_vkQueueWaitIdle queueWaitIdle = nullptr;
};

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_amaral_driverlab_RunnerActivity_runNativeBenchmark(
        JNIEnv *env,
        jclass,
        jstring driverDirectory,
        jstring driverName,
        jstring nativeLibraryDirectory,
        jstring temporaryDirectory,
        jint warmupSeconds,
        jint measureSeconds) {
    try {
        UtfString driverDirectoryString(env, driverDirectory);
        UtfString driverNameString(env, driverName);
        UtfString nativeLibraryDirectoryString(env, nativeLibraryDirectory);
        UtfString temporaryDirectoryString(env, temporaryDirectory);
        VulkanBenchmark benchmark;
        benchmark.initialize(
                driverDirectoryString.string(),
                driverNameString.string(),
                nativeLibraryDirectoryString.string(),
                temporaryDirectoryString.string());
        const std::string result = benchmark.run(warmupSeconds, measureSeconds);
        __android_log_print(ANDROID_LOG_INFO, kLogTag, "Benchmark concluído");
        return env->NewStringUTF(result.c_str());
    } catch (const std::exception &error) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "%s", error.what());
        const std::string result = std::string("{\"success\":false,\"error\":\"")
                                   + jsonEscape(error.what()) + "\"}";
        return env->NewStringUTF(result.c_str());
    } catch (...) {
        const char *result = "{\"success\":false,\"error\":\"Falha nativa desconhecida\"}";
        return env->NewStringUTF(result);
    }
}
