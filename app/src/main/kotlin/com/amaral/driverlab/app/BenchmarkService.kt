package com.amaral.driverlab.app

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.amaral.driverlab.bench.Arm
import com.amaral.driverlab.bench.ArmDefinition
import com.amaral.driverlab.bench.BenchmarkPlan
import com.amaral.driverlab.bench.BenchmarkOutcome
import com.amaral.driverlab.report.ArmPair
import com.amaral.driverlab.report.NullTestRecord
import com.amaral.driverlab.report.NullTestStore
import com.amaral.driverlab.report.ReportBuilder
import com.amaral.driverlab.report.ReportJson
import com.amaral.driverlab.report.SessionInfo
import com.amaral.driverlab.report.WorkloadArms
import com.amaral.driverlab.telemetry.PreflightReport
import com.amaral.driverlab.telemetry.DiagnosticLog
import com.amaral.driverlab.telemetry.TelemetryCollector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Runs the benchmark in the `:bench` process, in the foreground, holding a wakelock.
 *
 * All three matter for the measurement, not for convenience. A backgrounded process
 * has its scheduling cut; a device that sleeps mid-run leaves a hole in the
 * frametime series that looks exactly like a stutter; and the separate process is
 * what makes a driver crash cost one phase instead of the session — when this
 * process dies, the client's binding dies with it and the app records a crash.
 */
class BenchmarkService : Service() {

    private val job: Job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true }

    private var wakeLock: PowerManager.WakeLock? = null
    private var client: Messenger? = null
    private lateinit var handlerThread: HandlerThread
    private lateinit var incoming: Messenger

    override fun onCreate() {
        super.onCreate()
        handlerThread = HandlerThread("bench-control").apply { start() }
        incoming = Messenger(
            object : Handler(handlerThread.looper) {
                override fun handleMessage(message: Message) = handle(message)
            },
        )
    }

    override fun onBind(intent: Intent?): IBinder = incoming.binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        DiagnosticLog.i(TAG, "service starting in the foreground")
        startForeground(NOTIFICATION_ID, notification(getString(R.string.running_title), 0, 0))
        acquireWakeLock()
        return START_NOT_STICKY
    }

    private fun handle(message: Message) {
        when (message.what) {
            MSG_START -> {
                client = message.replyTo
                val payload = message.data.getString(KEY_REQUEST) ?: return
                val overridden = message.data.getBoolean(KEY_PREFLIGHT_OVERRIDDEN, false)
                start(json.decodeFromString(BenchRequest.serializer(), payload), overridden)
            }

            MSG_ABORT -> {
                scope.coroutineContext[Job]?.children?.forEach { it.cancel() }
                stopSelf()
            }
        }
    }

    private fun start(request: BenchRequest, preflightOverridden: Boolean) {
        DiagnosticLog.i(
            TAG,
            "run requested: ${request.armA.label} vs ${request.armB.label}, " +
                "preflightOverridden=$preflightOverridden" +
                (request.nullTest?.let { ", null test of ${it.totalComparisons} A/A comparisons" } ?: ""),
        )
        acquireWakeLock()
        request.nullTest?.let { return startNullTest(request, it) }
        val plan = BenchmarkPlan(
            a = ArmDefinition(Arm.A, request.armA.toRequestedDriver(), request.armA.label),
            b = ArmDefinition(Arm.B, request.armB.toRequestedDriver(), request.armB.label),
            workloads = request.workloads.map { it.toSpec() },
            runsPerArm = request.runsPerArm,
            comparisonIndex = request.comparisonIndex,
        )

        scope.launch {
            val telemetry = TelemetryCollector(applicationContext)
            val result = runCatching {
                val outcome = com.amaral.driverlab.bench.RunCoordinator(
                    AndroidBenchHost(applicationContext, telemetry),
                ).execute(
                    plan = plan,
                    temporaryDirectory = File(cacheDir, "bench").apply { mkdirs() },
                    // Where the APK's native libraries were extracted. The rootless hook is
                    // dlopened out of a linker namespace over this path, so it cannot be
                    // substituted with a scratch directory.
                    nativeLibraryDirectory = File(applicationInfo.nativeLibraryDir),
                    onProgress = { progress ->
                        send(
                            MSG_PROGRESS,
                            KEY_PROGRESS,
                            json.encodeToString(
                                BenchProgress.serializer(),
                                BenchProgress(
                                    state = progress.state.name,
                                    executionsDone = progress.executionsDone,
                                    executionsTotal = progress.executionsTotal,
                                    label = progress.currentLabel,
                                    workloadId = progress.currentWorkloadId,
                                    peakCelsius = progress.latestTelemetry?.peakZoneCelsius,
                                ),
                            ),
                        )
                        updateNotification(progress.currentLabel, progress.executionsDone, progress.executionsTotal)
                    },
                )

                val snapshot = withContext(Dispatchers.IO) { telemetry.snapshot() }
                // What this device proved about itself, re-derived from the stored A/A series
                // rather than trusted from a stored verdict. Null when it has never run the
                // test, or ran it on a profile that does not cover these workloads — both of
                // which leave the ranking gate closed.
                val verdict = NullTestStore(File(filesDir, STATE_DIRECTORY))
                    .read(snapshot.buildFingerprint)
                    ?.resultFor(plan.workloads.map { it.workloadId })
                DiagnosticLog.i(
                    TAG,
                    "null test for this profile: " +
                        when {
                            verdict == null -> "none on record"
                            verdict.passed -> "passed, floors " + verdict.perWorkload.entries.joinToString {
                                "${it.key} ${"%.1f".format(it.value.appliedNoiseFloor * 100)}%"
                            }
                            else -> "failed, ranking stays blocked"
                        },
                )

                ReportBuilder(appVersion = versionName()).build(
                    outcome = outcome,
                    device = snapshot,
                    preflight = PreflightReport(issues = emptyList(), overridden = preflightOverridden),
                    session = SessionInfo(
                        id = UUID.randomUUID().toString(),
                        thermalSessionId = UUID.randomUUID().toString(),
                        startedAtEpochMs = System.currentTimeMillis(),
                        runnerFinalState = outcome.finalState.name,
                    ),
                    nullTestResult = verdict?.weakest(),
                    noiseFloors = verdict?.perWorkload.orEmpty()
                        .mapValues { it.value.appliedNoiseFloor },
                )
            }

            val completion = result.fold(
                onSuccess = { report ->
                    DiagnosticLog.i(TAG, "run complete, report built")
                    // Encoding is inside the guard on purpose. It used to sit outside, so a
                    // value JSON cannot represent took the whole runner process down after the
                    // work was already finished — losing a completed run to a formatting fault.
                    runCatching {
                        val directory = File(cacheDir, "reports").apply { mkdirs() }
                        val file = File(directory, "${report.session.id}.json")
                        file.writeText(ReportJson.encode(report))
                        DiagnosticLog.i(TAG, "report written: ${file.length()} bytes at ${file.path}")
                        file.path
                    }.fold(
                        onSuccess = { BenchCompletion(ok = true, reportPath = it) },
                        onFailure = {
                            DiagnosticLog.e(TAG, "the finished report could not be written", it)
                            BenchCompletion(
                                ok = false,
                                error = "The run finished but its report could not be written: " +
                                    "${it.message}. This is a bug in the app, not a result about " +
                                    "the driver.",
                            )
                        },
                    )
                },
                onFailure = {
                    DiagnosticLog.e(TAG, "run threw", it)
                    BenchCompletion(ok = false, error = it.message ?: it::class.java.name)
                },
            )
            send(MSG_COMPLETE, KEY_COMPLETION, json.encodeToString(BenchCompletion.serializer(), completion))
            stopSelf()
        }
    }

    /**
     * Runs the A/A sequence and stores what it measured.
     *
     * Each comparison is its own plan with its own `comparisonIndex`, because that is what
     * flips the leading arm between comparisons — running fifteen copies of an identical
     * plan would reintroduce the ordering effect the counterbalancing exists to remove.
     *
     * Nothing here decides whether the device passed. The run medians go to the store and
     * the verdict is derived from them on every read, so a stored file can never grant a
     * ranking the measurements do not support.
     */
    private fun startNullTest(request: BenchRequest, spec: NullTestSpec) {
        val driver = request.armA.toRequestedDriver()
        val workloads = request.workloads.map { it.toSpec() }

        scope.launch {
            val telemetry = TelemetryCollector(applicationContext)
            val completion = runCatching {
                val perComparison = mutableListOf<BenchmarkOutcome>()
                val perPlanExecutions = BenchmarkPlan.nullTest(
                    driver = driver,
                    label = request.armA.label,
                    workloads = workloads,
                    runsPerArm = request.runsPerArm,
                ).totalExecutions
                val totalExecutions = perPlanExecutions * spec.totalComparisons

                for (comparison in 0 until spec.totalComparisons) {
                    val phase = when {
                        comparison < spec.warmupComparisons -> "warm-up"
                        comparison < spec.warmupComparisons + spec.calibrationComparisons -> "calibration"
                        else -> "A/A"
                    }
                    val plan = BenchmarkPlan.nullTest(
                        driver = driver,
                        label = request.armA.label,
                        workloads = workloads,
                        runsPerArm = request.runsPerArm,
                        comparisonIndex = comparison,
                    )
                    DiagnosticLog.i(
                        TAG,
                        "null test $phase ${comparison + 1} of ${spec.totalComparisons}",
                    )
                    val done = comparison * perPlanExecutions
                    val outcome = com.amaral.driverlab.bench.RunCoordinator(
                        AndroidBenchHost(applicationContext, telemetry),
                    ).execute(
                        plan = plan,
                        temporaryDirectory = File(cacheDir, "bench").apply { mkdirs() },
                        nativeLibraryDirectory = File(applicationInfo.nativeLibraryDir),
                        onProgress = { progress ->
                            val label = getString(
                                R.string.null_test_progress,
                                comparison + 1,
                                spec.totalComparisons,
                            )
                            send(
                                MSG_PROGRESS,
                                KEY_PROGRESS,
                                json.encodeToString(
                                    BenchProgress.serializer(),
                                    BenchProgress(
                                        state = progress.state.name,
                                        executionsDone = done + progress.executionsDone,
                                        executionsTotal = totalExecutions,
                                        label = label,
                                        workloadId = progress.currentWorkloadId,
                                        peakCelsius = progress.latestTelemetry?.peakZoneCelsius,
                                    ),
                                ),
                            )
                            updateNotification(label, done + progress.executionsDone, totalExecutions)
                        },
                    )
                    // A comparison that did not complete is dropped rather than padded. The
                    // null test then reports itself incomplete, which is the truth: fewer
                    // than ten consecutive ties is not a pass.
                    if (comparison < spec.warmupComparisons) {
                        // Run in full and thrown away. The GPU spends the first minutes of a
                        // session in a different frequency step, and calling that the device's
                        // resolution is the same mistake as timing a workload's first frame.
                        DiagnosticLog.i(TAG, "warm-up comparison ${comparison + 1} discarded")
                    } else if (outcome.completed) {
                        perComparison += outcome
                    } else {
                        DiagnosticLog.e(
                            TAG,
                            "null test comparison ${comparison + 1} ended in ${outcome.finalState}, dropped",
                        )
                    }
                }

                val snapshot = withContext(Dispatchers.IO) { telemetry.snapshot() }
                val record = NullTestRecord(
                    deviceFingerprint = snapshot.buildFingerprint,
                    driverSha256 = request.armA.libraryChecksum,
                    driverLabel = request.armA.label,
                    appVersion = versionName(),
                    completedAtEpochMs = System.currentTimeMillis(),
                    runsPerArm = request.runsPerArm,
                    perWorkload = workloads.map { workload ->
                        val arms = perComparison.map { outcome ->
                            ArmPair(
                                first = outcome.runMediansFor(Arm.A, workload.workloadId).toList(),
                                second = outcome.runMediansFor(Arm.B, workload.workloadId).toList(),
                            )
                        }
                        WorkloadArms(
                            workloadId = workload.workloadId,
                            calibration = arms.take(spec.calibrationComparisons),
                            test = arms.drop(spec.calibrationComparisons),
                        )
                    },
                )
                NullTestStore(File(filesDir, STATE_DIRECTORY)).write(record)
                DiagnosticLog.i(
                    TAG,
                    "null test stored: ${perComparison.size} of ${spec.totalComparisons} " +
                        "comparisons completed, " +
                        record.evaluate().entries.joinToString("; ") { (id, result) ->
                            "$id ${if (result.passed) "passed" else "failed"}"
                        },
                )
                BenchCompletion(ok = true, nullTest = true)
            }.getOrElse {
                DiagnosticLog.e(TAG, "null test threw", it)
                BenchCompletion(ok = false, nullTest = true, error = it.message ?: it::class.java.name)
            }

            send(MSG_COMPLETE, KEY_COMPLETION, json.encodeToString(BenchCompletion.serializer(), completion))
            stopSelf()
        }
    }

    /**
     * @return true when the message reached the client.
     *
     * A failure is logged rather than swallowed. The client going away mid-run is normal —
     * the user left the screen — but a send that fails for any other reason used to be
     * invisible, and the one that mattered (a completion too large for a Binder transaction)
     * left the UI waiting on a run that had already finished.
     */
    private fun send(what: Int, key: String, payload: String): Boolean {
        val target = client ?: return false
        val message = Message.obtain(null, what).apply {
            data = Bundle().apply { putString(key, payload) }
        }
        return runCatching { target.send(message); true }.getOrElse { error ->
            DiagnosticLog.e(TAG, "could not deliver message $what (${payload.length} chars)", error)
            false
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val power = getSystemService(PowerManager::class.java)
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            // Bounded on purpose: a leaked wakelock would outlive the run and drain
            // a device that is supposed to be measured on battery.
            acquire(MAXIMUM_RUN_MS)
        }
    }

    private fun updateNotification(text: String, done: Int, total: Int) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(text, done, total))
    }

    private fun notification(text: String, done: Int, total: Int): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, AmaralApplication.BENCH_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            // Determinate from the first frame. Section 14 lists an indeterminate bar
            // on a twenty-minute test as an antipattern, and it is: it says nothing at
            // exactly the point the user most wants to know.
            .setProgress(total.coerceAtLeast(1), done, false)
            .build()
    }

    private fun versionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName
    }.getOrNull().orEmpty()

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        scope.cancel()
        handlerThread.quitSafely()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "bench-service"

        const val MSG_START = 1
        const val MSG_ABORT = 2
        const val MSG_PROGRESS = 3
        const val MSG_COMPLETE = 4

        const val KEY_REQUEST = "request"
        const val KEY_PROGRESS = "progress"
        const val KEY_COMPLETION = "completion"
        const val KEY_PREFLIGHT_OVERRIDDEN = "preflightOverridden"

        /**
         * Where state that outlives a run lives, under the app's own files directory.
         * Both processes read it: `:bench` writes the null test record, the UI reads it
         * back to decide whether a comparison may be ranked.
         */
        const val STATE_DIRECTORY = "state"

        private const val NOTIFICATION_ID = 4201
        private const val WAKE_LOCK_TAG = "AmaralDriverLab:benchmark"

        /** Longer than the complete profile, short enough that a stuck run releases. */
        private const val MAXIMUM_RUN_MS = 45L * 60 * 1000
    }
}
