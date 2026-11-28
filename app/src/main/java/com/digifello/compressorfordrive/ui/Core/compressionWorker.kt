package com.digifello.compressorfordrive.ui.Core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.digifello.compressorfordrive.MainActivity
import kotlinx.coroutines.CancellationException
import java.io.File

/** Compresses the queued photos and videos on the phone. Fully offline. */
class CompressionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private enum class Kind { COMPRESSED, SKIPPED, FAILED }

    private class FileResult(val kind: Kind, val saved: Long = 0, val needsPermission: Boolean = false)

    private var done = 0
    private var compressed = 0
    private var skipped = 0
    private var failed = 0
    private var saved = 0L

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val level = AppSettings(ctx).level
        val before = inputData.getLong(KEY_BEFORE, 0L)

        if (!MediaAccess.hasAccess(ctx)) {
            return Result.failure(output(0, before, "Allow access to photos and videos first."))
        }
        try { setForeground(foregroundInfo(0, 0)) } catch (_: Exception) { }

        val keys = QueueFile.read(ctx)
        val total = keys.size
        val scanner = LocalScanner(ctx)
        val store = ProcessedStore.forApp(ctx)
        val writer = MediaWriter(ctx)
        val compressor = Compressor(ctx)

        val workDir = File(ctx.cacheDir, "compress").apply {
            deleteRecursively()
            mkdirs()
        }

        var consecutiveFailures = 0
        try {
            for (key in keys) {
                val media = QueueFile.parse(key)?.let { (isVideo, id) -> scanner.get(isVideo, id) }

                // Already handled by an earlier attempt of this job (for example the phone stopped the job
                // when the app was closed and it restarted): carry on from where it stopped, do not redo it.
                if (media != null && !store.shouldProcess(media, level)) {
                    done++
                    if (done % 20 == 0) report(total, "", before)
                    continue
                }

                report(total, media?.name.orEmpty(), before)

                val result = if (media == null) {
                    FileResult(Kind.SKIPPED) // deleted or changed since the scan
                } else try {
                    processFile(media, level, store, writer, compressor, workDir)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SecurityException) {
                    FileResult(Kind.FAILED, needsPermission = true)
                } catch (e: Exception) {
                    FileResult(Kind.FAILED)
                }

                if (result.needsPermission) {
                    return Result.failure(
                        output(total, before, "Permission to edit files was not granted. Start again and tap Allow.")
                    )
                }
                when (result.kind) {
                    Kind.COMPRESSED -> { compressed++; saved += result.saved; consecutiveFailures = 0 }
                    Kind.SKIPPED -> { skipped++; consecutiveFailures = 0 }
                    Kind.FAILED -> { failed++; consecutiveFailures++ }
                }
                done++
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    return Result.failure(output(total, before, "Stopped after repeated errors."))
                }
            }
        } finally {
            workDir.deleteRecursively()
        }
        return Result.success(output(total, before, null))
    }

    private suspend fun processFile(
        media: LocalMedia,
        level: CompressionLevel,
        store: ProcessedStore,
        writer: MediaWriter,
        compressor: Compressor,
        workDir: File,
    ): FileResult {
        if (workDir.usableSpace < media.size * 2 + MIN_FREE_BYTES) return FileResult(Kind.SKIPPED)

        val input = File(workDir, "in_${media.key}")
        try {
            if (!writer.copyOriginal(media, input)) return FileResult(Kind.FAILED)

            when (val outcome = compressor.compress(input, media.name, media.mime, level, workDir)) {
                is CompressionOutcome.Skipped -> {
                    store.markSkipped(media, level)
                    return FileResult(Kind.SKIPPED)
                }
                is CompressionOutcome.Success -> try {
                    // `input` is the untouched original: it is the backup if the write fails.
                    writer.replace(media, outcome.file, input)
                    store.markCompressed(media, outcome.newSize, level)
                    return FileResult(Kind.COMPRESSED, (media.size - outcome.newSize).coerceAtLeast(0))
                } finally {
                    outcome.file.delete()
                }
            }
        } finally {
            input.delete()
        }
    }

    private suspend fun report(total: Int, current: String, before: Long) {
        setProgress(output(total, before, null, current))
        try { setForeground(foregroundInfo(done, total)) } catch (_: Exception) { }
    }

    private fun output(total: Int, before: Long, error: String?, current: String = ""): Data =
        workDataOf(
            KEY_DONE to done, KEY_TOTAL to total, KEY_CURRENT to current,
            KEY_COMPRESSED to compressed, KEY_SKIPPED to skipped, KEY_FAILED to failed,
            KEY_SAVED to saved, KEY_BEFORE to before, KEY_ERROR to (error ?: ""),
        )

    private fun foregroundInfo(done: Int, total: Int): ForegroundInfo {
        val ctx = applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Compression", NotificationManager.IMPORTANCE_LOW)
            ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val openApp = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = WorkManager.getInstance(ctx).createCancelPendingIntent(id)
        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Compressing your files")
            .setContentText(if (total > 0) "$done of $total files" else "Preparing")
            .setProgress(total, done, total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
            .addAction(0, "Stop", stop)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val WORK_NAME = "compress_local"
        const val KEY_BEFORE = "before"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_CURRENT = "current"
        const val KEY_COMPRESSED = "compressed"
        const val KEY_SKIPPED = "skipped"
        const val KEY_FAILED = "failed"
        const val KEY_SAVED = "saved"
        const val KEY_ERROR = "error"

        private const val CHANNEL_ID = "compression"
        private const val NOTIFICATION_ID = 1001
        private const val MIN_FREE_BYTES = 100L * 1024 * 1024
        private const val MAX_CONSECUTIVE_FAILURES = 5
    }
}