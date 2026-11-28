package com.digifello.compressorfordrive.ui.Core

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.digifello.compressorfordrive.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs once a day when "Check daily" is on. It only looks for new files and posts a reminder.
 * Android asks for permission before any file is changed, so the actual compression starts from the app.
 */
class AutoCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val settings = AppSettings(ctx)
        if (!settings.dailyCheck || settings.scope != Scope.NEW_ONLY) return Result.success()
        if (!MediaAccess.hasAccess(ctx)) return Result.success()

        val level = settings.level
        val outcome = withContext(Dispatchers.IO) {
            LocalScanner(ctx).scan(settings.sinceSeconds).classify(ProcessedStore.forApp(ctx), level)
        }
        if (outcome.summary.eligibleCount == 0) return Result.success()

        val saved = outcome.summary.estimatedSavedBytes(level)
        notify(ctx, outcome.summary.eligibleCount, saved)
        return Result.success()
    }

    private fun notify(ctx: Context, count: Int, savedBytes: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val manager = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Reminders", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val openApp = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val mb = (savedBytes / (1024 * 1024)).coerceAtLeast(1)
        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("$count new files can be compressed")
            .setContentText("Could free about $mb MB. Tap to review.")
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        const val WORK_NAME = "auto_check"
        private const val CHANNEL_ID = "reminders"
        private const val NOTIFICATION_ID = 1002
    }
}