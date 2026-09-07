#include "icd_loader.h"

#include <dlfcn.h>

#if AMARAL_WITH_ADRENOTOOLS
#include <adrenotools/driver.h>
#endif

namespace amaral {

const char* describe(LoadStage stage) {
    switch (stage) {
        case LoadStage::HookUnavailable: return "HOOK_UNAVAILABLE";
        case LoadStage::DlopenFailed: return "DLOPEN_FAILED";
        case LoadStage::InstanceCreationFailed: return "INSTANCE_CREATION_FAILED";
        case LoadStage::NoPhysicalDevice: return "NO_PHYSICAL_DEVICE";
        case LoadStage::DeviceCreationFailed: return "DEVICE_CREATION_FAILED";
        case LoadStage::IdentityUnavailable: return "IDENTITY_UNAVAILABLE";
    }
    return "UNKNOWN";
}

namespace {

IcdLoadResult failure(LoadStage stage, std::string message) {
    IcdLoadResult result;
    result.ok = false;
    result.stage = stage;
    result.message = std::move(message);
    return result;
}

PFN_vkGetInstanceProcAddr resolveProcAddr(void* library) {
    if (library == nullptr) return nullptr;
    return reinterpret_cast<PFN_vkGetInstanceProcAddr>(dlsym(library, "vkGetInstanceProcAddr"));
}

#if AMARAL_WITH_ADRENOTOOLS
// adrenotools expects directory arguments to end in a separator.
std::string withTrailingSlash(const std::string& path) {
    if (path.empty() || path.back() == '/') return path;
    return path + "/";
}
#endif

}  // namespace

IcdLoadResult openSystemLoader() {
    void* library = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    if (library == nullptr) {
        const char* error = dlerror();
        return failure(LoadStage::DlopenFailed,
                       std::string("dlopen(libvulkan.so) failed: ") + (error ? error : "unknown"));
    }
    PFN_vkGetInstanceProcAddr proc = resolveProcAddr(library);
    if (proc == nullptr) {
        dlclose(library);
        return failure(LoadStage::DlopenFailed,
                       "libvulkan.so has no vkGetInstanceProcAddr");
    }

    IcdLoadResult result;
    result.ok = true;
    result.handle.library = library;
    result.handle.getInstanceProcAddr = proc;
    result.handle.usedAdrenotools = false;
    return result;
}

IcdLoadResult openPackagedDriver(const std::string& libraryDirectory,
                                 const std::string& libraryName,
                                 const std::string& nativeLibraryDirectory) {
#if AMARAL_WITH_ADRENOTOOLS
    // adrenotools_open_libvulkan returns a handle to the Android loader configured to pick
    // up the given ICD through a private linker namespace, which is what makes this work
    // without root.
    //
    // hookLibDir must be the app's nativeLibraryDir and nothing else: the library creates a
    // linker namespace over that path and dlopens libhook_impl.so and libmain_hook.so from
    // inside it. Its own header warns that a wrong path still returns a valid pointer and
    // then quietly falls back to the system driver — which is the one failure mode this
    // whole project is built around, so it is checked before the call rather than trusted.
    const std::string driverDirectory = withTrailingSlash(libraryDirectory);
    const std::string hookDirectory = withTrailingSlash(nativeLibraryDirectory);

    if (nativeLibraryDirectory.empty()) {
        return failure(LoadStage::HookUnavailable,
                       "the app did not supply its native library directory, so the rootless "
                       "hook could not be installed. Nothing was loaded.");
    }

    void* library = adrenotools_open_libvulkan(
            RTLD_NOW | RTLD_LOCAL,
            ADRENOTOOLS_DRIVER_CUSTOM,
            // tmpLibDir: only consulted below API 29, and minSdk here is 30, so the library
            // uses memfd and needs no writable scratch of its own.
            nullptr,
            hookDirectory.c_str(),      // hookLibDir  — where the APK's hooks were extracted
            driverDirectory.c_str(),    // customDriverDir — where the ICD lives
            libraryName.c_str(),        // customDriverName
            nullptr,                    // fileRedirectDir: feature not enabled
            nullptr);                   // userMappingHandle: feature not enabled

    if (library == nullptr) {
        const char* error = dlerror();
        return failure(LoadStage::DlopenFailed,
                       "adrenotools_open_libvulkan(\"" + libraryName + "\") failed: " +
                               (error ? error : "unknown"));
    }
    PFN_vkGetInstanceProcAddr proc = resolveProcAddr(library);
    if (proc == nullptr) {
        return failure(LoadStage::DlopenFailed,
                       "loaded \"" + libraryName + "\" but it has no vkGetInstanceProcAddr");
    }

    IcdLoadResult result;
    result.ok = true;
    result.handle.library = library;
    result.handle.getInstanceProcAddr = proc;
    result.handle.usedAdrenotools = true;
    return result;
#else
    (void)libraryDirectory;
    (void)nativeLibraryDirectory;
    // Deliberately a hard failure rather than a fallback to the system loader.
    // Falling back here is precisely how a benchmark ends up timing the Qualcomm
    // blob under a Turnip label.
    return failure(LoadStage::HookUnavailable,
                   "This build has no libadrenotools, so imported packages cannot be loaded "
                   "without root. \"" + libraryName + "\" was not loaded, and the system driver "
                   "was NOT used in its place. See docs/DRIVER_LOADING.md.");
#endif
}

void closeIcd(IcdHandle& handle) {
    // The adrenotools handle is the Android loader itself; closing it would unload
    // the loader from under any other Vulkan user in the process. The runner process
    // is discarded after every phase, so leaving it open is both correct and cheap.
    if (handle.library != nullptr && !handle.usedAdrenotools) {
        dlclose(handle.library);
    }
    handle.library = nullptr;
    handle.getInstanceProcAddr = nullptr;
}

}  // namespace amaral
