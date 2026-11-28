package com.digifello.compressorfordrive.ui.Screens.Home

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.digifello.compressorfordrive.ui.Core.CompressionLevel
import com.digifello.compressorfordrive.ui.Core.DriveQuota
import com.digifello.compressorfordrive.ui.Core.KindSummary
import com.digifello.compressorfordrive.ui.Core.ScanResult
import com.digifello.compressorfordrive.ui.Core.estimatedSavedBytes
import com.digifello.compressorfordrive.ui.theme.CompressorForDriveTheme

@Composable
fun HomeScreen(vm: HomeViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmStart by remember { mutableStateOf(false) }

    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { vm.onConsentResult(it.data) }

    val accountLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { vm.onAccountPicked(it.data) }

    LaunchedEffect(Unit) {
        vm.accountPickerRequests.collect { accountLauncher.launch(it) }
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { vm.startCompression() }

    LaunchedEffect(Unit) {
        vm.consentRequests.collect { consentLauncher.launch(IntentSenderRequest.Builder(it).build()) }
    }

    HomeContent(
        state = state,
        onConnect = vm::connect,
        onChangeAccount = vm::changeAccount,
        onLevel = vm::setLevel,
        onWifiOnly = vm::setWifiOnly,
        onChargingOnly = vm::setChargingOnly,
        onScan = vm::scan,
        onStart = { confirmStart = true },
        onStop = vm::stop,
        onDismissError = vm::dismissError,
    )

    if (confirmStart) {
        AlertDialog(
            onDismissRequest = { confirmStart = false },
            title = { Text("Replace files in Drive?") },
            text = {
                Text(
                    "Each file is replaced by a smaller version and the old version is deleted. " +
                            "This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmStart = false
                    val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                            PackageManager.PERMISSION_GRANTED
                    if (needsPermission) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else vm.startCompression()
                }) { Text("Start") }
            },
            dismissButton = { TextButton(onClick = { confirmStart = false }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeContent(
    state: HomeUiState,
    onConnect: () -> Unit,
    onChangeAccount: () -> Unit,
    onLevel: (CompressionLevel) -> Unit,
    onWifiOnly: (Boolean) -> Unit,
    onChargingOnly: (Boolean) -> Unit,
    onScan: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onDismissError: () -> Unit,
) {
    Scaffold(topBar = { TopAppBar(title = { Text("Compressor for Drive") }) }) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            state.error?.let { ErrorBanner(it, onDismissError) }
            AccountSection(state, onConnect, onChangeAccount)
            StorageSection(state)
            LevelSection(state, onLevel, onWifiOnly, onChargingOnly)
            ScanSection(state, onScan)
            EstimateSection(state)
            ActionSection(state, onStart, onStop)
            ResultSection(state)
        }
    }
}

@Composable
private fun AccountSection(state: HomeUiState, onConnect: () -> Unit, onChange: () -> Unit) {
    Section("Account") {
        if (state.phase == Phase.Disconnected) {
            Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) { Text("Connect Google Drive") }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = state.email ?: "Connected",
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(onClick = onChange, enabled = !state.isBusy) { Text("Change account") }
            }
        }
    }
}

@Composable
private fun StorageSection(state: HomeUiState) {
    val connected = state.phase != Phase.Disconnected
    Section("Drive storage") {
        OutlinedCard(modifier = Modifier.fillMaxWidth().alpha(if (connected) 1f else 0.5f)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val quota = state.quota
                when {
                    !connected -> Text("Not connected")
                    quota == null -> Text("Loading...")
                    else -> {
                        val limit = quota.limit
                        Text(
                            if (limit != null) "${formatBytes(quota.usage)} used of ${formatBytes(limit)}"
                            else "${formatBytes(quota.usage)} used"
                        )
                        if (limit != null && limit > 0) {
                            LinearProgressIndicator(
                                progress = { (quota.usage.toFloat() / limit).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        if (quota.usageInTrash > 0) Note("Includes ${formatBytes(quota.usageInTrash)} in trash")
                    }
                }
            }
        }
    }
}

@Composable
private fun LevelSection(
    state: HomeUiState,
    onLevel: (CompressionLevel) -> Unit,
    onWifiOnly: (Boolean) -> Unit,
    onChargingOnly: (Boolean) -> Unit,
) {
    val enabled = state.phase != Phase.Disconnected && state.phase != Phase.Running
    Section("Compression level") {
        Column(modifier = Modifier.selectableGroup().alpha(if (enabled) 1f else 0.5f)) {
            LevelOption("Safe", "Smallest quality change, smaller savings", CompressionLevel.SAFE, state.level, enabled, onLevel)
            LevelOption("Balanced", "Recommended", CompressionLevel.BALANCED, state.level, enabled, onLevel)
            LevelOption("Max saving", "Largest savings, slight quality loss", CompressionLevel.MAX_SAVING, state.level, enabled, onLevel)
        }
        Column(modifier = Modifier.alpha(if (enabled) 1f else 0.5f)) {
            CheckOption("Use Wi-Fi only", state.wifiOnly, enabled, onWifiOnly)
            CheckOption("Only while charging", state.chargingOnly, enabled, onChargingOnly)
        }
    }
}

@Composable
private fun LevelOption(
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
    val canScan = state.phase == Phase.Connected || state.phase == Phase.Scanned || state.phase == Phase.Done
    Section("Scan") {
        OutlinedButton(onClick = onScan, enabled = canScan, modifier = Modifier.fillMaxWidth()) { Text("Scan Drive") }
        if (state.phase == Phase.Scanning) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("Scanning... ${"%,d".format(state.scanCount)} files")
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
        Note("Skipped: PDFs, other file types, small photos, files you do not own, and files already compressed.")
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
    val quota = state.quota ?: return
    if (state.phase == Phase.Done || state.phase == Phase.Scanning) return
    Section("Estimate") {
        if (scan.eligibleCount == 0) {
            Text("Nothing to compress.")
        } else {
            val saved = scan.estimatedSavedBytes(state.level)
            Text("Estimated storage after compression: ${formatBytes((quota.usage - saved).coerceAtLeast(0))} (about ${formatBytes(saved)} saved)")
            Note("Estimate only. Actual savings depend on your files.")
        }
    }
}

@Composable
private fun ActionSection(state: HomeUiState, onStart: () -> Unit, onStop: () -> Unit) {
    val ready = state.phase == Phase.Scanned && (state.scan?.eligibleCount ?: 0) > 0
    Section("Compression") {
        Button(
            onClick = onStart,
            enabled = ready,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Start compression") }

        if (state.phase == Phase.Running) {
            val p = state.progress
            if (p == null || p.total == 0) {
                Text("Waiting to start...")
                if (state.wifiOnly || state.chargingOnly) Note("The job starts when the Wi-Fi or charging condition is met.")
            } else {
                LinearProgressIndicator(
                    progress = { p.done.toFloat() / p.total },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Processing ${p.done} of ${p.total} files")
                if (p.current.isNotEmpty()) Note(p.current, maxLines = 1)
                Note("Saved so far: ${formatBytes(p.savedBytes)}")
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
        ResultRow("Storage before", formatBytes(r.beforeBytes))
        ResultRow("Storage after", r.afterBytes?.let { formatBytes(it) } ?: "Unknown")
        ResultRow("Space freed", formatBytes(r.savedBytes))
        Note("Google can take a few minutes to update the storage figure.")
    }
}

@Composable
private fun ResultRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text(value)
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

@Composable
private fun Note(text: String, maxLines: Int = Int.MAX_VALUE) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
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

private val HomeUiState.isBusy: Boolean get() = phase == Phase.Scanning || phase == Phase.Running

private fun formatBytes(bytes: Long): String {
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

private val sampleQuota = DriveQuota(limit = 15L shl 30, usage = 12_400L shl 20, usageInDrive = 9L shl 30, usageInTrash = 600L shl 20)
private val sampleScan = ScanResult(KindSummary(840, 5L shl 30), KindSummary(60, 4L shl 30), KindSummary(340, 2L shl 30))

@Preview(showBackground = true)
@Composable
private fun PreviewDisconnected() = CompressorForDriveTheme(dynamicColor = false) {
    HomeContent(HomeUiState(ready = true), {}, {}, {}, {}, {}, {}, {}, {}, {})
}

@Preview(showBackground = true)
@Composable
private fun PreviewScanned() = CompressorForDriveTheme(dynamicColor = false) {
    HomeContent(
        HomeUiState(ready = true, phase = Phase.Scanned, email = "name@gmail.com", quota = sampleQuota, scan = sampleScan),
        {}, {}, {}, {}, {}, {}, {}, {}, {},
    )
}

@Preview(showBackground = true)
@Composable
private fun PreviewRunning() = CompressorForDriveTheme(dynamicColor = false) {
    HomeContent(
        HomeUiState(
            ready = true, phase = Phase.Running, email = "name@gmail.com", quota = sampleQuota, scan = sampleScan,
            progress = RunProgress(120, 860, "IMG_2041.jpg", 98, 20, 2, 640L shl 20),
        ),
        {}, {}, {}, {}, {}, {}, {}, {}, {},
    )
}