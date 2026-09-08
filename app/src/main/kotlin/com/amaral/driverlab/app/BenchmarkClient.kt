package com.amaral.driverlab.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import com.amaral.driverlab.telemetry.DiagnosticLog
import kotlinx.serialization.json.Json

/** What the UI sees while the runner is working, in the app process. */
sealed interface BenchUpdate {
    data class Progress(val progress: BenchProgress) : BenchUpdate
    data class Complete(val completion: BenchCompletion) : BenchUpdate
}

/**
 * Talks to the runner in the `:bench` process.
 *
 * The important case is the unhappy one. When a driver takes `:bench` down,
 * `onServiceDisconnected` fires without a completion message having arrived, and
 * that is reported as a crash — data about the driver — rather than as the app
 * losing its session. Nothing else distinguishes a crash from a hang, which is why
 * the binding is watched rather than only the messages.
 */
class BenchmarkClient(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    fun run(request: BenchRequest, preflightOverridden: Boolean): Flow<BenchUpdate> = callbackFlow {
        val thread = HandlerThread("bench-client").apply { start() }
        var completed = false

        val incoming = Messenger(
            object : Handler(thread.looper) {
                override fun handleMessage(message: Message) {
                    when (message.what) {
                        BenchmarkService.MSG_PROGRESS ->
                            message.data.getString(BenchmarkService.KEY_PROGRESS)?.let {
                                trySend(BenchUpdate.Progress(json.decodeFromString(BenchProgress.serializer(), it)))
                            }

                        BenchmarkService.MSG_COMPLETE -> {
                            message.data.getString(BenchmarkService.KEY_COMPLETION)?.let {
                                completed = true
                                DiagnosticLog.i(TAG, "completion received")
                                trySend(
                                    BenchUpdate.Complete(
                                        json.decodeFromString(BenchCompletion.serializer(), it),
                                    ),
                                )
                            }
                            close()
                        }
                    }
                }
            },
        )

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                DiagnosticLog.i(TAG, "bound to the runner process")
                val service = Messenger(binder)
                val message = Message.obtain(null, BenchmarkService.MSG_START).apply {
                    replyTo = incoming
                    data = Bundle().apply {
                        putString(
                            BenchmarkService.KEY_REQUEST,
                            json.encodeToString(BenchRequest.serializer(), request),
                        )
                        putBoolean(BenchmarkService.KEY_PREFLIGHT_OVERRIDDEN, preflightOverridden)
                    }
                }
                runCatching { service.send(message) }.onFailure {
                    trySend(
                        BenchUpdate.Complete(
                            BenchCompletion(ok = false, error = "the runner would not accept the plan"),
                        ),
                    )
                    close()
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                DiagnosticLog.w(TAG, "runner process disconnected, completed=$completed")
                if (!completed) {
                    // The runner process died without reporting. That is a crash, and
                    // a crash is a result about the driver, not a lost session.
                    trySend(
                        BenchUpdate.Complete(
                            BenchCompletion(
                                ok = false,
                                crashed = true,
                                error = "The benchmark process stopped without finishing. The driver " +
                                    "under test most likely crashed; this is recorded as a failure of " +
                                    "that driver, not of the run.",
                            ),
                        ),
                    )
                }
                close()
            }
        }

        val intent = Intent(context, BenchmarkService::class.java)
        DiagnosticLog.i(TAG, "starting the runner process")
        context.startForegroundService(intent)
        val bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        if (!bound) {
            DiagnosticLog.e(TAG, "bindService refused")
            trySend(BenchUpdate.Complete(BenchCompletion(ok = false, error = "the runner could not be started")))
            close()
        }

        awaitClose {
            runCatching { context.unbindService(connection) }
            thread.quitSafely()
        }
    }
}

private const val TAG = "bench-client"
