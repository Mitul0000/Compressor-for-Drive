package com.digifello.compressorfordrive.ui.Screens.Home

import android.app.Application
import android.app.PendingIntent
import android.os.Build
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.digifello.compressorfordrive.ui.Core.AppSettings
import com.digifello.compressorfordrive.ui.Core.AutoCheckWorker
import com.digifello.compressorfordrive.ui.Core.CompressionLevel
import com.digifello.compressorfordrive.ui.Core.CompressionWorker
import com.digifello.compressorfordrive.ui.Core.DeviceStorage
import com.digifello.compressorfordrive.ui.Core.LocalMedia
import com.digifello.compressorfordrive.ui.Core.LocalScanner
import com.digifello.compressorfordrive.ui.Core.MAX_FILES_PER_RUN
import com.digifello.compressorfordrive.ui.Core.MediaAccess
import com.digifello.compressorfordrive.ui.Core.ProcessedStore
import com.digifello.compressorfordrive.ui.Core.QueueFile
import com.digifello.compressorfordrive.ui.Core.ScanResult
import com.digifello.compressorfordrive.ui.Core.Scope
import com.digifello.compressorfordrive.ui.Core.classify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.TimeUnit

enum class Phase { Idle, Scanning, Scanned, Running, Done }

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
    val usedBefore: Long,
    val usedAfter: Long,
)

data class HomeUiState(
    val hasAccess: Boolean = false,
    val phase: Phase = Phase.Idle,
    val storage: DeviceStorage? = null,
    val level: CompressionLevel = CompressionLevel.BALANCED,
    val chargingOnly: Boolean = false,
    val scan: ScanResult? = null,
    val progress: RunProgress? = null,
    val result: RunResult? = null,
    val error: String? = null,
)

class HomeViewModel(private val app: Application) : AndroidViewModel(app) {

    private val settings = AppSettings(app)
    private val workManager = WorkManager.getInstance(app)

    private val _state = MutableStateFlow(
        HomeUiState(
            hasAccess = MediaAccess.hasAccess(app),
            storage = DeviceStorage.read(),
            level = settings.level,
            chargingOnly = settings.chargingOnly,
        )
    )
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    /** Android's "allow this app to edit these files" prompt. */
    private val _writeRequests = MutableSharedFlow<PendingIntent>(extraBufferCapacity = 1)
    val writeRequests: SharedFlow<PendingIntent> = _writeRequests.asSharedFlow()

    private var candidates: List<LocalMedia> = emptyList()
    private var workJob: Job? = null
    private var runUsedBefore = 0L

    /**
     * The run this screen is tracking. WorkManager keeps the last finished job around, so without
     * this a finished job from an earlier session would show up as a fresh "Done" result on launch.
     */
    private var currentRunId: UUID? = null

    init {
        // Older builds had a daily reminder job; make sure it is gone on devices that still have it scheduled.
        workManager.cancelUniqueWork("auto_check")
        observeWork()
    }

    fun refreshAccess() {
        _state.update { it.copy(hasAccess = MediaAccess.hasAccess(app), storage = DeviceStorage.read()) }
    }

    // ---- settings -------------------------------------------------------------------------

    fun setLevel(level: CompressionLevel) {
        settings.level = level
        _state.update { it.copy(level = level).invalidated() }
    }




    fun setChargingOnly(value: Boolean) {
        settings.chargingOnly = value
        _state.update { it.copy(chargingOnly = value) }
    }


    /** A different level or scope changes which files qualify, so the old scan no longer applies. */
    private fun HomeUiState.invalidated() =
        if (phase == Phase.Scanned || phase == Phase.Done) copy(phase = Phase.Idle, scan = null) else this

    // ---- scan -----------------------------------------------------------------------------

    fun scan() {
        if (!MediaAccess.hasAccess(app)) {
            refreshAccess()
            return
        }
        val s = _state.value
        viewModelScope.launch {
            _state.update { it.copy(phase = Phase.Scanning, scan = null, error = null) }
            try {
                val outcome = withContext(Dispatchers.IO) {
                    LocalScanner(app).scan().classify(ProcessedStore.forApp(app), s.level)
                }
                candidates = outcome.candidates
                _state.update {
                    it.copy(phase = Phase.Scanned, scan = outcome.summary, storage = DeviceStorage.read(), result = null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(phase = Phase.Idle) }
                fail("Scan failed: ${e.message}")
            }
        }
    }

    // ---- run ------------------------------------------------------------------------------

    /** Asks Android for permission to edit the files (one prompt), then starts the background job. */
    fun requestWriteAccess() {
        val items = candidates.take(MAX_FILES_PER_RUN)
        if (items.isEmpty()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val request = MediaStore.createWriteRequest(app.contentResolver, items.map { it.uri })
                _writeRequests.tryEmit(request)
            } catch (e: Exception) {
                fail("Could not ask for permission: ${e.message}")
            }
        } else {
            startCompression()
        }
    }

    fun onWriteResult(granted: Boolean) {
        if (granted) startCompression()
        else fail("Permission to edit files was not given, so nothing was changed.")
    }

    private fun startCompression() {
        val s = _state.value
        val items = candidates.take(MAX_FILES_PER_RUN)
        if (items.isEmpty()) return

        QueueFile.write(app, items)
        runUsedBefore = DeviceStorage.read().used

        val constraints = Constraints.Builder()
            .setRequiresCharging(s.chargingOnly)
            .setRequiresBatteryNotLow(true)
            .build()
        val request = OneTimeWorkRequestBuilder<CompressionWorker>()
            .setInputData(workDataOf(CompressionWorker.KEY_BEFORE to runUsedBefore))
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

    private fun finishRun(out: Data, error: String?) {
        val storage = DeviceStorage.read()
        val result = RunResult(
            compressed = out.getInt(CompressionWorker.KEY_COMPRESSED, 0),
            skipped = out.getInt(CompressionWorker.KEY_SKIPPED, 0),
            failed = out.getInt(CompressionWorker.KEY_FAILED, 0),
            savedBytes = out.getLong(CompressionWorker.KEY_SAVED, 0L),
            usedBefore = out.getLong(CompressionWorker.KEY_BEFORE, runUsedBefore),
            usedAfter = storage.used,
        )
        candidates = emptyList()
        _state.update {
            it.copy(
                phase = Phase.Done,
                progress = null,
                scan = null,
                storage = storage,
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
        CompressionWorker.KEY_BEFORE to runUsedBefore,
    )
}