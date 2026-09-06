package com.amaral.driverlab.driver

import kotlinx.serialization.Serializable

/**
 * `VkDriverId` values that matter here. The full enum is large; anything unrecognised keeps its
 * raw number so a report never silently loses it.
 */
public object VkDriverId {
    public const val AMD_PROPRIETARY: Int = 1
    public const val MESA_RADV: Int = 3
    public const val NVIDIA_PROPRIETARY: Int = 4
    public const val INTEL_OPEN_SOURCE_MESA: Int = 7
    public const val IMAGINATION_PROPRIETARY: Int = 8
    public const val QUALCOMM_PROPRIETARY: Int = 9
    public const val ARM_PROPRIETARY: Int = 10
    public const val BROADCOM_PROPRIETARY: Int = 12
    public const val MESA_LLVMPIPE: Int = 13
    public const val MESA_TURNIP: Int = 15
    public const val MESA_PANVK: Int = 18
    public const val MESA_VENUS: Int = 20
    public const val MESA_DOZEN: Int = 21
    public const val MESA_NVK: Int = 22
    public const val MESA_HONEYKRISP: Int = 24

    public fun describe(driverId: Int): String = when (driverId) {
        MESA_TURNIP -> "Mesa Turnip"
        QUALCOMM_PROPRIETARY -> "Qualcomm proprietary"
        ARM_PROPRIETARY -> "Arm proprietary"
        IMAGINATION_PROPRIETARY -> "Imagination proprietary"
        MESA_RADV -> "Mesa RADV"
        MESA_LLVMPIPE -> "Mesa llvmpipe"
        MESA_PANVK -> "Mesa PanVK"
        MESA_VENUS -> "Mesa Venus"
        INTEL_OPEN_SOURCE_MESA -> "Mesa Intel"
        AMD_PROPRIETARY -> "AMD proprietary"
        NVIDIA_PROPRIETARY -> "NVIDIA proprietary"
        BROADCOM_PROPRIETARY -> "Broadcom proprietary"
        MESA_DOZEN -> "Mesa Dozen"
        MESA_NVK -> "Mesa NVK"
        MESA_HONEYKRISP -> "Mesa Honeykrisp"
        else -> "driverID $driverId"
    }

    /** Mesa-family drivers, which is what a Turnip package is expected to produce. */
    public fun isMesa(driverId: Int): Boolean = driverId in setOf(
        MESA_RADV, INTEL_OPEN_SOURCE_MESA, MESA_LLVMPIPE, MESA_TURNIP,
        MESA_PANVK, MESA_VENUS, MESA_DOZEN, MESA_NVK, MESA_HONEYKRISP,
    )
}

@Serializable
public data class ConformanceVersion(
    val major: Int,
    val minor: Int,
    val subminor: Int,
    val patch: Int,
) {
    override fun toString(): String = "$major.$minor.$subminor.$patch"
}

/**
 * What the loaded ICD said about itself, read back from Vulkan after the driver is in the process.
 *
 * This, not `meta.json`, is what a result is labelled with. The whole of section 5 reduces to:
 * if these fields cannot be collected, the run does not happen.
 */
@Serializable
public data class DriverIdentity(
    /** SHA-256 of the `.so` handed to the loader. Empty for the system driver. */
    val libraryChecksum: String,
    val driverId: Int,
    val driverName: String,
    val driverInfo: String,
    val conformanceVersion: ConformanceVersion?,
    val deviceName: String,
    val vendorId: Int,
    val deviceId: Int,
    val apiVersion: Int,
    val driverVersion: Int,
    val instanceExtensions: List<String>,
    val deviceExtensions: List<String>,
    val source: DriverSource,
) {
    public val isTurnip: Boolean get() = driverId == VkDriverId.MESA_TURNIP
    public val isMesa: Boolean get() = VkDriverId.isMesa(driverId)
    public val isQualcommProprietary: Boolean get() = driverId == VkDriverId.QUALCOMM_PROPRIETARY

    public fun apiVersionString(): String =
        "${apiVersion shr 22}.${(apiVersion shr 12) and 0x3FF}.${apiVersion and 0xFFF}"

    /**
     * Mesa packs its version the same way Vulkan packs an API version, so a Turnip build reads
     * back as e.g. 25.1.0. Other vendors use the field however they like, hence the raw value too.
     */
    public fun driverVersionString(): String = if (isMesa) {
        "${driverVersion shr 22}.${(driverVersion shr 12) and 0x3FF}.${driverVersion and 0xFFF}"
    } else {
        driverVersion.toString()
    }

    public fun summary(): String = buildString {
        append(VkDriverId.describe(driverId))
        if (driverName.isNotBlank()) append(" · ").append(driverName)
        append(" · ").append(deviceName)
        append(" · Vulkan ").append(apiVersionString())
    }
}

/** Where the ICD under test came from. */
@Serializable
public enum class DriverSource {
    /** The Android system's own Vulkan driver. Usable as a reference, never ranked. */
    SYSTEM,

    /** A package the user imported and the app loaded into the runner process. */
    IMPORTED_PACKAGE,
}
