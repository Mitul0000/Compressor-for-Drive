package com.digifello.compressorfordrive.ui.Core

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import java.io.File
import java.io.IOException

/** Reads the original from, and writes the smaller version back to, the same MediaStore entry. */
class MediaWriter(context: Context) {

    private val resolver = context.applicationContext.contentResolver

    /** Copies the original into [dest]. On Android 10+ the unredacted original is used so GPS data survives. */
    fun copyOriginal(media: LocalMedia, dest: File): Boolean {
        val uris = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !media.isVideo) {
                add(MediaStore.setRequireOriginal(media.uri))
            }
            add(media.uri)
        }
        for (uri in uris) {
            try {
                val stream = resolver.openInputStream(uri) ?: continue
                stream.use { input -> dest.outputStream().use { input.copyTo(it) } }
                return true
            } catch (_: SecurityException) {
            } catch (_: IOException) {
            }
        }
        return false
    }

    /**
     * Overwrites the original in place so album, date and name stay the same. If anything goes wrong
     * the untouched original (kept in [backup]) is written back.
     */
    fun replace(media: LocalMedia, newFile: File, backup: File) {
        // Writing to the file makes Android stamp it with "modified now", which would push the photo to the
        // top of the album. Remember the real dates so they can be put back afterwards.
        val dates = readDates(media)
        try {
            val stream = resolver.openOutputStream(media.uri, "wt") ?: throw IOException("Could not open file for writing")
            stream.use { out -> newFile.inputStream().use { it.copyTo(out) } }
            val written = resolver.openFileDescriptor(media.uri, "r")?.use { it.statSize } ?: -1L
            if (written != newFile.length()) throw IOException("Written size does not match")
        } catch (e: Exception) {
            restore(media, backup)
            writeDates(media, dates)
            throw e
        }
        if (dates != null) {
            writeDates(media, dates)
            // Android rescans the file shortly after it is written and can reset the date again,
            // so put it back once more after the rescan has had time to finish.
            val handler = Handler(Looper.getMainLooper())
            handler.postDelayed({ writeDates(media, dates) }, 2_000)
            handler.postDelayed({ writeDates(media, dates) }, 8_000)
        }
    }

    private class Dates(val modifiedSeconds: Long, val takenMillis: Long?)

    private fun takenColumn(media: LocalMedia) =
        if (media.isVideo) MediaStore.Video.Media.DATE_TAKEN else MediaStore.Images.Media.DATE_TAKEN

    private fun readDates(media: LocalMedia): Dates? = try {
        resolver.query(
            media.uri,
            arrayOf(MediaStore.MediaColumns.DATE_MODIFIED, takenColumn(media)),
            null, null, null,
        )?.use { c ->
            if (!c.moveToFirst()) null
            else Dates(c.getLong(0), if (c.isNull(1)) null else c.getLong(1))
        }
    } catch (_: Exception) {
        null
    }

    private fun writeDates(media: LocalMedia, dates: Dates?) {
        if (dates == null) return
        try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DATE_MODIFIED, dates.modifiedSeconds)
                dates.takenMillis?.let { put(takenColumn(media), it) }
            }
            resolver.update(media.uri, values, null, null)
        } catch (_: Exception) {
        }
    }

    private fun restore(media: LocalMedia, backup: File) {
        try {
            resolver.openOutputStream(media.uri, "wt")?.use { out -> backup.inputStream().use { it.copyTo(out) } }
        } catch (_: Exception) {
        }
    }
}