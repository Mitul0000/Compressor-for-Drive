package com.digifello.compressorfordrive.ui.Core

import android.content.Context
import android.os.Build
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
        try {
            val stream = resolver.openOutputStream(media.uri, "wt") ?: throw IOException("Could not open file for writing")
            stream.use { out -> newFile.inputStream().use { it.copyTo(out) } }
            val written = resolver.openFileDescriptor(media.uri, "r")?.use { it.statSize } ?: -1L
            if (written != newFile.length()) throw IOException("Written size does not match")
        } catch (e: Exception) {
            restore(media, backup)
            throw e
        }
    }

    private fun restore(media: LocalMedia, backup: File) {
        try {
            resolver.openOutputStream(media.uri, "wt")?.use { out -> backup.inputStream().use { it.copyTo(out) } }
        } catch (_: Exception) {
        }
    }
}