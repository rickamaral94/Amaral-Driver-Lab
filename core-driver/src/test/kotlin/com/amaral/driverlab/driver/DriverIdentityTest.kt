package com.amaral.driverlab.driver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DriverIdentityTest {

    private fun identity(driverId: Int, driverVersion: Int) = DriverIdentity(
        libraryChecksum = "",
        driverId = driverId,
        driverName = "turnip",
        driverInfo = "Mesa 25.1.0",
        conformanceVersion = ConformanceVersion(1, 3, 6, 0),
        deviceName = "Adreno (TM) 740",
        vendorId = 0x5143,
        deviceId = 0x43050A01,
        apiVersion = (1 shl 22) or (3 shl 12) or 280,
        driverVersion = driverVersion,
        instanceExtensions = emptyList(),
        deviceExtensions = emptyList(),
        source = DriverSource.IMPORTED_PACKAGE,
    )

    @Test
    fun `api version unpacks the way Vulkan packs it`() {
        assertEquals("1.3.280", identity(VkDriverId.MESA_TURNIP, 0).apiVersionString())
    }

    @Test
    fun `Mesa packs its driver version like an api version`() {
        val turnip = identity(VkDriverId.MESA_TURNIP, (25 shl 22) or (1 shl 12) or 0)
        assertEquals("25.1.0", turnip.driverVersionString())
    }

    @Test
    fun `other vendors keep the raw driver version`() {
        val qualcomm = identity(VkDriverId.QUALCOMM_PROPRIETARY, 512)
        assertEquals("512", qualcomm.driverVersionString())
    }

    @Test
    fun `the summary names the driver and the device`() {
        val summary = identity(VkDriverId.MESA_TURNIP, 0).summary()
        assertTrue(summary.contains("Mesa Turnip"))
        assertTrue(summary.contains("Adreno (TM) 740"))
        assertTrue(summary.contains("Vulkan 1.3.280"))
    }

    @Test
    fun `conformance version prints as four parts`() {
        assertEquals("1.3.6.0", ConformanceVersion(1, 3, 6, 0).toString())
    }

    @Test
    fun `an unknown driver id keeps its number instead of being dropped`() {
        assertEquals("driverID 99", VkDriverId.describe(99))
    }
}
