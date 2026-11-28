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

class CompressionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private enum class Kind { COMPRESSED, SKIPPED, FAILED }
    private class FileResult(val kind: Kind, val saved: Long = 0)

    private var done = 0
    private var compressed = 0
    private var skipped = 0
    private var failed = 0
    private var saved = 0L

    override suspend fun doWork(): Result {
        val level = runCatching { CompressionLevel.valueOf(inputData.getString(KEY_LEVEL).orEmpty()) }
            .getOrDefault(CompressionLevel.BALANCED)
        val before = inputData.getLong(KEY_BEFORE, 0L)

        try { setForeground(foregroundInfo(0, 0)) } catch (_: Exception) { }

        val connect = ConnectDrive(applicationContext)
        // CHANGED: token provider now takes forceRefresh
        val tokens: suspend (Boolean) -> String = { forceRefresh -> connect.accessToken(forceRefresh) }
        val api = DriveApi(tokens)
        val uploader = DriveUploader(tokens)
        val compressor = Compressor(applicationContext)

        val workDir = File(applicationContext.cacheDir, "compress").apply {
            deleteRecursively()
            mkdirs()
        }

        val allFiles = try {
            api.listFiles()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result.failure(output(0, before, e.message ?: "Could not read Drive"))
        }
        val candidates = allFiles.filter { it.isCandidate() }.sortedByDescending { it.size }
        val pending = allFiles.filter { it.ownedByMe && it.needsCleanup }

        val total = candidates.size
        var consecutiveFailures = 0
        try {
            for (file in pending) {
                try {
                    // CHANGED: pass the stored checksum; skip if we don't have one
                    val expected = file.expectedMd5 ?: continue
                    uploader.deleteOldRevisions(file.id, expected)
                    uploader.setState(file.id, DriveApi.STATE_DONE)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }

            for (file in candidates) {
                report(total, file.name, before)
                val result = try {
                    processFile(file, level, api, uploader, compressor, workDir)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    FileResult(Kind.FAILED)
                }
                when (result.kind) {
                    Kind.COMPRESSED -> { compressed++; saved += result.saved; consecutiveFailures = 0 }
                    Kind.SKIPPED -> { skipped++; consecutiveFailures = 0 }
                    Kind.FAILED -> { failed++; consecutiveFailures++ }
                }
                done++
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    return Result.failure(output(total, before, "Stopped after repeated errors. Check your connection and account."))
                }
            }
        } finally {
            workDir.deleteRecursively()
        }
        return Result.success(output(total, before, null))
    }

    private suspend fun processFile(
        file: DriveFile,
        level: CompressionLevel,
        api: DriveApi,
        uploader: DriveUploader,
        compressor: Compressor,
        workDir: File,
    ): FileResult {
        if (workDir.usableSpace < file.size * 2 + MIN_FREE_BYTES) return FileResult(Kind.SKIPPED)

        val input = File(workDir, "in_${file.id}")
        try {
            api.download(file.id, input)
            if (input.length() != file.size) return FileResult(Kind.FAILED)

            when (val outcome = compressor.compress(input, file.name, file.mimeType, level, workDir)) {
                is CompressionOutcome.Skipped -> return FileResult(Kind.SKIPPED)
                is CompressionOutcome.Success -> try {
                    val upload = try {
                        // CHANGED: pass modifiedTime and expectedCurrentMd5
                        uploader.replaceFile(
                            fileId = file.id,
                            local = outcome.file,
                            newName = outcome.newName,
                            newMimeType = outcome.mimeType,
                            modifiedTime = file.modifiedTime,
                            expectedCurrentMd5 = file.md5,
                        )
                    } catch (e: SourceChangedException) {
                        // NEW: file was edited in Drive meanwhile, don't overwrite it
                        return FileResult(Kind.SKIPPED)
                    } catch (e: UploadVerificationException) {
                        uploader.setState(file.id, DriveApi.STATE_FAILED)
                        throw e
                    }

                    // CHANGED: pass the md5 of what we uploaded
                    uploader.deleteOldRevisions(file.id, upload.md5)

                    try {
                        uploader.setState(file.id, DriveApi.STATE_DONE)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                    }
                    return FileResult(Kind.COMPRESSED, (file.size - upload.size).coerceAtLeast(0))
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
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Compressing Drive files")
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
        const val WORK_NAME = "compress_drive"
        const val KEY_LEVEL = "level"
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