package com.amaral.driverlab.telemetry

import java.io.File

/**
 * Reads `/sys/class/thermal` without root.
 *
 * Zone naming is entirely device-specific and half of them are not GPU or CPU at all.
 * Every readable zone is recorded with its type, so whoever reads the report can work
 * out which ones matter on their device.
 *
 * Each zone also gets a guessed [ThermalRole]. The guess decides which sensors are worth
 * mentioning to the user and never decides whether a run may start: the first version of
 * this file refused to interpret zone names and then blocked runs on the maximum across
 * all of them, which stopped a freshly booted device because a power-management sensor
 * was reading 62 °C.
 */
public class ThermalZoneReader(private val root: File = File("/sys/class/thermal")) {

    public fun read(): List<ThermalZoneReading> {
        val directories = root.listFiles { file -> file.isDirectory && file.name.startsWith("thermal_zone") }
            ?: return emptyList()

        return directories.sortedBy { it.name }.mapNotNull { directory ->
            val millidegrees = readTrimmed(File(directory, "temp"))?.toLongOrNull() ?: return@mapNotNull null
            val type = readTrimmed(File(directory, "type")).orEmpty()
            val celsius = millidegrees / 1000.0
            // Some zones report in degrees already and some are simply broken; a
            // reading outside this range says nothing useful and would poison a mean.
            if (celsius < PLAUSIBLE_MINIMUM_CELSIUS || celsius > PLAUSIBLE_MAXIMUM_CELSIUS) {
                return@mapNotNull null
            }
            ThermalZoneReading(zone = directory.name, type = type, celsius = celsius)
        }
    }

    private fun readTrimmed(file: File): String? = try {
        if (file.canRead()) file.readText().trim() else null
    } catch (_: Exception) {
        // A zone the app cannot read is normal on a locked-down device. Missing
        // telemetry is recorded as missing, never as zero.
        null
    }

    public companion object {
        public const val PLAUSIBLE_MINIMUM_CELSIUS: Double = -40.0
        public const val PLAUSIBLE_MAXIMUM_CELSIUS: Double = 150.0
    }
}
