package com.amaral.driverlab.driver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins VkDriverId against the Vulkan specification.
 *
 * The values below are transcribed from `vulkan_core.h`. They are here because the first
 * version of [VkDriverId] was written from memory and was wrong from `INTEL_OPEN_SOURCE_MESA`
 * onwards — Qualcomm's blob was recorded as 9 (which is Arm) and Turnip as 15 (which is
 * CoreAVI). On a real device that produced "System driver · Imagination proprietary" for an
 * Adreno, and, far worse, meant the check that refuses a Turnip request answered by the
 * system driver was comparing against a number no driver reports.
 */
class VkDriverIdTest {

    private val fromTheSpecification = mapOf(
        "AMD_PROPRIETARY" to 1,
        "AMD_OPEN_SOURCE" to 2,
        "MESA_RADV" to 3,
        "NVIDIA_PROPRIETARY" to 4,
        "INTEL_PROPRIETARY_WINDOWS" to 5,
        "INTEL_OPEN_SOURCE_MESA" to 6,
        "IMAGINATION_PROPRIETARY" to 7,
        "QUALCOMM_PROPRIETARY" to 8,
        "ARM_PROPRIETARY" to 9,
        "GOOGLE_SWIFTSHADER" to 10,
        "GGP_PROPRIETARY" to 11,
        "BROADCOM_PROPRIETARY" to 12,
        "MESA_LLVMPIPE" to 13,
        "MOLTENVK" to 14,
        "COREAVI_PROPRIETARY" to 15,
        "JUICE_PROPRIETARY" to 16,
        "VERISILICON_PROPRIETARY" to 17,
        "MESA_TURNIP" to 18,
        "MESA_V3DV" to 19,
        "MESA_PANVK" to 20,
        "SAMSUNG_PROPRIETARY" to 21,
        "MESA_VENUS" to 22,
        "MESA_DOZEN" to 23,
        "MESA_NVK" to 24,
        "IMAGINATION_OPEN_SOURCE_MESA" to 25,
        "MESA_AGXV" to 26,
    )

    @Test
    fun `every constant matches the Vulkan specification`() {
        val actual = mapOf(
            "AMD_PROPRIETARY" to VkDriverId.AMD_PROPRIETARY,
            "AMD_OPEN_SOURCE" to VkDriverId.AMD_OPEN_SOURCE,
            "MESA_RADV" to VkDriverId.MESA_RADV,
            "NVIDIA_PROPRIETARY" to VkDriverId.NVIDIA_PROPRIETARY,
            "INTEL_PROPRIETARY_WINDOWS" to VkDriverId.INTEL_PROPRIETARY_WINDOWS,
            "INTEL_OPEN_SOURCE_MESA" to VkDriverId.INTEL_OPEN_SOURCE_MESA,
            "IMAGINATION_PROPRIETARY" to VkDriverId.IMAGINATION_PROPRIETARY,
            "QUALCOMM_PROPRIETARY" to VkDriverId.QUALCOMM_PROPRIETARY,
            "ARM_PROPRIETARY" to VkDriverId.ARM_PROPRIETARY,
            "GOOGLE_SWIFTSHADER" to VkDriverId.GOOGLE_SWIFTSHADER,
            "GGP_PROPRIETARY" to VkDriverId.GGP_PROPRIETARY,
            "BROADCOM_PROPRIETARY" to VkDriverId.BROADCOM_PROPRIETARY,
            "MESA_LLVMPIPE" to VkDriverId.MESA_LLVMPIPE,
            "MOLTENVK" to VkDriverId.MOLTENVK,
            "COREAVI_PROPRIETARY" to VkDriverId.COREAVI_PROPRIETARY,
            "JUICE_PROPRIETARY" to VkDriverId.JUICE_PROPRIETARY,
            "VERISILICON_PROPRIETARY" to VkDriverId.VERISILICON_PROPRIETARY,
            "MESA_TURNIP" to VkDriverId.MESA_TURNIP,
            "MESA_V3DV" to VkDriverId.MESA_V3DV,
            "MESA_PANVK" to VkDriverId.MESA_PANVK,
            "SAMSUNG_PROPRIETARY" to VkDriverId.SAMSUNG_PROPRIETARY,
            "MESA_VENUS" to VkDriverId.MESA_VENUS,
            "MESA_DOZEN" to VkDriverId.MESA_DOZEN,
            "MESA_NVK" to VkDriverId.MESA_NVK,
            "IMAGINATION_OPEN_SOURCE_MESA" to VkDriverId.IMAGINATION_OPEN_SOURCE_MESA,
        )
        for ((name, expected) in fromTheSpecification) {
            val got = actual[name] ?: continue
            assertEquals(name, expected, got)
        }
    }

    /** The two values P1 turns on. An Adreno running the system driver reports 8, not 9. */
    @Test
    fun `the two identities the guard depends on`() {
        assertEquals(8, VkDriverId.QUALCOMM_PROPRIETARY)
        assertEquals(18, VkDriverId.MESA_TURNIP)
    }

    @Test
    fun `an Adreno on the system driver is named Qualcomm`() {
        // Exactly what a real Odin2 Portal reported, and what the app used to call Imagination.
        assertEquals("Qualcomm proprietary", VkDriverId.describe(8))
    }

    @Test
    fun `a real turnip is named Turnip and counts as Mesa`() {
        assertEquals("Mesa Turnip", VkDriverId.describe(18))
        assertTrue(VkDriverId.isMesa(18))
    }

    @Test
    fun `the proprietary drivers are not Mesa`() {
        assertFalse(VkDriverId.isMesa(VkDriverId.QUALCOMM_PROPRIETARY))
        assertFalse(VkDriverId.isMesa(VkDriverId.ARM_PROPRIETARY))
        assertFalse(VkDriverId.isMesa(VkDriverId.IMAGINATION_PROPRIETARY))
    }

    @Test
    fun `an unknown driver id keeps its number rather than being mislabelled`() {
        assertEquals("driverID 99", VkDriverId.describe(99))
    }
}
