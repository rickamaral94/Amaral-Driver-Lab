package com.amaral.driverlab.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.amaral.driverlab.bench.ArmDefinition
import com.amaral.driverlab.bench.Arm
import com.amaral.driverlab.bench.BenchmarkPlan
import com.amaral.driverlab.bench.BenchmarkProfiles
import com.amaral.driverlab.driver.DriverImportException
import com.amaral.driverlab.driver.DriverImporter
import com.amaral.driverlab.driver.DriverPackage
import com.amaral.driverlab.driver.RequestedDriver
import com.amaral.driverlab.report.BenchmarkReport
import com.amaral.driverlab.report.ReportJson
import com.amaral.driverlab.telemetry.DiagnosticBundle
import com.amaral.driverlab.telemetry.DiagnosticLog
import com.amaral.driverlab.telemetry.Preflight
import com.amaral.driverlab.telemetry.PreflightReport
import com.amaral.driverlab.telemetry.TelemetryCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Which of the three taps the user is on. */
sealed interface Step {
    data object Home : Step
    data object Setup : Step
    data object Preflight : Step
    data object Running : Step
    data object Result : Step
}

data class UiState(
    val step: Step = Step.Home,
    val packages: List<DriverPackage> = emptyList(),
    val armA: RequestedDriver? = null,
    val armB: RequestedDriver? = RequestedDriver.System,
    val quickProfile: Boolean = true,
    val preflight: PreflightReport? = null,
    val progress: BenchProgress? = null,
    val report: BenchmarkReport? = null,
    val importError: String? = null,
    val runError: String? = null,
    val busy: Boolean = false,
) {
    val canStart: Boolean get() = armA != null && armB != null && !busy
}

class BenchViewModel(application: Application) : AndroidViewModel(application) {

    private val telemetry = TelemetryCollector(application)
    private val client = BenchmarkClient(application)
    private val importer = DriverImporter(deviceApiLevel = android.os.Build.VERSION.SDK_INT)
    private val state = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = state.asStateFlow()

    private var lastRunFinishedAt: Long? = null

    fun goTo(step: Step) = state.update { it.copy(step = step, importError = null, runError = null) }

    /**
     * Copies the archive out of the picker and validates it. The archive is
     * untrusted input, so nothing about it is believed before [DriverImporter] has
     * finished with it — including which file inside it is the driver.
     */
    fun importPackage(uri: Uri) {
        state.update { it.copy(busy = true, importError = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val id = UUID.randomUUID().toString()
                    val destination = File(getApplication<Application>().filesDir, "drivers/$id")
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { stream ->
                        importer.import(stream, destination, id)
                    } ?: error("the file could not be opened")
                }
            }
            result.onSuccess { imported ->
                DiagnosticLog.i(
                    TAG,
                    "imported \"${imported.displayName}\" lib=${imported.metadata.libraryName} " +
                        "sha256=${imported.libraryChecksum} dir=${imported.installDirectory}",
                )
                state.update {
                    it.copy(
                        busy = false,
                        packages = it.packages + imported,
                        armA = it.armA ?: RequestedDriver.of(imported),
                    )
                }
            }.onFailure { error ->
                DiagnosticLog.e(TAG, "import failed", error)
                state.update {
                    it.copy(
                        busy = false,
                        importError = when (error) {
                            is DriverImportException -> error.message
                            else -> "The package could not be read: ${error.message}"
                        },
                    )
                }
            }
        }
    }

    fun selectArm(arm: Arm, driver: RequestedDriver) = state.update {
        if (arm == Arm.A) it.copy(armA = driver) else it.copy(armB = driver)
    }

    fun selectProfile(quick: Boolean) = state.update { it.copy(quickProfile = quick) }

    fun runPreflight() {
        viewModelScope.launch {
            val sample = withContext(Dispatchers.IO) { telemetry.sample() }
            val since = lastRunFinishedAt?.let { System.currentTimeMillis() - it }

            // Every zone, with the role the app guessed. Which sysfs zone means what varies
            // by device, so this dump is the only way to tell a genuinely hot device from a
            // sensor the app misread.
            DiagnosticLog.i(TAG, "thermal status=${sample.thermalStatus}, ${sample.zones.size} zone(s)")
            for (zone in sample.zones) {
                DiagnosticLog.i(TAG, "  ${zone.zone} type=${zone.type} role=${zone.role} ${zone.celsius} C")
            }
            DiagnosticLog.i(
                TAG,
                "representative=${sample.representativeCelsius ?: "none"} peakAnyZone=${sample.peakZoneCelsius ?: "none"}",
            )

            val report = Preflight.evaluate(sample, since)
            DiagnosticLog.i(TAG, "preflight: ${report.summary()}")
            state.update { it.copy(step = Step.Preflight, preflight = report) }
        }
    }

    /** @param override true when the user chose to start despite blocking issues. */
    fun start(override: Boolean) {
        val current = state.value
        val a = current.armA ?: return
        val b = current.armB ?: return
        val preflight = current.preflight?.copy(overridden = override) ?: return
        if (!preflight.clearedToRun) return

        val workloads = if (current.quickProfile) BenchmarkProfiles.quick() else BenchmarkProfiles.complete()
        val request = BenchRequest(
            armA = refFor(a, current),
            armB = refFor(b, current),
            workloads = workloads.map(WorkloadRef::of),
            runsPerArm = BenchmarkPlan.DEFAULT_RUNS_PER_ARM,
        )

        DiagnosticLog.i(
            TAG,
            "starting: A=${request.armA.label} (${if (request.armA.systemDriver) "system" else request.armA.libraryName}) " +
                "B=${request.armB.label} (${if (request.armB.systemDriver) "system" else request.armB.libraryName})",
        )
        state.update { it.copy(step = Step.Running, preflight = preflight, progress = null, busy = true) }

        viewModelScope.launch {
            // The run happens in the :bench process. Everything that comes back
            // here has already crossed a process boundary, which is what makes a
            // driver crash survivable.
            client.run(request, preflightOverridden = override).collect { update ->
                when (update) {
                    is BenchUpdate.Progress ->
                        state.update { it.copy(progress = update.progress) }

                    is BenchUpdate.Complete -> {
                        lastRunFinishedAt = System.currentTimeMillis()
                        // Read once: the report is hundreds of kilobytes, which is why it
                        // arrives as a path rather than inline in the first place.
                        val report = if (update.completion.ok) {
                            readReport(update.completion.reportPath)
                        } else {
                            null
                        }
                        state.update {
                            when {
                                !update.completion.ok ->
                                    it.copy(step = Step.Result, busy = false, runError = update.completion.error)

                                report != null ->
                                    it.copy(step = Step.Result, busy = false, report = report, runError = null)

                                else -> it.copy(
                                    step = Step.Result,
                                    busy = false,
                                    runError = "The run finished but its report could not be read back " +
                                        "from ${update.completion.reportPath}.",
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun refFor(driver: RequestedDriver, current: UiState): DriverRef = when (driver) {
        is RequestedDriver.System -> DriverRef(systemDriver = true, label = "System driver")
        is RequestedDriver.Package -> {
            val imported = current.packages.firstOrNull { it.libraryChecksum == driver.libraryChecksum }
            DriverRef(
                systemDriver = false,
                label = driver.displayName,
                libraryChecksum = driver.libraryChecksum,
                libraryDirectory = imported?.installDirectory?.absolutePath.orEmpty(),
                libraryName = imported?.metadata?.libraryName.orEmpty(),
            )
        }
    }

    /**
     * The runner writes the report to a file and sends its path; the contents are far too
     * large for a Binder transaction. See [BenchCompletion].
     */
    private fun readReport(path: String): BenchmarkReport? = runCatching {
        ReportJson.decode(java.io.File(path).readText())
    }.onFailure { DiagnosticLog.e(TAG, "could not read the report at $path", it) }.getOrNull()

    fun exportJson(): String? = state.value.report?.let { ReportJson.encodePretty(it) }

    /**
     * Packs the log tree for sharing. Needed because since Android 11 most file managers cannot
     * browse `Android/data`, so the folder alone would only be reachable over a cable.
     */
    fun diagnosticsBundle(): java.io.File? = DiagnosticBundle.create(
        java.io.File(getApplication<Application>().cacheDir, "share").apply { mkdirs() },
    )


    private companion object {
        const val TAG = "ui"
    }
}
