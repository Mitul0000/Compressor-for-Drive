package com.digifello.compressorfordrive.ui.Core

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException

/**
 * Saves a finished file to the gallery as a brand-new item (album "Storage Compressor").
 * Unlike [MediaWriter] it never touches an existing file.
 */
class GalleryWriter(context: Context) {

    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    /** Throws [IOException] if the file could not be saved. */
    fun saveNew(file: File, displayName: String, mimeType: String, isVideo: Boolean): Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveScoped(file, displayName, mimeType, isVideo)
        } else {
            saveLegacy(file, displayName, mimeType, isVideo)
        }

    /** Android 10+: no storage permission needed to add our own file to the gallery. */
    private fun saveScoped(file: File, displayName: String, mimeType: String, isVideo: Boolean): Uri {
        val collection = if (isVideo) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val folder = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$folder/$FOLDER")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("Could not create the gallery entry")
        try {
            val out = resolver.openOutputStream(uri, "w") ?: throw IOException("Could not open the gallery file")
            out.use { o -> file.inputStream().use { it.copyTo(o) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return uri
        } catch (e: Exception) {
            try { resolver.delete(uri, null, null) } catch (_: Exception) { }
            throw if (e is IOException) e else IOException(e.message, e)
        }
    }

    /** Android 9 and below: needs WRITE_EXTERNAL_STORAGE, writes into the public Pictures/Movies folder. */
    @Suppress("DEPRECATION")
    private fun saveLegacy(file: File, displayName: String, mimeType: String, isVideo: Boolean): Uri {
        val root = Environment.getExternalStoragePublicDirectory(
            if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
        )
        val dir = File(root, FOLDER)
        if (!dir.exists() && !dir.mkdirs()) throw IOException("Could not create the folder (storage permission missing?)")

        val target = uniqueFile(dir, displayName)
        file.copyTo(target)
        MediaScannerConnection.scanFile(appContext, arrayOf(target.absolutePath), arrayOf(mimeType), null)
        return Uri.fromFile(target)
    }

    private fun uniqueFile(dir: File, name: String): File {
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var candidate = File(dir, name)
        var i = 1
        while (candidate.exists()) {
            candidate = File(dir, if (ext.isEmpty()) "$base ($i)" else "$base ($i).$ext")
            i++
        }
        return candidate
    }

    companion object {
        const val FOLDER = "Storage Compressor"
    }
}