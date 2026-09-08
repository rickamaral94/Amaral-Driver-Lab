package com.amaral.driverlab.driver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityGuardTest {

    private fun identity(
        driverId: Int,
        driverName: String = "turnip",
        checksum: String = TURNIP_SHA,
        source: DriverSource = DriverSource.IMPORTED_PACKAGE,
    ) = DriverIdentity(
        libraryChecksum = checksum,
        driverId = driverId,
        driverName = driverName,
        driverInfo = "Mesa 25.1.0",
        conformanceVersion = ConformanceVersion(1, 3, 6, 0),
        deviceName = "Adreno (TM) 740",
        vendorId = 0x5143,
        deviceId = 0x43050A01,
        apiVersion = (1 shl 22) or (3 shl 12) or 280,
        driverVersion = (25 shl 22) or (1 shl 12),
        instanceExtensions = listOf("VK_KHR_surface"),
        deviceExtensions = listOf("VK_KHR_swapchain"),
        source = source,
    )

    private val requestedTurnip = RequestedDriver.Package(
        libraryChecksum = TURNIP_SHA,
        displayName = "Turnip 25.1.0",
    )

    /**
     * The failure mode section 5 was written for: the package never loaded, the Qualcomm blob
     * answered, and every number that followed would have been filed under "Turnip".
     */
    @Test
    fun `a Turnip request answered by the proprietary blob is refused`() {
        val verdict = IdentityGuard().check(
            requestedTurnip,
            identity(VkDriverId.QUALCOMM_PROPRIETARY, driverName = "Qualcomm driver"),
        )
        val rejected = verdict as IdentityVerdict.Rejected
        assertEquals(IdentityVerdict.RejectionCode.FELL_BACK_TO_SYSTEM_DRIVER, rejected.code)
        assertTrue(rejected.reason.contains("Qualcomm proprietary"))
        assertTrue(rejected.reason.contains("Turnip 25.1.0"))
    }

    @Test
    fun `a Turnip request answered by Turnip is accepted`() {
        val verdict = IdentityGuard().check(requestedTurnip, identity(VkDriverId.MESA_TURNIP))
        val accepted = verdict as IdentityVerdict.Accepted
        assertTrue(accepted.identity.isTurnip)
        assertTrue(accepted.identity.isMesa)
    }

    @Test
    fun `a driver from another family is refused even when it is not the blob`() {
        val verdict = IdentityGuard().check(
            requestedTurnip,
            identity(VkDriverId.ARM_PROPRIETARY, driverName = "Mali"),
        )
        assertEquals(
            IdentityVerdict.RejectionCode.UNEXPECTED_DRIVER_FAMILY,
            (verdict as IdentityVerdict.Rejected).code,
        )
    }

    @Test
    fun `a run with no identity at all is refused`() {
        val verdict = IdentityGuard().check(requestedTurnip, null)
        assertEquals(
            IdentityVerdict.RejectionCode.NO_IDENTITY,
            (verdict as IdentityVerdict.Rejected).code,
        )
    }

    @Test
    fun `a device that reports no driver properties cannot host a benchmark`() {
        val verdict = IdentityGuard().check(
            requestedTurnip,
            identity(driverId = 0, driverName = ""),
        )
        assertEquals(
            IdentityVerdict.RejectionCode.MISSING_DRIVER_PROPERTIES,
            (verdict as IdentityVerdict.Rejected).code,
        )
    }

    @Test
    fun `the system driver is accepted whatever it turns out to be`() {
        val verdict = IdentityGuard().check(
            RequestedDriver.System,
            identity(VkDriverId.QUALCOMM_PROPRIETARY, source = DriverSource.SYSTEM),
        )
        val accepted = verdict as IdentityVerdict.Accepted
        assertTrue(accepted.label.contains("Qualcomm proprietary"))
        assertFalse(accepted.unverified)
    }

    @Test
    fun `an unknown build runs but is marked unverified`() {
        val verdict = IdentityGuard().check(requestedTurnip, identity(VkDriverId.MESA_TURNIP))
        val accepted = verdict as IdentityVerdict.Accepted
        assertTrue(accepted.unverified)
        assertEquals("Turnip 25.1.0", accepted.label)
    }

    @Test
    fun `a build on the allowlist gets its catalogued name`() {
        val allowlist = DriverAllowlist.parse(
            """
            {
              "schemaVersion": 1,
              "entries": [
                { "sha256": "$TURNIP_SHA", "label": "Turnip 25.1.0 (Amaral build 4)", "vendor": "Mesa" }
              ]
            }
            """.trimIndent(),
        )
        val verdict = IdentityGuard(allowlist).check(requestedTurnip, identity(VkDriverId.MESA_TURNIP))
        val accepted = verdict as IdentityVerdict.Accepted
        assertFalse(accepted.unverified)
        assertEquals("Turnip 25.1.0 (Amaral build 4)", accepted.label)
    }

    @Test
    fun `allowlist lookup ignores hash case`() {
        val allowlist = DriverAllowlist(
            listOf(AllowlistEntry(sha256 = TURNIP_SHA.uppercase(), label = "Known build")),
        )
        assertTrue(allowlist.contains(TURNIP_SHA))
        assertEquals(1, allowlist.size)
    }

    private companion object {
        const val TURNIP_SHA = "a3f1c0de00000000000000000000000000000000000000000000000000000001"
    }
}
