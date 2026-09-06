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
    /** Writable scratch directory the loader may use for its own temporary files. */
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
