package com.digifello.compressorfordrive.ui.Core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
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

/**
 * PRESERVED  - no visible difference (PNG is re-saved with zero loss).
 * BALANCED   - a slight, hard-to-notice loss.
 * MAX_SAVING - frees the most space, with a small quality drop.
 */
enum class CompressionLevel(
    val jpegQuality: Int,
    val ssimThreshold: Double,
    val videoBitrateFactor: Double,
    val videoShortSideCap: Int?,
) {
    PRESERVED(92, 0.98, 0.75, null),
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
        mimeType == "image/jpeg" ->
            if (isAlreadyCompressed(input, level)) CompressionOutcome.Skipped("Already compressed")
            else compressImage(input, fileName, level, workDir)
        mimeType == "image/png" -> compressPng(input, fileName, workDir)
        mimeType.startsWith("video/") -> compressVideo(input, fileName, level, workDir, onProgress)
        else -> CompressionOutcome.Skipped("File type not supported")
    }

    /** PNG is always handled losslessly: same pixels, just packed smaller. Kept only if verified identical. */
    private suspend fun compressPng(
        input: File,
        fileName: String,
        workDir: File,
    ): CompressionOutcome = withContext(Dispatchers.Default) {
        val originalSize = input.length()
        if (originalSize < MIN_IMAGE_BYTES) return@withContext CompressionOutcome.Skipped("Already small")

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(input.absolutePath, bounds)
        if (bounds.outWidth <= 0) return@withContext CompressionOutcome.Skipped("Not a readable image")
        if (bounds.outWidth.toLong() * bounds.outHeight > MAX_PNG_PIXELS) {
            return@withContext CompressionOutcome.Skipped("Image too large to process safely")
        }

        val bitmap = BitmapFactory.decodeFile(input.absolutePath)
            ?: return@withContext CompressionOutcome.Skipped("Could not decode image")
        val out = File(workDir, "out_${System.nanoTime()}.png")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && bitmap.colorSpace?.isSrgb == false) {
                return@withContext CompressionOutcome.Skipped("Wide colour profile")
            }
            FileOutputStream(out).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val newSize = out.length()
            if (newSize > originalSize * (1 - MIN_PNG_SAVING)) {
                out.delete()
                return@withContext CompressionOutcome.Skipped("Not enough saving")
            }
            val check = BitmapFactory.decodeFile(out.absolutePath)
            val identical = check != null && bitmap.sameAs(check)
            check?.recycle()
            if (!identical) {
                out.delete()
                return@withContext CompressionOutcome.Skipped("Lossless check failed")
            }
            CompressionOutcome.Success(out, fileName, "image/png", originalSize, newSize, 1.0)
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun compressImage(
        input: File,
        fileName: String,
        level: CompressionLevel,
        workDir: File,
    ): CompressionOutcome = withContext(Dispatchers.Default) {
        val originalSize = input.length()
        // MIN_IMAGE_BYTES is the single top-level constant in scanResult.kt
        if (originalSize < MIN_IMAGE_BYTES) return@withContext CompressionOutcome.Skipped("Already small")

        if (hasUnsupportedExtras(input)) {
            return@withContext CompressionOutcome.Skipped("Has HDR or Motion Photo data")
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(input.absolutePath, bounds)
        if (bounds.outWidth <= 0) return@withContext CompressionOutcome.Skipped("Not a readable image")
        if (bounds.outWidth.toLong() * bounds.outHeight > MAX_PIXELS) {
            return@withContext CompressionOutcome.Skipped("Image too large to process safely")
        }

        val newName = fileName
        val out = File(workDir, "out_${System.nanoTime()}.jpg")

        val bitmap = BitmapFactory.decodeFile(input.absolutePath)
            ?: return@withContext CompressionOutcome.Skipped("Could not decode image")
        try {
            // JPEG cannot hold transparency; never flatten the user's transparent PNGs.
            if (bitmap.hasAlpha()) return@withContext CompressionOutcome.Skipped("Has transparency")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && bitmap.colorSpace?.isSrgb == false) {
                return@withContext CompressionOutcome.Skipped("Wide colour profile")
            }

            val threshold = level.ssimThreshold

            var quality = level.jpegQuality
            repeat(2) { attempt ->
                FileOutputStream(out).use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
                copyExif(input, out, level)

                val newSize = out.length()
                if (newSize >= originalSize) {
                    out.delete()
                    return@withContext CompressionOutcome.Skipped("Not enough saving")
                }
                val score = SimilarityCheck.imageSimilarity(input, out)
                if (score >= threshold) {
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

    /** Motion Photos and Ultra HDR gain maps live in extra data a re-encode would silently drop. */
    private fun hasUnsupportedExtras(file: File): Boolean = try {
        val buffer = ByteArray(HEADER_SCAN_BYTES)
        val n = file.inputStream().use { it.read(buffer) }
        if (n <= 0) false else {
            val head = String(buffer, 0, n, Charsets.ISO_8859_1)
            EXTRA_MARKERS.any { head.contains(it) }
        }
    } catch (_: Exception) {
        false
    }

    /**
     * True when this photo carries our marker from an earlier run at the same or a stronger level.
     * The marker lives inside the file, so it still works after reinstalling the app or moving the photo.
     */
    private fun isAlreadyCompressed(file: File, level: CompressionLevel): Boolean = try {
        val tag = ExifInterface(file.absolutePath).getAttribute(ExifInterface.TAG_SOFTWARE).orEmpty()
        if (!tag.startsWith(MARKER_PREFIX)) false
        else {
            val doneLevel = CompressionLevel.entries.firstOrNull { tag.endsWith("level=${it.name}") }
            doneLevel != null && doneLevel.ordinal >= level.ordinal
        }
    } catch (_: Exception) {
        false
    }

    private fun copyExif(from: File, to: File, level: CompressionLevel) {
        try {
            val src = ExifInterface(from.absolutePath)
            val dst = ExifInterface(to.absolutePath)
            for (tag in EXIF_TAGS) src.getAttribute(tag)?.let { dst.setAttribute(tag, it) }
            dst.setAttribute(ExifInterface.TAG_SOFTWARE, "$MARKER_PREFIX/1 level=${level.name}")
            dst.saveAttributes()
        } catch (_: Exception) {
        }
    }

    private suspend fun compressVideo(
        input: File,
        fileName: String,
        level: CompressionLevel,
        workDir: File,
        onProgress: (Float) -> Unit,
    ): CompressionOutcome {
        val originalSize = input.length()
        val info = readVideoInfo(input) ?: return CompressionOutcome.Skipped("Could not read video")
        if (info.hdr) return CompressionOutcome.Skipped("HDR video")
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
        if (newSize >= originalSize) {
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

    private class VideoInfo(val bitrate: Int, val width: Int, val height: Int, val hdr: Boolean)

    private suspend fun readVideoInfo(file: File): VideoInfo? = withContext(Dispatchers.IO) {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.absolutePath)
            val bitrate = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0
            var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return@withContext null
            var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return@withContext null
            val rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rotation == 90 || rotation == 270) { val t = w; w = h; h = t }
            val hdr = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && run {
                val transfer = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COLOR_TRANSFER)?.toIntOrNull()
                transfer == MediaFormat.COLOR_TRANSFER_ST2084 || transfer == MediaFormat.COLOR_TRANSFER_HLG
            }
            VideoInfo(bitrate, w, h, hdr)
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
                        .setVideoMimeType(MimeTypes.VIDEO_H265) // falls back to H.264 if the phone cannot encode HEVC
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
        const val MAX_PIXELS = 50_000_000L
        const val MAX_PNG_PIXELS = 24_000_000L
        const val MIN_PNG_SAVING = 0.05
        const val MIN_VIDEO_BITRATE = 2_000_000
        const val MIN_TARGET_BITRATE = 1_000_000
        const val HEADER_SCAN_BYTES = 256 * 1024

        val EXTRA_MARKERS = listOf("MotionPhoto", "MicroVideo", "hdrgm", "GainMap")

        const val MARKER_PREFIX = "CompressorForDrive"

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
            ExifInterface.TAG_IMAGE_DESCRIPTION, ExifInterface.TAG_USER_COMMENT,
            ExifInterface.TAG_ARTIST, ExifInterface.TAG_COPYRIGHT,
            "LensMake", "LensModel", "LensSpecification", "BodySerialNumber", // plain names: older library versions lack the constants
            ExifInterface.TAG_IMAGE_UNIQUE_ID,
            ExifInterface.TAG_EXPOSURE_PROGRAM, ExifInterface.TAG_EXPOSURE_MODE, ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
            ExifInterface.TAG_METERING_MODE, ExifInterface.TAG_LIGHT_SOURCE, ExifInterface.TAG_SCENE_CAPTURE_TYPE,
            ExifInterface.TAG_SCENE_TYPE, ExifInterface.TAG_SENSING_METHOD, ExifInterface.TAG_CONTRAST,
            ExifInterface.TAG_SATURATION, ExifInterface.TAG_SHARPNESS, ExifInterface.TAG_GAIN_CONTROL,
            ExifInterface.TAG_DIGITAL_ZOOM_RATIO, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
            ExifInterface.TAG_SUBJECT_DISTANCE, ExifInterface.TAG_SUBJECT_DISTANCE_RANGE,
            ExifInterface.TAG_BRIGHTNESS_VALUE, ExifInterface.TAG_SHUTTER_SPEED_VALUE, ExifInterface.TAG_APERTURE_VALUE,
            ExifInterface.TAG_MAX_APERTURE_VALUE, ExifInterface.TAG_ISO_SPEED_RATINGS,
            ExifInterface.TAG_GPS_PROCESSING_METHOD, ExifInterface.TAG_GPS_IMG_DIRECTION,
            ExifInterface.TAG_GPS_IMG_DIRECTION_REF, ExifInterface.TAG_GPS_SPEED, ExifInterface.TAG_GPS_SPEED_REF,
            ExifInterface.TAG_GPS_MAP_DATUM, ExifInterface.TAG_GPS_AREA_INFORMATION,
        )
    }
}