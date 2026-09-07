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
import com.amaral.driverlab.report.ReportBuilder
import com.amaral.driverlab.report.ReportJson
import com.amaral.driverlab.report.SessionInfo
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
                "preflightOverridden=$preflightOverridden",
        )
        acquireWakeLock()
        val plan = BenchmarkPlan(
            a = ArmDefinition(Arm.A, request.armA.toRequestedDriver(), request.armA.label),
            b = ArmDefinition(Arm.B, request.armB.toRequestedDriver(), request.armB.label),
            workloads = request.workloads.map { it.toSpec() },
            runsPerArm = request.runsPerArm,
            comparisonIndex = request.comparisonIndex,
            isNullTest = request.isNullTest,
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

                ReportBuilder(appVersion = versionName()).build(
                    outcome = outcome,
                    device = withContext(Dispatchers.IO) { telemetry.snapshot() },
                    preflight = PreflightReport(issues = emptyList(), overridden = preflightOverridden),
                    session = SessionInfo(
                        id = UUID.randomUUID().toString(),
                        thermalSessionId = UUID.randomUUID().toString(),
                        startedAtEpochMs = System.currentTimeMillis(),
                        runnerFinalState = outcome.finalState.name,
                    ),
                    nullTestResult = null,
                )
            }

            val completion = result.fold(
                onSuccess = { report ->
                    DiagnosticLog.i(TAG, "run complete, report built")
                    // Encoding is inside the guard on purpose. It used to sit outside, so a
                    // value JSON cannot represent took the whole runner process down after the
                    // work was already finished — losing a completed run to a formatting fault.
                    runCatching { ReportJson.encode(report) }.fold(
                        onSuccess = { BenchCompletion(ok = true, reportJson = it) },
                        onFailure = {
                            DiagnosticLog.e(TAG, "the finished report could not be encoded", it)
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

    private fun send(what: Int, key: String, payload: String) {
        val target = client ?: return
        val message = Message.obtain(null, what).apply {
            data = Bundle().apply { putString(key, payload) }
        }
        // The client going away mid-run is normal — the user left the screen. The
        // run continues; there is simply nowhere to report to.
        runCatching { target.send(message) }
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

        private const val NOTIFICATION_ID = 4201
        private const val WAKE_LOCK_TAG = "AmaralDriverLab:benchmark"

        /** Longer than the complete profile, short enough that a stuck run releases. */
        private const val MAXIMUM_RUN_MS = 45L * 60 * 1000
    }
}
