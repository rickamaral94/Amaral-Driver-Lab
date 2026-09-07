package com.amaral.driverlab.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ThermalZoneReaderTest {

    @get:Rule
    val temporaryFolder: TemporaryFolder = TemporaryFolder()

    private fun zone(name: String, type: String?, temp: String?) {
        val directory = File(temporaryFolder.root, name).apply { mkdirs() }
        type?.let { File(directory, "type").writeText(it) }
        temp?.let { File(directory, "temp").writeText(it) }
    }

    @Test
    fun `reads millidegrees and reports celsius`() {
        zone("thermal_zone0", "cpu-0-0-usr", "41200\n")
        zone("thermal_zone1", "gpuss-0-usr", "38900")

        val readings = ThermalZoneReader(temporaryFolder.root).read()
        assertEquals(2, readings.size)
        assertEquals("cpu-0-0-usr", readings[0].type)
        assertEquals(41.2, readings[0].celsius, 1e-9)
        assertEquals(38.9, readings[1].celsius, 1e-9)
    }

    @Test
    fun `zones are returned in a stable order`() {
        zone("thermal_zone10", "late", "30000")
        zone("thermal_zone2", "early", "31000")
        val readings = ThermalZoneReader(temporaryFolder.root).read()
        assertEquals(listOf("thermal_zone10", "thermal_zone2"), readings.map { it.zone })
    }

    @Test
    fun `an unreadable zone is skipped, not reported as zero`() {
        zone("thermal_zone0", "cpu", "40000")
        zone("thermal_zone1", "broken", null)
        val readings = ThermalZoneReader(temporaryFolder.root).read()
        assertEquals(1, readings.size)
        assertEquals("cpu", readings.single().type)
    }

    @Test
    fun `implausible readings are dropped rather than poisoning the peak`() {
        // Some kernels report a sentinel here; averaging it in would make a warm
        // device look frozen and a preflight check pass that should not.
        zone("thermal_zone0", "cpu", "40000")
        zone("thermal_zone1", "sensor-off", "-274000")
        zone("thermal_zone2", "runaway", "999000")
        val readings = ThermalZoneReader(temporaryFolder.root).read()
        assertEquals(listOf(40.0), readings.map { it.celsius })
    }

    @Test
    fun `a non-numeric temperature is skipped`() {
        zone("thermal_zone0", "cpu", "not a number")
        assertTrue(ThermalZoneReader(temporaryFolder.root).read().isEmpty())
    }

    @Test
    fun `a missing sysfs tree is empty, not an error`() {
        assertTrue(ThermalZoneReader(File(temporaryFolder.root, "absent")).read().isEmpty())
    }

    @Test
    fun `a zone with no type is still recorded`() {
        zone("thermal_zone0", null, "42000")
        val reading = ThermalZoneReader(temporaryFolder.root).read().single()
        assertEquals("", reading.type)
        assertEquals(42.0, reading.celsius, 1e-9)
    }

    @Test
    fun `zones are classified so an unrelated sensor can be told apart`() {
        zone("thermal_zone0", "cpu-0-0-usr", "31000")
        zone("thermal_zone7", "gpuss-0-usr", "30000")
        zone("thermal_zone20", "pm8550b_tz", "62000")
        zone("thermal_zone30", "skin-therm", "29000")
        zone("thermal_zone40", "mdm-core-usr", "45000")
        zone("thermal_zone50", "something-nobody-documented", "33000")

        val byType = ThermalZoneReader(temporaryFolder.root).read().associateBy { it.type }
        assertEquals(ThermalRole.CPU, byType.getValue("cpu-0-0-usr").role)
        assertEquals(ThermalRole.GPU, byType.getValue("gpuss-0-usr").role)
        assertEquals(ThermalRole.POWER, byType.getValue("pm8550b_tz").role)
        assertEquals(ThermalRole.SKIN, byType.getValue("skin-therm").role)
        assertEquals(ThermalRole.PERIPHERAL, byType.getValue("mdm-core-usr").role)
        assertEquals(ThermalRole.UNKNOWN, byType.getValue("something-nobody-documented").role)
    }

    @Test
    fun `only cpu, gpu and skin count towards the reading shown to the user`() {
        assertTrue(ThermalRole.CPU.relevantToBenchmarking)
        assertTrue(ThermalRole.GPU.relevantToBenchmarking)
        assertTrue(ThermalRole.SKIN.relevantToBenchmarking)
        // These idle warm on a cold device and say nothing about graphics work.
        assertFalse(ThermalRole.POWER.relevantToBenchmarking)
        assertFalse(ThermalRole.PERIPHERAL.relevantToBenchmarking)
        assertFalse("an unrecognised zone is not evidence either", ThermalRole.UNKNOWN.relevantToBenchmarking)
    }
}
