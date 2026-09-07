package com.amaral.driverlab.driver

import java.io.File

/**
 * Loads an ICD and reports back what Vulkan says about it.
 *
 * Declared here and implemented in `:core-vk`, because the identity this returns is what
 * [IdentityGuard] judges, and the guard must not depend on the graphics stack to do its job.
 *
 * Every call happens in the isolated runner process. A driver that segfaults or loses the device
 * takes the runner down, and that is recorded as data rather than losing the session.
 */
public interface VulkanDriverLoader {
    public fun probe(request: LoadRequest): LoadOutcome
}

public data class LoadRequest(
    val source: DriverSource,
    /** Directory holding the unpacked package; null for [DriverSource.SYSTEM]. */
    val libraryDirectory: File?,
    /** File name of the ICD inside [libraryDirectory]; null for [DriverSource.SYSTEM]. */
    val libraryName: String?,
    /**
     * SHA-256 of that file, carried through so the identity that comes back names the
     * bytes that ran. Empty for [DriverSource.SYSTEM], which has no file to hash.
     */
    val libraryChecksum: String? = null,
    /**
     * The app's own `applicationInfo.nativeLibraryDir`.
     *
     * Not a scratch directory: the rootless hook creates a linker namespace over this path
     * and dlopens its hook libraries out of it, so it has to be where the APK's native
     * libraries were actually extracted. Getting it wrong does not fail loudly — the hook
     * silently gives up and the system driver answers instead, which is the exact failure
     * this project exists to prevent.
     */
    val nativeLibraryDirectory: File,

    /** Writable scratch directory. Unused above API 29, where the loader uses memfd. */
    val temporaryDirectory: File,
    /** Enables VK_LAYER_KHRONOS_validation. Diagnostic only — never set for a ranking run. */
    val enableValidationLayer: Boolean = false,
)

public sealed interface LoadOutcome {

    public data class Success(val identity: DriverIdentity) : LoadOutcome

    public data class Failure(val stage: Stage, val message: String) : LoadOutcome

    /** Where it went wrong, so the failure catalog can distinguish "did not load" from "crashed". */
    public enum class Stage {
        HOOK_UNAVAILABLE,
        DLOPEN_FAILED,
        INSTANCE_CREATION_FAILED,
        NO_PHYSICAL_DEVICE,
        DEVICE_CREATION_FAILED,
        IDENTITY_UNAVAILABLE,
    }
}
