package com.digifello.compressorfordrive.ui.Screens.Test

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.digifello.compressorfordrive.ui.Core.CompressionLevel
import com.digifello.compressorfordrive.ui.Core.CompressionOutcome
import com.digifello.compressorfordrive.ui.Core.Compressor
import com.digifello.compressorfordrive.ui.Core.GalleryWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

enum class TestPhase { Empty, Ready, Compressing, Done }

sealed interface TestResult {
    data class Compressed(
        val file: File,
        val mimeType: String,
        val newSize: Long,
        val similarity: Double,
        /** Name used in the gallery, or null if saving failed. */
        val savedAs: String?,
        val saveError: String?,
    ) : TestResult

    data class NotCompressed(val reason: String) : TestResult
}

data class TestUiState(
    val phase: TestPhase = TestPhase.Empty,
    val loading: Boolean = false,
    val fileName: String = "",
    val mime: String = "",
    val originalSize: Long = 0L,
    val originalFile: File? = null,
    val level: CompressionLevel = CompressionLevel.BALANCED,
    val progress: Float = 0f,
    val result: TestResult? = null,
    val error: String? = null,
) {
    val isVideo: Boolean get() = mime.startsWith("video/")
}

/**
 * Compresses ONE file the user picks, using the same [Compressor] as the main screen.
 * The original is never modified: the result is shown on screen and saved to the gallery as a new file.
 */
class TestViewModel(private val app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(TestUiState())
    val state: StateFlow<TestUiState> = _state.asStateFlow()

    private val compressor = Compressor(app)
    private val testDir = File(app.cacheDir, "test")
    private var job: Job? = null

    // ---- picking --------------------------------------------------------------------------

    fun onPicked(uri: Uri?) {
        if (uri == null) return
        if (_state.value.phase == TestPhase.Compressing) return
        val level = _state.value.level

        job?.cancel()
        // Clear the old previews first, so nothing is still showing a file we are about to delete.
        _state.value = TestUiState(level = level, loading = true)

        job = viewModelScope.launch {
            try {
                val picked = withContext(Dispatchers.IO) { copyPicked(uri) }
                _state.update {
                    it.copy(
                        phase = TestPhase.Ready,
                        loading = false,
                        fileName = picked.name,
                        mime = picked.mime,
                        originalSize = picked.file.length(),
                        originalFile = picked.file,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Could not open this file.") }
            }
        }
    }

    private class Picked(val file: File, val name: String, val mime: String)

    private fun copyPicked(uri: Uri): Picked {
        val resolver = app.contentResolver
        val mime = resolver.getType(uri).orEmpty()
        val ext = EXTENSIONS[mime]
            ?: throw IOException("This file type is not supported. Choose a JPEG or PNG photo, or an MP4 video.")

        val rawName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
        val name = when {
            rawName.isNullOrBlank() -> "media.$ext"
            rawName.contains('.') -> rawName
            else -> "$rawName.$ext"
        }

        testDir.deleteRecursively()
        testDir.mkdirs()
        val file = File(testDir, "in_${System.nanoTime()}.$ext")
        val input = resolver.openInputStream(uri) ?: throw IOException("Could not read this file.")
        input.use { i -> file.outputStream().use { o -> i.copyTo(o) } }
        return Picked(file, name, mime)
    }

    // ---- settings -------------------------------------------------------------------------

    fun setLevel(level: CompressionLevel) {
        val s = _state.value
        if (s.phase == TestPhase.Compressing || s.level == level) return
        // A new level means the old result no longer applies.
        _state.update {
            it.copy(
                level = level,
                phase = if (it.phase == TestPhase.Done) TestPhase.Ready else it.phase,
                result = null,
                progress = 0f,
            )
        }
        deleteOutputs()
    }

    // ---- compress -------------------------------------------------------------------------

    fun compress() {
        val s = _state.value
        val input = s.originalFile ?: return
        if (s.phase == TestPhase.Compressing) return

        _state.update { it.copy(phase = TestPhase.Compressing, progress = 0f, result = null, error = null) }
        deleteOutputs()

        job = viewModelScope.launch {
            try {
                val outcome = compressor.compress(input, s.fileName, s.mime, s.level, testDir) { p ->
                    _state.update { it.copy(progress = p.coerceIn(0f, 1f)) }
                }
                ensureActive() // a cancelled video encode comes back as "Skipped", not as an exception

                val result = when (outcome) {
                    is CompressionOutcome.Skipped -> TestResult.NotCompressed(outcome.reason)
                    is CompressionOutcome.Success -> saveToGallery(outcome, s.fileName)
                }
                _state.update { it.copy(phase = TestPhase.Done, progress = 1f, result = result) }
            } catch (e: CancellationException) {
                deleteOutputs()
                _state.update { it.copy(phase = TestPhase.Ready, progress = 0f) }
                throw e
            } catch (e: Exception) {
                deleteOutputs()
                _state.update {
                    it.copy(phase = TestPhase.Ready, progress = 0f, error = "Compression failed: ${e.message}")
                }
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    private suspend fun saveToGallery(o: CompressionOutcome.Success, originalName: String): TestResult.Compressed {
        val base = originalName.substringBeforeLast('.', originalName)
        val ext = o.newName.substringAfterLast('.', "")
        val name = if (ext.isEmpty()) "${base}_compressed" else "${base}_compressed.$ext"

        var savedAs: String? = null
        var saveError: String? = null
        try {
            withContext(Dispatchers.IO) {
                GalleryWriter(app).saveNew(o.file, name, o.mimeType, o.mimeType.startsWith("video/"))
            }
            savedAs = name
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            saveError = e.message ?: "unknown error"
        }
        return TestResult.Compressed(o.file, o.mimeType, o.newSize, o.similarity, savedAs, saveError)
    }

    // ---- housekeeping ---------------------------------------------------------------------

    fun clear() {
        job?.cancel()
        _state.value = TestUiState(level = _state.value.level)
        testDir.deleteRecursively()
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    private fun deleteOutputs() {
        testDir.listFiles()?.forEach { if (it.name.startsWith("out_")) it.delete() }
    }

    override fun onCleared() {
        testDir.deleteRecursively()
    }

    private companion object {
        val EXTENSIONS = mapOf(
            "image/jpeg" to "jpg",
            "image/png" to "png",
            "video/mp4" to "mp4",
        )
    }
}