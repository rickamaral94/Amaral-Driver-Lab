package com.amaral.driverlab.driver

import kotlinx.serialization.Serializable

/**
 * `VkDriverId` values that matter here. The full enum is large; anything unrecognised keeps its
 * raw number so a report never silently loses it.
 */
/**
 * `VkDriverId` values, transcribed from `vulkan_core.h`.
 *
 * These are checked against the header by a test rather than trusted, because an earlier
 * version of this file was written from memory and was wrong from `INTEL_OPEN_SOURCE_MESA`
 * onwards. The consequences were not cosmetic: Qualcomm's blob was recorded as 9, which is
 * Arm, so the check that refuses a Turnip request answered by the system driver — the whole
 * of P1 — was comparing against the wrong number. Turnip itself was 15, which is CoreAVI,
 * so a real Turnip would never have been recognised as one.
 */
public object VkDriverId {
    public const val AMD_PROPRIETARY: Int = 1
    public const val AMD_OPEN_SOURCE: Int = 2
    public const val MESA_RADV: Int = 3
    public const val NVIDIA_PROPRIETARY: Int = 4
    public const val INTEL_PROPRIETARY_WINDOWS: Int = 5
    public const val INTEL_OPEN_SOURCE_MESA: Int = 6
    public const val IMAGINATION_PROPRIETARY: Int = 7
    public const val QUALCOMM_PROPRIETARY: Int = 8
    public const val ARM_PROPRIETARY: Int = 9
    public const val GOOGLE_SWIFTSHADER: Int = 10
    public const val GGP_PROPRIETARY: Int = 11
    public const val BROADCOM_PROPRIETARY: Int = 12
    public const val MESA_LLVMPIPE: Int = 13
    public const val MOLTENVK: Int = 14
    public const val COREAVI_PROPRIETARY: Int = 15
    public const val JUICE_PROPRIETARY: Int = 16
    public const val VERISILICON_PROPRIETARY: Int = 17
    public const val MESA_TURNIP: Int = 18
    public const val MESA_V3DV: Int = 19
    public const val MESA_PANVK: Int = 20
    public const val SAMSUNG_PROPRIETARY: Int = 21
    public const val MESA_VENUS: Int = 22
    public const val MESA_DOZEN: Int = 23
    public const val MESA_NVK: Int = 24
    public const val IMAGINATION_OPEN_SOURCE_MESA: Int = 25
    public const val MESA_AGXV: Int = 26

    public fun describe(driverId: Int): String = when (driverId) {
        AMD_PROPRIETARY -> "AMD proprietary"
        AMD_OPEN_SOURCE -> "AMD open source"
        MESA_RADV -> "Mesa RADV"
        NVIDIA_PROPRIETARY -> "NVIDIA proprietary"
        INTEL_PROPRIETARY_WINDOWS -> "Intel proprietary"
        INTEL_OPEN_SOURCE_MESA -> "Mesa Intel"
        IMAGINATION_PROPRIETARY -> "Imagination proprietary"
        QUALCOMM_PROPRIETARY -> "Qualcomm proprietary"
        ARM_PROPRIETARY -> "Arm proprietary"
        GOOGLE_SWIFTSHADER -> "Google SwiftShader"
        GGP_PROPRIETARY -> "GGP proprietary"
        BROADCOM_PROPRIETARY -> "Broadcom proprietary"
        MESA_LLVMPIPE -> "Mesa llvmpipe"
        MOLTENVK -> "MoltenVK"
        COREAVI_PROPRIETARY -> "CoreAVI proprietary"
        JUICE_PROPRIETARY -> "Juice proprietary"
        VERISILICON_PROPRIETARY -> "VeriSilicon proprietary"
        MESA_TURNIP -> "Mesa Turnip"
        MESA_V3DV -> "Mesa V3DV"
        MESA_PANVK -> "Mesa PanVK"
        SAMSUNG_PROPRIETARY -> "Samsung proprietary"
        MESA_VENUS -> "Mesa Venus"
        MESA_DOZEN -> "Mesa Dozen"
        MESA_NVK -> "Mesa NVK"
        IMAGINATION_OPEN_SOURCE_MESA -> "Mesa Imagination"
        MESA_AGXV -> "Mesa AGXV"
        else -> "driverID $driverId"
    }

    /** Mesa-family drivers, which is what a Turnip package is expected to produce. */
    public fun isMesa(driverId: Int): Boolean = driverId in setOf(
        MESA_RADV, INTEL_OPEN_SOURCE_MESA, MESA_LLVMPIPE, MESA_TURNIP, MESA_V3DV,
        MESA_PANVK, MESA_VENUS, MESA_DOZEN, MESA_NVK, IMAGINATION_OPEN_SOURCE_MESA, MESA_AGXV,
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
