package com.digifello.compressorfordrive.ui.Core

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File

data class LocalMedia(
    val id: Long,
    val isVideo: Boolean,
    val name: String,
    val mime: String,
    val size: Long,
    val dateAdded: Long,
) {
    /** Stable identifier used in the queue file and the processed log. */
    val key: String get() = (if (isVideo) "v" else "i") + id

    val uri: Uri get() = ContentUris.withAppendedId(LocalScanner.collection(isVideo), id)
}

/** Reads photos and videos from the phone's MediaStore. Nothing here touches the network. */
class LocalScanner(context: Context) {

    private val resolver = context.applicationContext.contentResolver

    fun scan(sinceSeconds: Long? = null): List<LocalMedia> {
        val out = ArrayList<LocalMedia>()
        query(false, sinceSeconds, null, out)
        query(true, sinceSeconds, null, out)
        return out
    }

    fun get(isVideo: Boolean, id: Long): LocalMedia? {
        val out = ArrayList<LocalMedia>(1)
        query(isVideo, null, id, out)
        return out.firstOrNull()
    }

    private fun query(isVideo: Boolean, since: Long?, id: Long?, out: MutableList<LocalMedia>) {
        val mimes = if (isVideo) VIDEO_MIMES else IMAGE_MIMES
        val selection = StringBuilder(
            "${MediaStore.MediaColumns.MIME_TYPE} IN (${mimes.joinToString(",") { "?" }}) " +
                    "AND ${MediaStore.MediaColumns.SIZE} > 0"
        )
        val args = ArrayList<String>(mimes)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) selection.append(" AND ${MediaStore.MediaColumns.IS_PENDING} = 0")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) selection.append(" AND ${MediaStore.MediaColumns.IS_TRASHED} = 0")
        since?.let {
            selection.append(" AND ${MediaStore.MediaColumns.DATE_ADDED} >= ?")
            args.add(it.toString())
        }
        id?.let {
            selection.append(" AND ${MediaStore.MediaColumns._ID} = ?")
            args.add(it.toString())
        }

        resolver.query(collection(isVideo), PROJECTION, selection.toString(), args.toTypedArray(), null)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val addedCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            while (c.moveToNext()) {
                out += LocalMedia(
                    id = c.getLong(idCol),
                    isVideo = isVideo,
                    name = c.getString(nameCol).orEmpty(),
                    mime = c.getString(mimeCol).orEmpty(),
                    size = c.getLong(sizeCol),
                    dateAdded = c.getLong(addedCol),
                )
            }
        }
    }

    companion object {
        private val IMAGE_MIMES = listOf("image/jpeg", "image/png")
        private val VIDEO_MIMES = listOf("video/mp4")
        private val PROJECTION = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
        )

        fun collection(isVideo: Boolean): Uri =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (isVideo) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
    }
}

/** The list of files for the current run. Too long for WorkManager input data, so it lives in a file. */
object QueueFile {
    private fun file(context: Context) = File(context.filesDir, "queue.txt")

    fun write(context: Context, items: List<LocalMedia>) {
        file(context).writeText(items.joinToString("\n") { it.key })
    }

    fun read(context: Context): List<String> =
        file(context).takeIf { it.exists() }?.readLines()?.filter { it.isNotBlank() }.orEmpty()

    /** "v123" -> (isVideo = true, id = 123) */
    fun parse(key: String): Pair<Boolean, Long>? {
        val id = key.drop(1).toLongOrNull() ?: return null
        return when (key.firstOrNull()) {
            'v' -> true to id
            'i' -> false to id
            else -> null
        }
    }
}