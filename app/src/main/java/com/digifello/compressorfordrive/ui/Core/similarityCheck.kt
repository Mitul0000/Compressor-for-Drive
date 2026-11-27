package com.digifello.compressorfordrive.ui.Core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.media.MediaMetadataRetriever
import java.io.File
import kotlin.math.abs
import kotlin.math.max


object SimilarityCheck {

    private const val IMAGE_COMPARE_SIDE = 1024
    private const val VIDEO_COMPARE_SIDE = 512
    private const val WINDOW = 8
    private const val STEP = 4
    private const val VIDEO_SAMPLES = 5

    data class VideoCheck(
        val similarity: Double,
        val durationMatches: Boolean,
        val audioMatches: Boolean,
    ) {
        fun passes(threshold: Double) = durationMatches && audioMatches && similarity >= threshold
    }



    fun imageSimilarity(original: File, compressed: File): Double {
        val a = decodeForCompare(original, IMAGE_COMPARE_SIDE) ?: return 0.0
        val b = decodeForCompare(compressed, IMAGE_COMPARE_SIDE)
        if (b == null) {
            a.recycle()
            return 0.0
        }
        return try {
            bitmapSimilarity(a, b)
        } finally {
            a.recycle()
            b.recycle()
        }
    }



    fun videoSimilarity(original: File, compressed: File): VideoCheck {
        val ra = MediaMetadataRetriever()
        val rb = MediaMetadataRetriever()
        try {
            ra.setDataSource(original.absolutePath)
            rb.setDataSource(compressed.absolutePath)

            val durA = ra.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val durB = rb.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val durationOk = durA > 0 && abs(durA - durB) <= max(500L, durA / 100)

            val audioA = ra.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            val audioB = rb.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"

            if (durA <= 0) return VideoCheck(0.0, false, audioA == audioB)

            val scores = mutableListOf<Double>()
            for (i in 1..VIDEO_SAMPLES) {
                val timeUs = durA * 1000L * (2 * i - 1) / (2 * VIDEO_SAMPLES) // 10%, 30%, 50%, 70%, 90%
                val fa = ra.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST) ?: continue
                val fb = rb.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                if (fb == null) {
                    fa.recycle()
                    continue
                }
                val sa = shrinkOnWhite(fa, VIDEO_COMPARE_SIDE)
                val sb = shrinkOnWhite(fb, VIDEO_COMPARE_SIDE)
                scores += bitmapSimilarity(sa, sb)
                sa.recycle(); sb.recycle()
            }
            val average = if (scores.isEmpty()) 0.0 else scores.average()
            return VideoCheck(average, durationOk, audioA == audioB)
        } finally {
            ra.release()
            rb.release()
        }
    }



    fun bitmapSimilarity(a: Bitmap, b: Bitmap): Double {
        val bb = if (b.width == a.width && b.height == a.height) b
        else Bitmap.createScaledBitmap(b, a.width, a.height, true)
        try {
            val w = a.width
            val h = a.height
            if (w < WINDOW || h < WINDOW) return 0.0
            val ga = luma(a)
            val gb = luma(bb)

            val c1 = (0.01 * 255) * (0.01 * 255)
            val c2 = (0.03 * 255) * (0.03 * 255)
            val n = (WINDOW * WINDOW).toDouble()
            var total = 0.0
            var count = 0

            var y = 0
            while (y + WINDOW <= h) {
                var x = 0
                while (x + WINDOW <= w) {
                    var sa = 0.0; var sb = 0.0; var saa = 0.0; var sbb = 0.0; var sab = 0.0
                    for (j in 0 until WINDOW) {
                        val row = (y + j) * w + x
                        for (i in 0 until WINDOW) {
                            val p = ga[row + i].toDouble()
                            val q = gb[row + i].toDouble()
                            sa += p; sb += q
                            saa += p * p; sbb += q * q; sab += p * q
                        }
                    }
                    val ma = sa / n
                    val mb = sb / n
                    val va = saa / n - ma * ma
                    val vb = sbb / n - mb * mb
                    val cov = sab / n - ma * mb
                    total += ((2 * ma * mb + c1) * (2 * cov + c2)) /
                            ((ma * ma + mb * mb + c1) * (va + vb + c2))
                    count++
                    x += STEP
                }
                y += STEP
            }
            return if (count == 0) 0.0 else total / count
        } finally {
            if (bb !== b) bb.recycle()
        }
    }



    private fun luma(bmp: Bitmap): IntArray {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val c = px[i]
            px[i] = ((0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) + 0.5).toInt()
        }
        return px
    }

    private fun decodeForCompare(file: File, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
        return shrinkOnWhite(decoded, maxSide)
    }

    private fun shrinkOnWhite(src: Bitmap, maxSide: Int): Bitmap {
        val scale = minOf(1f, maxSide.toFloat() / max(src.width, src.height))
        val w = max(1, (src.width * scale).toInt())
        val h = max(1, (src.height * scale).toInt())
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(src, w, h, true) else src
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.WHITE)
            drawBitmap(scaled, 0f, 0f, null)
        }
        if (scaled !== src) scaled.recycle()
        src.recycle()
        return out
    }
}