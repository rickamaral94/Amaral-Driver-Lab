package com.amaral.driverlab.telemetry

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * A log the user can actually get hold of.
 *
 * Logcat is fine when a device is on a cable, and useless the rest of the time — which is most of
 * the time for a benchmark that has to be run unplugged. So the app keeps its own log on disk,
 * and the benchmark runs in a separate process precisely so that it can die, which means the
 * interesting entries are the ones written just before the process stopped existing. Every write
 * is flushed for that reason: a buffered logger loses exactly the lines that explain a crash.
 *
 * The layout is a tree under the app's own external files directory, so no permission is needed:
 *
 * ```
 * Android/data/<package>/files/
 *   logs/
 *     2026-09-07T04-55-12_main.log
 *     2026-09-07T04-55-13_bench.log
 *     crashes/
 *       2026-09-07T04-55-30_bench.txt
 * ```
 *
 * On Android 11 and later most file managers cannot browse `Android/data`, so the app also
 * offers to share the whole tree — see `DiagnosticBundle`. Writing somewhere the user can only
 * reach with a cable would defeat the point.
 */
public object DiagnosticLog {

    public enum class Level { DEBUG, INFO, WARN, ERROR }

    private const val MAXIMUM_SESSION_FILES = 20
    private const val MAXIMUM_CRASH_FILES = 20

    /** Entries kept in memory so the UI can show recent activity without reading the file back. */
    private const val MAXIMUM_IN_MEMORY_ENTRIES = 400

    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)
    private val fileStampFormat = SimpleDateFormat("yyyy-MM-dd'T'HH-mm-ss", Locale.US)

    private val recent = ConcurrentLinkedQueue<String>()

    @Volatile
    private var root: File? = null

    @Volatile
    private var sessionFile: File? = null

    @Volatile
    private var processTag: String = "app"

    private val writeLock = Any()

    public val installed: Boolean get() = sessionFile != null

    /**
     * @param filesDirectory the app's external files directory. Called once per process; each
     *   process gets its own file, because two processes appending to one file interleave lines
     *   in an order neither of them can explain afterwards.
     */
    public fun install(filesDirectory: File, processName: String) {
        synchronized(writeLock) {
            if (sessionFile != null) return
            processTag = processName.substringAfterLast(':').ifBlank { "app" }
            val logs = File(filesDirectory, "logs").apply { mkdirs() }
            File(logs, "crashes").mkdirs()
            root = logs
            sessionFile = File(logs, "${fileStampFormat.format(Date())}_$processTag.log")
            prune(logs, suffix = ".log", keep = MAXIMUM_SESSION_FILES)
        }
        write(Level.INFO, "log", "opened for process \"$processTag\"")
    }

    public fun d(tag: String, message: String): Unit = write(Level.DEBUG, tag, message)
    public fun i(tag: String, message: String): Unit = write(Level.INFO, tag, message)
    public fun w(tag: String, message: String): Unit = write(Level.WARN, tag, message)

    public fun e(tag: String, message: String, error: Throwable? = null) {
        write(Level.ERROR, tag, message + (error?.let { "\n" + stackTraceOf(it) } ?: ""))
    }

    private fun write(level: Level, tag: String, message: String) {
        val line = "${timestampFormat.format(Date())} ${level.name.first()} [$processTag/$tag] ${redact(message)}"
        recent.add(line)
        while (recent.size > MAXIMUM_IN_MEMORY_ENTRIES) recent.poll()

        val target = sessionFile ?: return
        synchronized(writeLock) {
            runCatching {
                // Appended and flushed line by line. Anything buffered is lost when the runner
                // process dies, which is the case this log exists to explain.
                target.appendText(line + "\n")
            }
        }
    }

    /**
     * Records an uncaught exception and then hands off to whatever was installed before, so the
     * platform still does its own reporting and the process still dies. Swallowing the crash
     * here would leave the app in an unknown state and hide it from the system.
     */
    public fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                write(Level.ERROR, "crash", "uncaught on thread \"${thread.name}\"")
                val crashes = root?.let { File(it, "crashes") }
                if (crashes != null) {
                    crashes.mkdirs()
                    File(crashes, "${fileStampFormat.format(Date())}_$processTag.txt").writeText(
                        buildString {
                            appendLine("process: $processTag")
                            appendLine("thread: ${thread.name}")
                            appendLine("time: ${timestampFormat.format(Date())}")
                            appendLine()
                            append(stackTraceOf(error))
                            appendLine()
                            appendLine("--- last ${recent.size} log lines ---")
                            recent.forEach { appendLine(it) }
                        },
                    )
                    prune(crashes, suffix = ".txt", keep = MAXIMUM_CRASH_FILES)
                }
            }
            previous?.uncaughtException(thread, error)
        }
    }

    public fun recentLines(): List<String> = recent.toList()

    public fun logsDirectory(): File? = root

    public fun crashesDirectory(): File? = root?.let { File(it, "crashes") }

    private fun stackTraceOf(error: Throwable): String {
        val writer = StringWriter()
        error.printStackTrace(PrintWriter(writer))
        return writer.toString()
    }

    /**
     * The GitHub token is the one secret this app holds, and a log the user is encouraged to
     * share is exactly where it must never appear. Redacting on the way in rather than on the way
     * out means a token cannot be sitting in a file waiting for someone to forget.
     */
    private fun redact(message: String): String =
        TOKEN_PATTERN.replace(message) { match -> match.value.take(4) + "…redacted…" }

    private val TOKEN_PATTERN =
        Regex("\\b(gh[pousr]_[A-Za-z0-9]{16,}|github_pat_[A-Za-z0-9_]{20,})\\b")

    private fun prune(directory: File, suffix: String, keep: Int) {
        val files = directory.listFiles { file -> file.isFile && file.name.endsWith(suffix) }
            ?.sortedByDescending { it.name }
            ?: return
        files.drop(keep).forEach { runCatching { it.delete() } }
    }
}
