package com.amaral.driverlab.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.amaral.driverlab.app.ui.AmaralTheme
import com.amaral.driverlab.app.ui.HomeScreen
import com.amaral.driverlab.app.ui.PreflightScreen
import com.amaral.driverlab.app.ui.ResultScreen
import com.amaral.driverlab.app.ui.RunningScreen
import com.amaral.driverlab.app.ui.SetupScreen
import com.amaral.driverlab.report.ReportFiles

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AmaralTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot(onShareFile = ::shareFile)
                }
            }
        }
    }

    /**
     * Shares a file the app produced, by content URI. Both the report and the diagnostics zip
     * leave this way — the latter because the log directory under `Android/data` is not
     * browsable on Android 11 and later.
     *
     * The contents never enter the Intent. An extra is a Binder transaction, bounded at about
     * a megabyte for the whole process, and a real report is larger: the Export button used to
     * hand 2.2 MB to startActivity and took the app down with it.
     */
    private fun shareFile(file: java.io.File) {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            this,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            file,
        )
        val isArchive = file.extension.equals("zip", ignoreCase = true)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = if (isArchive) "application/zip" else ReportFiles.MIME_TYPE
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val title = getString(if (isArchive) R.string.result_share_logs else R.string.result_export)
        startActivity(Intent.createChooser(intent, title))
    }
}

@Composable
private fun AppRoot(onShareFile: (java.io.File) -> Unit) {
    val viewModel: BenchViewModel = viewModel()
    val state by viewModel.uiState.collectAsState()

    // Any MIME type: driver archives arrive from file managers that label .zip
    // inconsistently, and a filter that looks tidy is a filter that hides the file
    // the user is trying to pick.
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::importPackage) }

    val shareLogs = {
        val bundle = viewModel.diagnosticsBundle()
        if (bundle != null) onShareFile(bundle)
    }

    when (state.step) {
        Step.Home -> HomeScreen(
            state = state,
            onRun = { viewModel.goTo(Step.Setup) },
            onShareLogs = shareLogs,
        )

        Step.Setup -> SetupScreen(
            state = state,
            onImport = { picker.launch(arrayOf("*/*")) },
            onSelect = viewModel::selectArm,
            onProfile = viewModel::selectProfile,
            onContinue = viewModel::runPreflight,
        )

        Step.Preflight -> PreflightScreen(
            state = state,
            onStart = viewModel::start,
            onRecheck = viewModel::runPreflight,
        )

        Step.Running -> RunningScreen(state) { viewModel.goTo(Step.Home) }

        Step.Result -> ResultScreen(
            state = state,
            onPublish = { viewModel.exportReportFile()?.let(onShareFile) },
            onExport = { viewModel.exportReportFile()?.let(onShareFile) },
            onShareLogs = shareLogs,
            onHome = { viewModel.goTo(Step.Home) },
        )
    }
}
