package com.amaral.driverlab.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.amaral.driverlab.telemetry.DiagnosticLog

class AmaralApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Runs in both processes. The benchmark lives in :bench precisely so that it can die, so
        // the log has to be open there too — and the crash handler installed before anything
        // else, because the first thing worth recording is the failure that happens before the
        // app is fully up.
        val external = getExternalFilesDir(null) ?: filesDir
        // Application.getProcessName() is the static one, added in API 28; it is the only way to
        // tell the app process from :bench without parsing /proc.
        DiagnosticLog.install(external, android.app.Application.getProcessName())
        DiagnosticLog.installCrashHandler()

        DiagnosticLog.i(
            "app",
            "starting ${BuildConfig.APPLICATION_ID} on ${Build.MANUFACTURER} ${Build.MODEL}, " +
                "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        )

        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                BENCH_CHANNEL_ID,
                getString(R.string.running_title),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Progress of a running benchmark."
                setShowBadge(false)
            },
        )
    }

    companion object {
        const val BENCH_CHANNEL_ID = "benchmark-progress"
    }
}
