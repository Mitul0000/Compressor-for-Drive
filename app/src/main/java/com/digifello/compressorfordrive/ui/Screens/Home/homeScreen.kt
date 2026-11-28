package com.digifello.compressorfordrive.ui.Screens.Home

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.WindowInsets
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.digifello.compressorfordrive.ui.Core.CompressionLevel
import com.digifello.compressorfordrive.ui.Core.DeviceStorage
import com.digifello.compressorfordrive.ui.Core.KindSummary
import com.digifello.compressorfordrive.ui.Core.MAX_FILES_PER_RUN
import com.digifello.compressorfordrive.ui.Core.MediaAccess
import com.digifello.compressorfordrive.ui.Core.ScanResult
import com.digifello.compressorfordrive.ui.Core.estimatedSavedBytes
import com.digifello.compressorfordrive.ui.theme.CompressorForDriveTheme
import java.text.DateFormat
import java.util.Date

@Composable
fun HomeScreen(vm: HomeViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmStart by remember { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        vm.refreshAccess()
        onPauseOrDispose { }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { vm.refreshAccess() }

    // Android's one-time "allow this app to edit these files" prompt.
    val writeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { vm.onWriteResult(it.resultCode == Activity.RESULT_OK) }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { vm.requestWriteAccess() }

    LaunchedEffect(Unit) {
        vm.writeRequests.collect { writeLauncher.launch(IntentSenderRequest.Builder(it).build()) }
    }

    fun needsNotificationPermission() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED

    HomeContent(
        state = state,
        actions = Actions(
            onGrantAccess = { permissionLauncher.launch(MediaAccess.permissionsToRequest()) },
            onLevel = vm::setLevel,
            onChargingOnly = vm::setChargingOnly,
            onScan = vm::scan,
            onStart = { confirmStart = true },
            onStop = vm::stop,
            onDismissError = vm::dismissError,
        ),
    )

    if (confirmStart) {
        AlertDialog(
            onDismissRequest = { confirmStart = false },
            title = { Text("Replace your originals?") },
            text = {
                Text(
                    "Each photo and video is replaced by a smaller version, keeping its name, date and album. " +
                            "Android will ask once for permission to edit the files. " +
                            "There is no backup, so this cannot be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmStart = false
                    if (needsNotificationPermission()) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else vm.requestWriteAccess()
                }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { confirmStart = false }) { Text("Cancel") } },
        )
    }
}

private class Actions(
    val onGrantAccess: () -> Unit,
    val onLevel: (CompressionLevel) -> Unit,
    val onChargingOnly: (Boolean) -> Unit,
    val onScan: () -> Unit,
    val onStart: () -> Unit,
    val onStop: () -> Unit,
    val onDismissError: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeContent(state: HomeUiState, actions: Actions) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("Storage Compressor") }) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            state.error?.let { ErrorBanner(it, actions.onDismissError) }
            OfflineCard()
            if (!state.hasAccess) AccessSection(actions.onGrantAccess)
            StorageSection(state)
            QualitySection(state, actions.onLevel)
            OptionsSection(state, actions)
            ScanSection(state, actions.onScan)
            EstimateSection(state)
            ActionSection(state, actions.onStart, actions.onStop)
            ResultSection(state)
        }
    }
}

@Composable
private fun OfflineCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Works completely offline",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                "No account, no internet permission, no uploads. Everything is compressed on this phone " +
                        "and your files never leave it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun AccessSection(onGrant: () -> Unit) {
    Section("Access") {
        Note("To find and shrink your photos and videos, the app needs permission to see them. They stay on your phone.")
        Button(onClick = onGrant, modifier = Modifier.fillMaxWidth()) { Text("Allow access to photos and videos") }
    }
}

@Composable
private fun StorageSection(state: HomeUiState) {
    Section("Phone storage") {
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val storage = state.storage
                if (storage == null || storage.total <= 0) {
                    Text("Unavailable")
                } else {
                    Text("${formatBytes(storage.used)} used of ${formatBytes(storage.total)}")
                    LinearProgressIndicator(
                        progress = { (storage.used.toFloat() / storage.total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Note("${formatBytes(storage.free)} free")
                }
            }
        }
    }
}

@Composable
private fun QualitySection(state: HomeUiState, onLevel: (CompressionLevel) -> Unit) {
    Section("Compression quality") {
        LevelPicker(state.level, state.phase != Phase.Running, onLevel)
    }
}

/** The three quality options. Shared by the Main and Test screens. */
@Composable
internal fun LevelPicker(selected: CompressionLevel, enabled: Boolean, onLevel: (CompressionLevel) -> Unit) {
    Column(modifier = Modifier.selectableGroup().alpha(if (enabled) 1f else 0.5f)) {
        LevelOption(
            "Quality preserved",
            "Looks identical to the original. PNG files are shrunk with no loss at all. Smallest saving.",
            CompressionLevel.PRESERVED, selected, enabled, onLevel,
        )
        LevelOption(
            "Balanced (recommended)",
            "A slight loss you are unlikely to notice. Good saving.",
            CompressionLevel.BALANCED, selected, enabled, onLevel,
        )
        LevelOption(
            "Max storage",
            "Frees the most space, with a small drop in quality. Videos are capped at 1080p.",
            CompressionLevel.MAX_SAVING, selected, enabled, onLevel,
        )
    }
    Note("Each result is checked against the original. Files that would look noticeably worse are left untouched.")
}

@Composable
internal fun LevelOption(
    title: String,
    description: String,
    value: CompressionLevel,
    selected: CompressionLevel,
    enabled: Boolean,
    onSelect: (CompressionLevel) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = value == selected, enabled = enabled, role = Role.RadioButton) { onSelect(value) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = value == selected, onClick = null, enabled = enabled)
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(title)
            Note(description)
        }
    }
}

@Composable
private fun OptionsSection(state: HomeUiState, actions: Actions) {
    val enabled = state.phase != Phase.Running
    Section("Options") {
        Column(modifier = Modifier.alpha(if (enabled) 1f else 0.5f)) {
            CheckOption("Only while charging", state.chargingOnly, enabled, actions.onChargingOnly)
        }
    }
}

@Composable
private fun CheckOption(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Text(label, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun ScanSection(state: HomeUiState, onScan: () -> Unit) {
    val canScan = state.hasAccess && state.phase != Phase.Scanning && state.phase != Phase.Running
    Section("Scan") {
        OutlinedButton(onClick = onScan, enabled = canScan, modifier = Modifier.fillMaxWidth()) { Text("Scan this phone") }
        if (state.phase == Phase.Scanning) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("Scanning...")
            }
        }
        state.scan?.let { ScanTable(it) }
    }
}

@Composable
private fun ScanTable(scan: ScanResult) {
    Column {
        TableRow("File type", "Files", "Size", header = true)
        HorizontalDivider()
        TableRow("Photos and images", scan.photos)
        TableRow("Videos", scan.videos)
        TableRow("Skipped", scan.skipped)
        Note("Skipped: small files, files already compressed, and formats the app does not handle.")
        if (scan.eligibleCount > MAX_FILES_PER_RUN) {
            Note("The ${"%,d".format(MAX_FILES_PER_RUN)} biggest files are handled in each run. Run again for the rest.")
        }
    }
}

@Composable
private fun TableRow(label: String, summary: KindSummary) =
    TableRow(label, "%,d".format(summary.count), formatBytes(summary.bytes))

@Composable
private fun TableRow(a: String, b: String, c: String, header: Boolean = false) {
    val style = if (header) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(a, style = style, modifier = Modifier.weight(2f))
        Text(b, style = style, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
        Text(c, style = style, modifier = Modifier.weight(1.2f), textAlign = TextAlign.End)
    }
}

@Composable
private fun EstimateSection(state: HomeUiState) {
    val scan = state.scan ?: return
    val storage = state.storage ?: return
    if (state.phase != Phase.Scanned) return
    Section("Estimate") {
        if (scan.eligibleCount == 0) {
            Text("Nothing to compress.")
        } else {
            val saved = scan.estimatedSavedBytes(state.level)
            Text("Free space after compression: about ${formatBytes(storage.free + saved)} (${formatBytes(saved)} freed)")
            Note("Estimate only. Actual savings depend on your files.")
        }
    }
}

@Composable
private fun ActionSection(state: HomeUiState, onStart: () -> Unit, onStop: () -> Unit) {
    val ready = state.phase == Phase.Scanned && (state.scan?.eligibleCount ?: 0) > 0
    Section("Compression") {
        Button(onClick = onStart, enabled = ready, modifier = Modifier.fillMaxWidth()) { Text("Start compression") }

        if (state.phase == Phase.Running) {
            val p = state.progress
            if (p == null || p.total == 0) {
                Text("Waiting to start...")
                if (state.chargingOnly) Note("The job starts when the phone is charging.")
            } else {
                LinearProgressIndicator(progress = { p.done.toFloat() / p.total }, modifier = Modifier.fillMaxWidth())
                Text("Processing ${p.done} of ${p.total} files")
                if (p.current.isNotEmpty()) Note(p.current, maxLines = 1)
                Note("Freed so far: ${formatBytes(p.savedBytes)}")
            }
            TextButton(onClick = onStop) { Text("Stop") }
            Note("Runs in the background. You can close the app.")
        }
    }
}

@Composable
private fun ResultSection(state: HomeUiState) {
    val r = state.result ?: return
    if (state.phase != Phase.Done) return
    Section("Last run") {
        ResultRow("Files compressed", "%,d".format(r.compressed))
        ResultRow("Files skipped", "%,d".format(r.skipped))
        if (r.failed > 0) ResultRow("Files failed", "%,d".format(r.failed))
        if (r.usedBefore > 0) ResultRow("Storage used before", formatBytes(r.usedBefore))
        ResultRow("Storage used after", formatBytes(r.usedAfter))
        ResultRow("Space freed", formatBytes(r.savedBytes))
        Note("Your gallery may take a moment to show the new file sizes.")
    }
}

@Composable
internal fun ResultRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text(value)
    }
}

@Composable
internal fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

@Composable
internal fun Note(text: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
internal fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

internal fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var i = -1
    do {
        value /= 1024
        i++
    } while (value >= 1024 && i < units.lastIndex)
    return "%.1f %s".format(value, units[i])
}

private val sampleStorage = DeviceStorage(total = 128L shl 30, free = 9L shl 30)
private val sampleScan = ScanResult(KindSummary(840, 5L shl 30), KindSummary(60, 4L shl 30), KindSummary(340, 2L shl 30))
private val noActions = Actions({}, {}, {}, {}, {}, {}, {})
@Preview(showBackground = true)
@Composable
private fun PreviewScanned() = CompressorForDriveTheme(dynamicColor = false) {
    HomeContent(
        HomeUiState(hasAccess = true, phase = Phase.Scanned, storage = sampleStorage, scan = sampleScan),
        noActions,
    )
}