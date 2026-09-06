package com.amaral.driverlab.vk

import com.amaral.driverlab.driver.DriverSource
import com.amaral.driverlab.driver.VkDriverId
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the parts of the engine that do not need a GPU: the shape of the work a
 * spec declares, and the translation of the native layer's JSON into the domain
 * types the guard and the report use.
 */
class VulkanEngineTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `a spec must declare a positive amount of work`() {
        val error = runCatching { WorkloadSpec(WorkloadIds.BASELINE, frameCount = 0) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun `warmup frames are allowed to be zero but not negative`() {
        WorkloadSpec(WorkloadIds.BASELINE, warmupFrames = 0)
        val error = runCatching {
            WorkloadSpec(WorkloadIds.BASELINE, warmupFrames = -1)
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun `the standard specs state fixed counts, never a duration`() {
        val baseline = WorkloadSpec.baseline()
        assertEquals(WorkloadIds.BASELINE, baseline.workloadId)
        assertTrue(baseline.frameCount > 0)
        assertTrue("warmup must be discarded, not counted", baseline.warmupFrames > 0)

        val tiling = WorkloadSpec.tilingGmem()
        assertEquals(WorkloadIds.TILING_GMEM, tiling.workloadId)
        assertTrue("the tiling target must be large enough to bin", tiling.width >= 1920)
        assertTrue(tiling.trianglesPerDraw > 0)
    }

    @Test
    fun `native identity maps onto the domain type`() {
        val native = json.decodeFromString(
            NativeStatus.serializer(),
            """
            {
              "ok": true,
              "identity": {
                "driverId": 15,
                "driverName": "turnip",
                "driverInfo": "Mesa 25.1.0",
                "hasConformanceVersion": true,
                "conformanceMajor": 1, "conformanceMinor": 3,
                "conformanceSubminor": 6, "conformancePatch": 0,
                "deviceName": "Adreno (TM) 740",
                "vendorId": 20803, "deviceId": 1124467201,
                "apiVersion": 4206872, "driverVersion": 104861696,
                "timestampPeriod": 52.08, "timestampValidBits": 64,
                "instanceExtensions": ["VK_KHR_surface"],
                "deviceExtensions": ["VK_KHR_swapchain", "VK_EXT_line_rasterization"]
              }
            }
            """.trimIndent(),
        )
        assertTrue(native.ok)

        val identity = native.identity!!.toDomain(DriverSource.IMPORTED_PACKAGE, "abc123")
        assertEquals(VkDriverId.MESA_TURNIP, identity.driverId)
        assertTrue(identity.isTurnip)
        assertEquals("abc123", identity.libraryChecksum)
        assertEquals("Adreno (TM) 740", identity.deviceName)
        assertEquals("1.3.6.0", identity.conformanceVersion.toString())
        assertEquals(2, identity.deviceExtensions.size)
    }

    @Test
    fun `an absent conformance version is null rather than four zeroes`() {
        val native = json.decodeFromString(
            NativeStatus.serializer(),
            """{"ok":true,"identity":{"driverId":9,"driverName":"qcom","hasConformanceVersion":false}}""",
        )
        assertNull(native.identity!!.toDomain(DriverSource.SYSTEM, "").conformanceVersion)
    }

    @Test
    fun `a native failure carries its stage`() {
        val status = json.decodeFromString(
            NativeStatus.serializer(),
            """{"ok":false,"stage":"HOOK_UNAVAILABLE","message":"no libadrenotools in this build"}""",
        )
        assertEquals("HOOK_UNAVAILABLE", status.stage)
        assertTrue(status.message.contains("libadrenotools"))
        assertNull(status.identity)
    }

    @Test
    fun `a workload run prefers GPU timestamps and says which series it used`() {
        val gpu = longArrayOf(16_000_000, 16_100_000)
        val cpu = longArrayOf(900_000, 950_000)
        val withTimestamps = run(gpu, cpu, timestamps = true)
        assertTrue(withTimestamps.primaryFrametimesNs.contentEquals(gpu))

        val withoutTimestamps = run(LongArray(0), cpu, timestamps = false)
        assertTrue(withoutTimestamps.primaryFrametimesNs.contentEquals(cpu))
    }

    private fun run(gpu: LongArray, cpu: LongArray, timestamps: Boolean) = WorkloadRun(
        spec = WorkloadSpec.baseline(),
        gpuFrametimesNs = gpu,
        cpuFrametimesNs = cpu,
        imageSha256 = "0".repeat(64),
        imageWidth = 1280,
        imageHeight = 720,
        sampleCount = 1,
        depthFormat = 0,
        attachmentBytes = 0,
        timestampsUsable = timestamps,
    )
}
