package com.digifello.compressorfordrive.ui.Screens.Home

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.digifello.compressorfordrive.ui.Core.CompressionLevel
import com.digifello.compressorfordrive.ui.Core.CompressionWorker
import com.digifello.compressorfordrive.ui.Core.ConnectDrive
import com.digifello.compressorfordrive.ui.Core.DriveApi
import com.digifello.compressorfordrive.ui.Core.DriveAuthState
import com.digifello.compressorfordrive.ui.Core.DriveQuota
import com.digifello.compressorfordrive.ui.Core.ScanResult
import com.digifello.compressorfordrive.ui.Core.toScanResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

enum class Phase { Disconnected, Connected, Scanning, Scanned, Running, Done }

data class RunProgress(
    val done: Int = 0,
    val total: Int = 0,
    val current: String = "",
    val compressed: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
    val savedBytes: Long = 0,
)

data class RunResult(
    val compressed: Int,
    val skipped: Int,
    val failed: Int,
    val savedBytes: Long,
    val beforeBytes: Long,
    val afterBytes: Long?,
)

data class HomeUiState(
    val ready: Boolean = false,
    val phase: Phase = Phase.Disconnected,
    val email: String? = null,
    val quota: DriveQuota? = null,
    val level: CompressionLevel = CompressionLevel.BALANCED,
    val wifiOnly: Boolean = true,
    val chargingOnly: Boolean = false,
    val scanCount: Int = 0,
    val scan: ScanResult? = null,
    val progress: RunProgress? = null,
    val result: RunResult? = null,
    val error: String? = null,
)

class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val connect = ConnectDrive(app)
    private val api = DriveApi({ force -> connect.accessToken(force) })
    private val workManager = WorkManager.getInstance(app)

    private val _state = MutableStateFlow(
        HomeUiState(
            level = runCatching { CompressionLevel.valueOf(prefs.getString("level", "")!!) }
                .getOrDefault(CompressionLevel.BALANCED),
            wifiOnly = prefs.getBoolean("wifiOnly", true),
            chargingOnly = prefs.getBoolean("chargingOnly", false),
        )
    )
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private val _consentRequests = MutableSharedFlow<PendingIntent>(extraBufferCapacity = 1)
    val consentRequests: SharedFlow<PendingIntent> = _consentRequests.asSharedFlow()

    private var workJob: Job? = null

    /**
     * The run this screen is tracking. WorkManager keeps the last finished job around, so without
     * this a finished job from an earlier session would show up as a fresh "Done" result on launch.
     */
    private var currentRunId: UUID? = null

    init {
        viewModelScope.launch {
            val auth = try {
                connect.reloadUser()
            } catch (e: Exception) {
                DriveAuthState.Disconnected
            }
            if (auth is DriveAuthState.Connected) applyConnected(auth)
            _state.update { it.copy(ready = true) }
        }
    }

    fun connect() {
        viewModelScope.launch {
            try {
                when (val s = connect.signInWithGoogle()) {
                    is DriveAuthState.NeedsConsent -> _consentRequests.emit(s.pendingIntent)
                    is DriveAuthState.Connected -> applyConnected(s)
                    DriveAuthState.Disconnected -> Unit
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail("Could not connect: ${e.message}")
            }
        }
    }

    fun onConsentResult(data: Intent?) {
        viewModelScope.launch {
            try {
                applyConnected(connect.handleConsentResult(data))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail("Drive access was not granted")
            }
        }
    }

    fun changeAccount() {
        viewModelScope.launch {
            workJob?.cancel()
            connect.disconnect()
            _state.update { HomeUiState(ready = true, level = it.level, wifiOnly = it.wifiOnly, chargingOnly = it.chargingOnly) }
            connect()
        }
    }

    private suspend fun applyConnected(auth: DriveAuthState.Connected) {
        _state.update { it.copy(email = auth.email, phase = Phase.Connected, error = null) }
        refreshQuota()
        observeWork()
    }

    private suspend fun refreshQuota() {
        try {
            val quota = api.getQuota()
            _state.update { it.copy(quota = quota) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail("Could not read storage: ${e.message}")
        }
    }

    fun setLevel(level: CompressionLevel) {
        prefs.edit().putString("level", level.name).apply()
        _state.update { it.copy(level = level) }
    }

    fun setWifiOnly(value: Boolean) {
        prefs.edit().putBoolean("wifiOnly", value).apply()
        _state.update { it.copy(wifiOnly = value) }
    }

    fun setChargingOnly(value: Boolean) {
        prefs.edit().putBoolean("chargingOnly", value).apply()
        _state.update { it.copy(chargingOnly = value) }
    }

    fun scan() {
        viewModelScope.launch {
            _state.update { it.copy(phase = Phase.Scanning, scanCount = 0, scan = null, error = null) }
            try {
                val files = api.listFiles { n -> _state.update { it.copy(scanCount = n) } }
                val quota = api.getQuota()
                _state.update { it.copy(phase = Phase.Scanned, scan = files.toScanResult(), quota = quota, result = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(phase = Phase.Connected) }
                fail("Scan failed: ${e.message}")
            }
        }
    }

    fun startCompression() {
        val s = _state.value
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (s.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresCharging(s.chargingOnly)
            .build()
        val request = OneTimeWorkRequestBuilder<CompressionWorker>()
            .setInputData(
                workDataOf(
                    CompressionWorker.KEY_LEVEL to s.level.name,
                    CompressionWorker.KEY_BEFORE to (s.quota?.usage ?: 0L),
                )
            )
            .setConstraints(constraints)
            .build()
        currentRunId = request.id
        workManager.enqueueUniqueWork(CompressionWorker.WORK_NAME, ExistingWorkPolicy.KEEP, request)
        _state.update { it.copy(phase = Phase.Running, progress = null, result = null, error = null) }
        observeWork()
    }

    fun stop() {
        workManager.cancelUniqueWork(CompressionWorker.WORK_NAME)
    }

    private fun observeWork() {
        if (workJob?.isActive == true) return
        workJob = viewModelScope.launch {
            workManager.getWorkInfosForUniqueWorkFlow(CompressionWorker.WORK_NAME).collect { infos ->
                val info = infos.firstOrNull() ?: return@collect
                when (info.state) {
                    WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED -> {
                        // Also covers a run still going after the app process was restarted.
                        currentRunId = info.id
                        _state.update { it.copy(phase = Phase.Running, progress = info.progress.toProgress()) }
                    }

                    WorkInfo.State.SUCCEEDED,
                    WorkInfo.State.FAILED,
                    WorkInfo.State.CANCELLED -> {
                        // A finished job we are not tracking is an old one: ignore it.
                        if (info.id != currentRunId) return@collect
                        currentRunId = null
                        when (info.state) {
                            WorkInfo.State.SUCCEEDED -> finishRun(info.outputData, null)
                            WorkInfo.State.FAILED ->
                                finishRun(info.outputData, info.outputData.getString(CompressionWorker.KEY_ERROR))
                            else -> {
                                val last = _state.value.progress
                                finishRun(last?.toData() ?: Data.EMPTY, "Stopped. Files already processed stay compressed.")
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun finishRun(out: Data, error: String?) {
        refreshQuota()
        val after = _state.value.quota?.usage
        val result = RunResult(
            compressed = out.getInt(CompressionWorker.KEY_COMPRESSED, 0),
            skipped = out.getInt(CompressionWorker.KEY_SKIPPED, 0),
            failed = out.getInt(CompressionWorker.KEY_FAILED, 0),
            savedBytes = out.getLong(CompressionWorker.KEY_SAVED, 0L),
            beforeBytes = out.getLong(CompressionWorker.KEY_BEFORE, 0L),
            afterBytes = after,
        )
        _state.update {
            it.copy(
                phase = Phase.Done,
                progress = null,
                result = result,
                error = error?.takeIf { e -> e.isNotEmpty() },
            )
        }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    private fun fail(message: String) {
        _state.update { it.copy(error = message) }
    }

    private fun Data.toProgress() = RunProgress(
        done = getInt(CompressionWorker.KEY_DONE, 0),
        total = getInt(CompressionWorker.KEY_TOTAL, 0),
        current = getString(CompressionWorker.KEY_CURRENT).orEmpty(),
        compressed = getInt(CompressionWorker.KEY_COMPRESSED, 0),
        skipped = getInt(CompressionWorker.KEY_SKIPPED, 0),
        failed = getInt(CompressionWorker.KEY_FAILED, 0),
        savedBytes = getLong(CompressionWorker.KEY_SAVED, 0L),
    )

    private fun RunProgress.toData() = workDataOf(
        CompressionWorker.KEY_COMPRESSED to compressed,
        CompressionWorker.KEY_SKIPPED to skipped,
        CompressionWorker.KEY_FAILED to failed,
        CompressionWorker.KEY_SAVED to savedBytes,
        CompressionWorker.KEY_BEFORE to (_state.value.quota?.usage ?: 0L),
    )
}