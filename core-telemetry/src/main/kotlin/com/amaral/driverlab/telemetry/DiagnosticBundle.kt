package com.amaral.driverlab.telemetry

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Packs the log tree into one file the user can send somewhere.
 *
 * This exists because of a platform detail rather than a design preference: since Android 11 most
 * file managers cannot browse `Android/data`, so a log written there is reachable over a cable and
 * not much else. Sharing a zip through the normal share sheet works everywhere.
 */
public object DiagnosticBundle {

    /**
     * @param destinationDirectory somewhere the app can write and later share from, typically
     *   the cache directory.
     * @return the zip, or null when there is nothing to collect.
     */
    public fun create(destinationDirectory: File, fileName: String = "diagnostics.zip"): File? {
        val logs = DiagnosticLog.logsDirectory() ?: return null
        if (!logs.isDirectory) return null

        val files = logs.walkTopDown().filter { it.isFile }.toList()
        if (files.isEmpty()) return null

        destinationDirectory.mkdirs()
        val zip = File(destinationDirectory, fileName)

        ZipOutputStream(zip.outputStream().buffered()).use { out ->
            for (file in files) {
                // Paths are relative to the log root, so the zip opens as logs/… rather than as
                // somebody's device directory layout.
                val entryName = file.relativeTo(logs.parentFile ?: logs).path
                out.putNextEntry(ZipEntry(entryName))
                file.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
        return zip
    }
}
