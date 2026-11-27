package com.digifello.compressorfordrive.ui.Core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.exifinterface.media.ExifInterface
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

enum class CompressionLevel(
    val jpegQuality: Int,
    val ssimThreshold: Double,
    val videoBitrateFactor: Double,
    val videoShortSideCap: Int?,
) {
    SAFE(90, 0.97, 0.70, null),
    BALANCED(85, 0.95, 0.50, null),
    MAX_SAVING(75, 0.93, 0.35, 1080),
}

sealed interface CompressionOutcome {
    data class Success(
        val file: File,
        val newName: String,
        val mimeType: String,
        val originalSize: Long,
        val newSize: Long,
        val similarity: Double,
    ) : CompressionOutcome

    data class Skipped(val reason: String) : CompressionOutcome
}

class Compressor(context: Context) {

    private val appContext = context.applicationContext

    suspend fun compress(
        input: File,
        fileName: String,
        mimeType: String,
        level: CompressionLevel,
        workDir: File,
        onProgress: (Float) -> Unit = {},
    ): CompressionOutcome = when {
        mimeType == "image/jpeg" || mimeType == "image/png" -> compressImage(input, fileName, mimeType, level, workDir)
        mimeType.startsWith("video/") -> compressVideo(input, fileName, mimeType, level, workDir, onProgress)
        mimeType == "application/pdf" -> CompressionOutcome.Skipped("PDF compression is not added yet")
        else -> CompressionOutcome.Skipped("File type not supported")
    }

    private suspend fun compressImage(
        input: File,
        fileName: String,
        mimeType: String,
        level: CompressionLevel,
        workDir: File,
    ): CompressionOutcome = withContext(Dispatchers.Default) {
        val originalSize = input.length()
        if (originalSize < MIN_IMAGE_BYTES) return@withContext CompressionOutcome.Skipped("Already small")

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(input.absolutePath, bounds)
        if (bounds.outWidth <= 0) return@withContext CompressionOutcome.Skipped("Not a readable image")
        if (bounds.outWidth.toLong() * bounds.outHeight > MAX_PIXELS) {
            return@withContext CompressionOutcome.Skipped("Image too large to process safely")
        }

        val isPng = mimeType == "image/png"
        val newName = if (isPng) fileName.substringBeforeLast('.', fileName) + ".jpg" else fileName
        val out = File(workDir, "out_${System.nanoTime()}.jpg")

        var bitmap = BitmapFactory.decodeFile(input.absolutePath)
            ?: return@withContext CompressionOutcome.Skipped("Could not decode image")
        try {
            if (isPng || bitmap.hasAlpha()) bitmap = flattenOnWhite(bitmap)

            var quality = level.jpegQuality
            repeat(2) { attempt ->
                FileOutputStream(out).use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
                if (!isPng) copyExif(input, out)

                val newSize = out.length()
                if (newSize > originalSize * (1 - MIN_SAVING)) {
                    out.delete()
                    return@withContext CompressionOutcome.Skipped("Not enough saving")
                }
                val score = SimilarityCheck.imageSimilarity(input, out)
                if (score >= level.ssimThreshold) {
                    return@withContext CompressionOutcome.Success(
                        out, newName, "image/jpeg", originalSize, newSize, score
                    )
                }
                if (attempt == 0) quality = minOf(quality + 5, 95)
            }
            out.delete()
            CompressionOutcome.Skipped("Quality check failed")
        } finally {
            bitmap.recycle()
        }
    }

    private fun flattenOnWhite(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.WHITE)
            drawBitmap(src, 0f, 0f, null)
        }
        src.recycle()
        return out
    }

    private fun copyExif(from: File, to: File) {
        try {
            val src = ExifInterface(from.absolutePath)
            val dst = ExifInterface(to.absolutePath)
            for (tag in EXIF_TAGS) src.getAttribute(tag)?.let { dst.setAttribute(tag, it) }
            dst.saveAttributes()
        } catch (_: Exception) {
        }
    }

    private suspend fun compressVideo(
        input: File,
        fileName: String,
        mimeType: String,
        level: CompressionLevel,
        workDir: File,
        onProgress: (Float) -> Unit,
    ): CompressionOutcome {
        val originalSize = input.length()
        val info = readVideoInfo(input) ?: return CompressionOutcome.Skipped("Could not read video")
        if (info.bitrate in 1 until MIN_VIDEO_BITRATE) return CompressionOutcome.Skipped("Already low bitrate")

        val targetBitrate = (info.bitrate * level.videoBitrateFactor).toInt()
            .coerceAtLeast(MIN_TARGET_BITRATE)
        val out = File(workDir, "out_${System.nanoTime()}.mp4")

        try {
            runTransformer(input, out, targetBitrate, info, level, onProgress)
        } catch (e: Exception) {
            out.delete()
            return CompressionOutcome.Skipped("Video encoding failed: ${e.message}")
        }

        val newSize = out.length()
        if (newSize > originalSize * (1 - MIN_SAVING)) {
            out.delete()
            return CompressionOutcome.Skipped("Not enough saving")
        }
        val check = withContext(Dispatchers.Default) { SimilarityCheck.videoSimilarity(input, out) }
        if (!check.passes(level.ssimThreshold)) {
            out.delete()
            return CompressionOutcome.Skipped("Quality check failed")
        }
        val name = if (fileName.endsWith(".mp4", ignoreCase = true)) fileName
        else fileName.substringBeforeLast('.', fileName) + ".mp4"
        return CompressionOutcome.Success(out, name, "video/mp4", originalSize, newSize, check.similarity)
    }

    private class VideoInfo(val bitrate: Int, val width: Int, val height: Int)

    private suspend fun readVideoInfo(file: File): VideoInfo? = withContext(Dispatchers.IO) {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.absolutePath)
            val bitrate = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0
            var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return@withContext null
            var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return@withContext null
            val rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rotation == 90 || rotation == 270) { val t = w; w = h; h = t }
            VideoInfo(bitrate, w, h)
        } catch (_: Exception) {
            null
        } finally {
            r.release()
        }
    }

    private suspend fun runTransformer(
        input: File,
        output: File,
        bitrate: Int,
        info: VideoInfo,
        level: CompressionLevel,
        onProgress: (Float) -> Unit,
    ) = withContext(Dispatchers.Main) {
        val videoEffects = buildList<Effect> {
            val cap = level.videoShortSideCap
            if (cap != null && minOf(info.width, info.height) > cap) {
                val targetHeight = if (info.height <= info.width) {
                    cap
                } else {
                    val scaled = (info.height.toFloat() * cap / info.width).roundToInt()
                    scaled - (scaled % 2)
                }
                add(Presentation.createForHeight(targetHeight))
            }
        }
        val edited = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(input)))
            .setEffects(Effects(emptyList(), videoEffects))
            .build()

        val encoderFactory = DefaultEncoderFactory.Builder(appContext)
            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
            .build()

        var transformer: Transformer? = null
        coroutineScope {
            val poller = launch {
                while (true) {
                    delay(500)
                    val t = transformer ?: continue
                    val holder = ProgressHolder()
                    if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                        onProgress(holder.progress / 100f)
                    }
                }
            }
            try {
                suspendCancellableCoroutine<Unit> { cont ->
                    val t = Transformer.Builder(appContext)
                        .setVideoMimeType(MimeTypes.VIDEO_H264)
                        .setEncoderFactory(encoderFactory)
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                cont.resume(Unit)
                            }

                            override fun onError(
                                composition: Composition,
                                exportResult: ExportResult,
                                exportException: ExportException,
                            ) {
                                cont.resumeWithException(exportException)
                            }
                        })
                        .build()
                    transformer = t
                    cont.invokeOnCancellation {
                        Handler(Looper.getMainLooper()).post { t.cancel() }
                    }
                    t.start(edited, output.absolutePath)
                }
            } finally {
                poller.cancel()
            }
        }
    }

    private companion object {
        const val MIN_IMAGE_BYTES = 100_000L
        const val MAX_PIXELS = 50_000_000L
        const val MIN_SAVING = 0.10
        const val MIN_VIDEO_BITRATE = 2_000_000
        const val MIN_TARGET_BITRATE = 1_000_000

        val EXIF_TAGS = listOf(
            ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
            ExifInterface.TAG_SUBSEC_TIME, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
            ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_ORIENTATION,
            ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
            ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_FLASH, ExifInterface.TAG_WHITE_BALANCE,
        )
    }
}