package com.amaral.driverlab.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class AmaralApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Created in both processes: the benchmark runs in :bench and posts the
        // progress notification from there.
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
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
