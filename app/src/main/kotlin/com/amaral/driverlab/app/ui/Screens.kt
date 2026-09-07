package com.amaral.driverlab.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.amaral.driverlab.app.BuildConfig
import com.amaral.driverlab.app.R
import com.amaral.driverlab.app.Step
import com.amaral.driverlab.app.UiState
import com.amaral.driverlab.bench.Arm
import com.amaral.driverlab.driver.RequestedDriver
import com.amaral.driverlab.report.BenchmarkReport
import com.amaral.driverlab.telemetry.PreflightIssue
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Section 11 asks for three taps: choose drivers, choose a profile, run. The home
 * screen is one large button and a list of what has already been measured.
 */
@Composable
fun HomeScreen(
    state: UiState,
    onRun: () -> Unit,
    onCalibrate: () -> Unit,
    onShareLogs: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)

        Button(
            onClick = onRun,
            modifier = Modifier.fillMaxWidth().height(72.dp),
        ) {
            Text(stringResource(R.string.home_run), style = MaterialTheme.typography.titleLarge)
        }

        val nullTest = state.nullTest
        when {
            nullTest == null || !nullTest.exists -> Warning(stringResource(R.string.home_null_test_none))

            !nullTest.passed -> Warning(stringResource(R.string.home_null_test_failed))

            !nullTest.coversCurrentProfile ->
                Warning(stringResource(R.string.home_null_test_stale_profile))

            else -> Note(
                stringResource(
                    R.string.home_null_test_ok,
                    java.text.DateFormat.getDateInstance().format(
                        java.util.Date(nullTest.completedAtEpochMs),
                    ),
                    nullTest.noiseFloors.entries.joinToString {
                        "${it.key} ${"%.1f".format(it.value * 100)}%"
                    },
                ),
            )
        }

        OutlinedButton(onClick = onCalibrate, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.home_null_test_run))
        }

        Text(stringResource(R.string.home_recent), style = MaterialTheme.typography.titleMedium)
        if (state.report == null) {
            Text(stringResource(R.string.home_empty), style = MaterialTheme.typography.bodyMedium)
        } else {
            VerdictCard(state.report)
        }

        Spacer(Modifier.height(8.dp))
        // Kept on the first screen on purpose: when something goes wrong badly enough that the
        // run never starts, this is the only screen the user can still reach.
        OutlinedButton(onClick = onShareLogs, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.home_share_logs))
        }
        Note(stringResource(R.string.logs_location, BuildConfig.APPLICATION_ID))
    }
}

@Composable
fun SetupScreen(
    state: UiState,
    onImport: () -> Unit,
    onSelect: (Arm, RequestedDriver) -> Unit,
    onProfile: (Boolean) -> Unit,
    onContinue: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.headlineSmall)

        OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.setup_import))
        }
        state.importError?.let { Warning(it) }

        for (arm in listOf(Arm.A, Arm.B)) {
            Text("Driver ${arm.name}", style = MaterialTheme.typography.titleSmall)
            val selected = if (arm == Arm.A) state.armA else state.armB

            DriverOption(
                label = stringResource(R.string.setup_system_driver),
                supporting = stringResource(R.string.setup_system_driver_note),
                selected = selected is RequestedDriver.System,
                onSelect = { onSelect(arm, RequestedDriver.System) },
            )
            for (imported in state.packages) {
                DriverOption(
                    label = imported.displayName,
                    supporting = "SHA-256 ${imported.libraryChecksum.take(16)}… · " +
                        stringResource(R.string.setup_unverified),
                    selected = (selected as? RequestedDriver.Package)?.libraryChecksum ==
                        imported.libraryChecksum,
                    onSelect = { onSelect(arm, RequestedDriver.of(imported)) },
                )
            }
        }

        Text(stringResource(R.string.setup_profile), style = MaterialTheme.typography.titleSmall)
        DriverOption(
            label = stringResource(R.string.setup_profile_quick),
            supporting = "One workload, fewer frames. Finds a large difference, not a small one.",
            selected = state.quickProfile,
            onSelect = { onProfile(true) },
        )
        DriverOption(
            label = stringResource(R.string.setup_profile_complete),
            supporting = "The full protocol. The only profile that can support a ranking.",
            selected = !state.quickProfile,
            onSelect = { onProfile(false) },
        )

        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onContinue,
            enabled = state.canStart,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            Text(stringResource(R.string.setup_start))
        }
    }
}

@Composable
fun PreflightScreen(state: UiState, onStart: (Boolean) -> Unit, onRecheck: () -> Unit) {
    val report = state.preflight ?: return
    var override by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.preflight_title), style = MaterialTheme.typography.headlineSmall)

        if (report.issues.isEmpty()) {
            Text(stringResource(R.string.preflight_ready), style = MaterialTheme.typography.bodyLarge)
        } else {
            for (issue in report.issues) {
                if (issue.blocking) Warning(issue.message) else Note(issue.message)
            }
        }

        if (report.blockingIssues.isNotEmpty()) {
            Note(stringResource(R.string.preflight_override_warning))
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = override, onClick = { override = !override })
                Text(stringResource(R.string.preflight_override))
            }
            OutlinedButton(onClick = onRecheck, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.preflight_recheck))
            }
        }

        Button(
            onClick = { onStart(override) },
            enabled = report.blockingIssues.isEmpty() || override,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            Text(stringResource(R.string.setup_start))
        }
    }
}

@Composable
fun RunningScreen(state: UiState, onAbort: () -> Unit) {
    val progress = state.progress

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.running_title), style = MaterialTheme.typography.headlineSmall)

        if (progress == null) {
            CircularProgressIndicator()
            Text("Loading the driver…")
        } else {
            // Determinate from the first frame. The work is fixed in advance, so
            // there is never a reason to show a bar that does not know where it is.
            LinearProgressIndicator(
                progress = { progress.fraction.toFloat() },
                modifier = Modifier.fillMaxWidth().semantics {
                    contentDescription = "${(progress.fraction * 100).roundToInt()} percent complete"
                },
            )
            Text(
                stringResource(
                    R.string.running_execution,
                    progress.executionsDone,
                    progress.executionsTotal,
                ),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(progress.label, style = MaterialTheme.typography.bodyMedium)
            if (progress.workloadId.isNotBlank()) {
                Text(progress.workloadId, style = MaterialTheme.typography.bodySmall)
            }
            val celsius = progress.peakCelsius
            if (celsius != null) {
                Text(
                    stringResource(R.string.running_temperature, celsius),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onAbort, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.running_abort))
        }
    }
}

@Composable
fun ResultScreen(
    state: UiState,
    onPublish: () -> Unit,
    onExport: () -> Unit,
    onShareLogs: () -> Unit,
    onHome: () -> Unit,
) {
    var showDetail by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        state.runError?.let {
            Warning(it)
            // A failed run is exactly when the log matters, so the button is above "Back".
            OutlinedButton(onClick = onShareLogs, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.result_share_logs))
            }
            OutlinedButton(onClick = onHome, modifier = Modifier.fillMaxWidth()) { Text("Back") }
            return@Column
        }

        val report = state.report ?: return@Column
        VerdictCard(report)

        if (report.nullTest?.passed != true) {
            Warning(
                stringResource(R.string.result_ranking_blocked) + ". " +
                    stringResource(R.string.help_null_test),
            )
        }

        TextButton(onClick = { showDetail = !showDetail }) {
            Text(stringResource(R.string.result_details))
        }

        if (showDetail) {
            for (comparison in report.comparisons) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(comparison.workloadId, style = MaterialTheme.typography.titleSmall)
                        Text("${comparison.verdict} · ${comparison.reason}")
                        Text("%+.1f%% (95%%: %.3f – %.3f)".format(
                            comparison.percentDifference,
                            comparison.speedupInterval.low,
                            comparison.speedupInterval.high,
                        ))
                        Text("p = %.4f · Cliff's δ = %.3f · %d vs %d runs".format(
                            comparison.pValue, comparison.cliffsDelta, comparison.runsA, comparison.runsB,
                        ))
                        Text(
                            "Noise floor %.1f%%. %s".format(
                                comparison.noiseFloor * 100,
                                stringResource(R.string.help_noise_floor),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            for (failure in report.failures) {
                Warning("${failure.stage} on ${failure.workloadId}: ${failure.message}")
            }
        }

        Spacer(Modifier.height(8.dp))
        Button(onClick = onPublish, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.result_publish))
        }
        OutlinedButton(onClick = onExport, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.result_export))
        }
        OutlinedButton(onClick = onShareLogs, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.result_share_logs))
        }
        TextButton(onClick = onHome, modifier = Modifier.fillMaxWidth()) { Text("Home") }
    }
}

// ---- Small pieces ---------------------------------------------------------

@Composable
fun NullTestScreen(
    state: UiState,
    onStart: (RequestedDriver) -> Unit,
    onBack: () -> Unit,
) {
    // Defaults to the system driver because it needs no import, so a device can be
    // calibrated before the user has any package at all.
    var selected by remember { mutableStateOf<RequestedDriver>(RequestedDriver.System) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.null_test_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.null_test_explainer), style = MaterialTheme.typography.bodyMedium)
        Warning(stringResource(R.string.null_test_cost))

        Text(stringResource(R.string.null_test_driver), style = MaterialTheme.typography.titleMedium)
        DriverOption(
            label = stringResource(R.string.setup_system_driver),
            supporting = stringResource(R.string.setup_system_driver_note),
            selected = selected is RequestedDriver.System,
            onSelect = { selected = RequestedDriver.System },
        )
        for (pkg in state.packages) {
            DriverOption(
                label = pkg.displayName,
                supporting = "SHA-256 ${pkg.libraryChecksum.take(16)}…",
                selected = (selected as? RequestedDriver.Package)?.libraryChecksum == pkg.libraryChecksum,
                onSelect = { selected = RequestedDriver.of(pkg) },
            )
        }

        Button(
            onClick = { onStart(selected) },
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.null_test_start))
        }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.back))
        }
    }
}

@Composable
fun NullTestResultScreen(state: UiState, onHome: () -> Unit, onShareLogs: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        state.runError?.let {
            Warning(it)
            OutlinedButton(onClick = onShareLogs, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.result_share_logs))
            }
        }

        val nullTest = state.nullTest
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(
                        if (nullTest?.passed == true) R.string.null_test_passed else R.string.null_test_failed,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (nullTest != null) {
                    for ((workloadId, floor) in nullTest.noiseFloors) {
                        Text(
                            stringResource(
                                R.string.null_test_floor,
                                "%.1f%%".format(floor * 100),
                                workloadId,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        // The explanation is the statistics module's own sentence, not a rewrite of it, so
        // what the screen says and what the report says cannot drift apart.
        nullTest?.explanation?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        OutlinedButton(onClick = onShareLogs, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.result_share_logs))
        }
        Button(onClick = onHome, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.back))
        }
    }
}

@Composable
private fun VerdictCard(report: BenchmarkReport) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Plain language first, technical detail one tap away.
            //
            // `plainVerdict` is written in English on purpose: it travels in the published
            // report, where every reader of the leaderboard has to be able to read it. The
            // blocked case is the one the user sees most often and says nothing about their
            // own run, so the screen states it in their own language instead.
            Text(
                if (report.nullTest?.passed == true) {
                    report.plainVerdict
                } else {
                    stringResource(R.string.result_not_ranked)
                },
                style = MaterialTheme.typography.titleMedium,
            )
            val headline = report.comparisons.maxByOrNull { abs(it.percentDifference) }
            if (headline != null) {
                Text(
                    "${headline.labelA} vs ${headline.labelB} · ${headline.workloadId}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun DriverOption(label: String, supporting: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(Modifier.padding(start = 4.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(supporting, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Warning(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Filled.Warning,
            contentDescription = "Warning",
            tint = MaterialTheme.colorScheme.error,
        )
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Maps a preflight code to the shortest sentence that lets the user act on it. */
fun PreflightIssue.Code.shortAction(): String = when (this) {
    PreflightIssue.Code.BATTERY_TOO_LOW -> "Charge it above 40%, then unplug."
    PreflightIssue.Code.PLUGGED_IN -> "Unplug the charger."
    PreflightIssue.Code.ALREADY_THROTTLING -> "Let it cool until Android stops holding it back."
    PreflightIssue.Code.DEVICE_TOO_HOT -> "Let it cool down."
    PreflightIssue.Code.NOT_COOLED_DOWN -> "Wait a few more minutes since the last run."
    PreflightIssue.Code.NO_THERMAL_TELEMETRY -> "Nothing to do; the report will note it."
}
