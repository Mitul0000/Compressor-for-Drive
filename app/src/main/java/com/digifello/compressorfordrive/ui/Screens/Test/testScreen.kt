package com.digifello.compressorfordrive.ui.Screens.Test

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.os.Build
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.digifello.compressorfordrive.ui.Core.CompressionLevel
import com.digifello.compressorfordrive.ui.Screens.Home.ErrorBanner
import com.digifello.compressorfordrive.ui.Screens.Home.LevelPicker
import com.digifello.compressorfordrive.ui.Screens.Home.Note
import com.digifello.compressorfordrive.ui.Screens.Home.ResultRow
import com.digifello.compressorfordrive.ui.Screens.Home.Section
import com.digifello.compressorfordrive.ui.Screens.Home.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TestScreen(vm: TestViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // The system photo picker needs no storage permission.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { vm.onPicked(it) }

    // Android 9 and below need this permission before a file can be added to the gallery.
    val legacyWritePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        vm.compress()
    }

    fun startCompress() {
        val needsLegacyPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                PackageManager.PERMISSION_GRANTED
        if (needsLegacyPermission) legacyWritePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        else vm.compress()
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Test one file") }) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            state.error?.let { ErrorBanner(it, vm::dismissError) }
            IntroCard()
            PickSection(
                state = state,
                onPick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                onClear = vm::clear,
            )
            if (state.phase != TestPhase.Empty) {
                Section("Compression quality") {
                    LevelPicker(state.level, state.phase != TestPhase.Compressing, vm::setLevel)
                }
                CompressSection(state, onCompress = { startCompress() }, onCancel = vm::cancel)
            }
            state.result?.let { ResultSection(state, it) }
        }
    }
}

@Composable
private fun IntroCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Try it on one file",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                "Pick a photo or video, choose a quality level and compress it. Your original is never changed. " +
                        "The compressed copy is shown here and saved to your gallery as a new file.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun PickSection(state: TestUiState, onPick: () -> Unit, onClear: () -> Unit) {
    val busy = state.loading || state.phase == TestPhase.Compressing
    Section("File") {
        OutlinedButton(onClick = onPick, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.phase == TestPhase.Empty) "Choose a photo or video" else "Choose a different file")
        }
        if (state.loading) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("Opening file...")
            }
        }
        if (state.phase != TestPhase.Empty) {
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(state.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Note("${if (state.isVideo) "Video" else "Photo"} · ${formatBytes(state.originalSize)}")
                }
            }
            TextButton(onClick = onClear, enabled = !busy) { Text("Remove") }
        }
    }
}

@Composable
private fun CompressSection(state: TestUiState, onCompress: () -> Unit, onCancel: () -> Unit) {
    val busy = state.phase == TestPhase.Compressing
    Section("Compress") {
        Button(onClick = onCompress, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Compress") }
        if (busy) {
            if (state.isVideo && state.progress > 0f) {
                LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                Text("Compressing video... ${(state.progress * 100).toInt()}%")
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(if (state.isVideo) "Compressing video..." else "Compressing photo...")
            }
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
        Note("Photos under 500 KB and videos already under 2 Mbps are skipped, the same as on the Main screen.")
    }
}

// ---- result -------------------------------------------------------------------------------

@Composable
private fun ResultSection(state: TestUiState, result: TestResult) {
    when (result) {
        is TestResult.NotCompressed -> Section("Result") {
            Text("Not compressed: ${result.reason}")
            Note(
                "Your file was left as it is and nothing was saved. The app only keeps a result that is " +
                        "smaller than the original and passes the quality check."
            )
        }

        is TestResult.Compressed -> {
            val original = state.originalFile ?: return
            CompressedResult(state, original, result)
        }
    }
}

@Composable
private fun CompressedResult(state: TestUiState, original: File, r: TestResult.Compressed) {
    var showCompressed by remember(r) { mutableStateOf(true) }
    val shown = if (showCompressed) r.file else original

    Section("Output") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            ViewButton("Original", !showCompressed, { showCompressed = false }, Modifier.weight(1f))
            ViewButton("Compressed", showCompressed, { showCompressed = true }, Modifier.weight(1f))
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (state.isVideo) VideoPreview(shown) else ImagePreview(shown)
        }
        Note("${if (showCompressed) "Compressed" else "Original"} · ${formatBytes(shown.length())}")
    }

    Section("Details") {
        val saved = (state.originalSize - r.newSize).coerceAtLeast(0)
        val percent = if (state.originalSize > 0) saved * 100.0 / state.originalSize else 0.0
        ResultRow("Quality level", levelName(state.level))
        ResultRow("Original size", formatBytes(state.originalSize))
        ResultRow("Compressed size", formatBytes(r.newSize))
        ResultRow("Saved", "${formatBytes(saved)} (${"%.0f".format(percent)}%)")
        ResultRow(
            "Quality match",
            if (r.mimeType == "image/png") "Identical (lossless)" else "%.3f".format(r.similarity),
        )
        Note("Quality match is a similarity score from 0 to 1 (SSIM). Closer to 1 means closer to the original.")

        if (r.savedAs != null) {
            Note("Saved to your gallery as \"${r.savedAs}\" in the Storage Compressor album. Your original was not changed.")
        } else {
            Text(
                "Could not save to the gallery: ${r.saveError}",
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

private fun levelName(level: CompressionLevel) = when (level) {
    CompressionLevel.PRESERVED -> "Quality preserved"
    CompressionLevel.BALANCED -> "Balanced"
    CompressionLevel.MAX_SAVING -> "Max storage"
}

@Composable
private fun ViewButton(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    if (selected) Button(onClick = onClick, modifier = modifier) { Text(label) }
    else OutlinedButton(onClick = onClick, modifier = modifier) { Text(label) }
}

@Composable
private fun ImagePreview(file: File) {
    var finished by remember(file) { mutableStateOf(false) }
    val bitmap by produceState<ImageBitmap?>(null, file) {
        value = withContext(Dispatchers.IO) { decodePreview(file) }
        finished = true
    }
    val bmp = bitmap
    when {
        bmp != null -> Image(
            bitmap = bmp,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )

        finished -> Text("Cannot preview this image")
        else -> CircularProgressIndicator()
    }
}

/**
 * Plays the video with MediaPlayer on a TextureView. Unlike VideoView (a SurfaceView), a TextureView is
 * drawn as part of the normal UI, so it scrolls, clips and fades together with the rest of the screen.
 */
@Composable
private fun VideoPreview(file: File) {
    key(file.absolutePath) {
        var player by remember { mutableStateOf<MediaPlayer?>(null) }
        var surface by remember { mutableStateOf<Surface?>(null) }
        var prepared by remember { mutableStateOf(false) }
        var playing by remember { mutableStateOf(false) }
        var position by remember { mutableIntStateOf(0) }
        var duration by remember { mutableIntStateOf(0) }
        var videoRatio by remember { mutableStateOf<Float?>(null) }
        var failed by remember { mutableStateOf(false) }

        DisposableEffect(Unit) {
            val mp = MediaPlayer()
            try {
                mp.setDataSource(file.absolutePath)
                mp.setOnPreparedListener {
                    duration = it.duration
                    prepared = true
                }
                mp.setOnVideoSizeChangedListener { _, w, h ->
                    if (w > 0 && h > 0) videoRatio = w.toFloat() / h
                }
                mp.setOnCompletionListener {
                    playing = false
                    position = 0
                    it.seekTo(0)
                }
                mp.setOnErrorListener { _, _, _ ->
                    failed = true
                    playing = false
                    true
                }
                mp.prepareAsync()
                player = mp
            } catch (_: Exception) {
                failed = true
            }
            onDispose {
                player = null
                runCatching { mp.release() }
            }
        }

        // Attach the player to the texture, and show the first frame instead of a black box.
        LaunchedEffect(player, surface, prepared) {
            val mp = player
            val sf = surface
            if (mp != null && sf != null && sf.isValid) {
                runCatching { mp.setSurface(sf) }
                if (prepared && !playing) runCatching { mp.seekTo(1) }
            }
        }

        LaunchedEffect(playing) {
            while (playing) {
                position = runCatching { player?.currentPosition }.getOrNull() ?: 0
                delay(250)
            }
        }

        if (failed) {
            Text("Cannot preview this video")
        } else Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val ratioModifier = videoRatio?.let { Modifier.aspectRatio(it) } ?: Modifier.fillMaxSize()
                AndroidView(
                    modifier = ratioModifier,
                    factory = { ctx ->
                        TextureView(ctx).apply {
                            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                                    surface = Surface(st)
                                }

                                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}

                                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                    runCatching { player?.setSurface(null) }
                                    surface?.release()
                                    surface = null
                                    return true
                                }

                                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                            }
                            setOnClickListener {
                                val mp = player
                                if (mp != null && prepared) {
                                    if (playing) mp.pause() else mp.start()
                                    playing = !playing
                                }
                            }
                        }
                    },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    enabled = prepared,
                    onClick = {
                        val mp = player
                        if (mp != null) {
                            if (playing) mp.pause() else mp.start()
                            playing = !playing
                        }
                    },
                ) { Text(if (playing) "Pause" else "Play") }
                Slider(
                    value = position.toFloat(),
                    onValueChange = {
                        position = it.toInt()
                        runCatching { player?.seekTo(position) }
                    },
                    valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
                    enabled = prepared,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** Decodes a screen-sized copy and applies the EXIF rotation, because BitmapFactory ignores it. */
private fun decodePreview(file: File, maxSide: Int = 1600): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    val decoded = BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply { inSampleSize = sample },
    ) ?: return null

    val degrees = try {
        ExifInterface(file.absolutePath).rotationDegrees
    } catch (_: Exception) {
        0
    }
    if (degrees == 0) return decoded.asImageBitmap()

    val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
    val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    if (rotated !== decoded) decoded.recycle()
    return rotated.asImageBitmap()
}