package com.amaral.driverlab.app

import android.content.Context
import com.amaral.driverlab.bench.BenchDriverSession
import com.amaral.driverlab.bench.BenchHost
import com.amaral.driverlab.bench.DriverSessionResult
import com.amaral.driverlab.driver.LoadRequest
import com.amaral.driverlab.telemetry.TelemetryCollector
import com.amaral.driverlab.telemetry.TelemetrySample
import com.amaral.driverlab.vk.VulkanEngine
import com.amaral.driverlab.vk.VulkanSession
import com.amaral.driverlab.vk.WorkloadResult
import com.amaral.driverlab.vk.WorkloadSpec
import kotlinx.coroutines.delay

/**
 * Wires the coordinator to the real device.
 *
 * Lives in the `:bench` process. Everything it touches — the ICD, the Vulkan
 * device, the workloads — dies with that process if a driver takes it down, which
 * is the point of the separation.
 */
class AndroidBenchHost(
    context: Context,
    private val collector: TelemetryCollector = TelemetryCollector(context),
) : BenchHost {

    override fun openDriver(request: LoadRequest): DriverSessionResult =
        when (val opened = VulkanEngine.open(request)) {
            is VulkanEngine.OpenResult.Opened -> DriverSessionResult.Opened(Session(opened.session))
            is VulkanEngine.OpenResult.Failed ->
                DriverSessionResult.Failed(opened.stage.name, opened.message)
        }

    override fun sampleTelemetry(): TelemetrySample = collector.sample()

    override suspend fun cooldown(millis: Long) {
        // Suspending rather than sleeping, so the UI stays live and abort works.
        delay(millis)
    }

    private class Session(private val delegate: VulkanSession) : BenchDriverSession {
        override val identity = delegate.identity
        override fun run(spec: WorkloadSpec): WorkloadResult = delegate.run(spec)
        override fun close() = delegate.close()
    }
}
