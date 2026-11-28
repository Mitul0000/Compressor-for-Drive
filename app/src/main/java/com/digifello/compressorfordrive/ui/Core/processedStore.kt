package com.digifello.compressorfordrive.ui.Core

import android.content.Context
import java.io.File
import java.io.IOException

/**
 * Remembers which files were already handled, so nothing is compressed twice and files that
 * could not be shrunk are not retried on every scan. A file counts as "new" again if its size changes.
 */
class ProcessedStore(private val file: File) {

    private class Entry(val size: Long, val level: Int, val compressed: Boolean)

    private val map = HashMap<String, Entry>()

    init {
        if (file.exists()) {
            file.forEachLine { line ->
                val p = line.split('\t')
                if (p.size == 4) {
                    val size = p[1].toLongOrNull()
                    val level = p[2].toIntOrNull()
                    if (size != null && level != null) map[p[0]] = Entry(size, level, p[3] == "1")
                }
            }
        }
    }

    @Synchronized
    fun shouldProcess(item: LocalMedia, level: CompressionLevel): Boolean {
        val e = map[item.key] ?: return true
        if (e.size != item.size) return true
        if (e.compressed) return false
        // Tried before without success: only retry with a stronger level.
        return e.level < level.ordinal
    }

    @Synchronized
    fun markCompressed(item: LocalMedia, newSize: Long, level: CompressionLevel) =
        put(item.key, Entry(newSize, level.ordinal, true))

    @Synchronized
    fun markSkipped(item: LocalMedia, level: CompressionLevel) =
        put(item.key, Entry(item.size, level.ordinal, false))

    private fun put(key: String, e: Entry) {
        map[key] = e
        try {
            file.appendText("$key\t${e.size}\t${e.level}\t${if (e.compressed) 1 else 0}\n")
        } catch (_: IOException) {
        }
    }

    companion object {
        fun forApp(context: Context) = ProcessedStore(File(context.applicationContext.filesDir, "processed.tsv"))
    }
}