package com.amaral.driverlab.telemetry

import java.io.File

/**
 * Reads `/sys/class/thermal` without root.
 *
 * Zone naming is entirely device-specific and half of them are not GPU or CPU at
 * all. Nothing here tries to interpret the names — every readable zone is recorded
 * with its type, and working out which ones matter on a given device is a job for
 * whoever reads the report, not for a heuristic that would be wrong somewhere.
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
