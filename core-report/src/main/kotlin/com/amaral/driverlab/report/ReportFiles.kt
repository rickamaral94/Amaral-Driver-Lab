package com.amaral.driverlab.report

import java.io.File

/**
 * Writes a report somewhere it can be shared from.
 *
 * A report never travels as a string through an Intent or a Messenger. Both are Binder
 * transactions, bounded at about a megabyte for the whole process, and a real run is larger
 * than that — a scaled Complete run measured 2.2 MB. Putting it in `Intent.EXTRA_TEXT`
 * crashed the app on the Export button; putting it in a Messenger bundle silently lost the
 * completion. Only a path or a content URI crosses.
 */
public object ReportFiles {

    public const val MIME_TYPE: String = "application/json"

    /**
     * @param directory somewhere the app can write and later grant read access to, which in
     *   practice means the FileProvider's exported cache subdirectory.
     * @return the written file, named after the session so two exports do not collide.
     */
    public fun writeForSharing(report: BenchmarkReport, directory: File): File {
        directory.mkdirs()
        val file = File(directory, fileNameFor(report))
        file.writeText(ReportJson.encodePretty(report))
        return file
    }

    public fun fileNameFor(report: BenchmarkReport): String {
        val device = report.device.model
            .lowercase()
            .map { if (it.isLetterOrDigit()) it else '-' }
            .joinToString("")
            .trim('-')
            .ifBlank { "device" }
        return "amaral-driver-lab-$device-${report.session.id}.json"
    }
}
