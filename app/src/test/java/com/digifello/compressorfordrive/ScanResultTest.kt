package com.digifello.compressorfordrive

import com.digifello.compressorfordrive.ui.Core.CompressionLevel
import com.digifello.compressorfordrive.ui.Core.LocalMedia
import com.digifello.compressorfordrive.ui.Core.ProcessedStore
import com.digifello.compressorfordrive.ui.Core.classify
import com.digifello.compressorfordrive.ui.Core.isCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ScanResultTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun photo(id: Long, size: Long) = LocalMedia(id, false, "p$id.jpg", "image/jpeg", size, 0L)
    private fun video(id: Long, size: Long) = LocalMedia(id, true, "v$id.mp4", "video/mp4", size, 0L)
    private fun store() = ProcessedStore(tmp.newFile())

    private val mb = 1024L * 1024

    @Test
    fun smallFilesAreNotCandidates() {
        val s = store()
        assertFalse(photo(1, 100 * 1024).isCandidate(s, CompressionLevel.BALANCED))
        assertFalse(video(2, 2 * mb).isCandidate(s, CompressionLevel.BALANCED))
    }

    @Test
    fun bigFilesAreCandidates() {
        val s = store()
        assertTrue(photo(1, 3 * mb).isCandidate(s, CompressionLevel.BALANCED))
        assertTrue(video(2, 80 * mb).isCandidate(s, CompressionLevel.BALANCED))
    }

    @Test
    fun compressedFilesAreLeftAloneUntilTheirSizeChanges() {
        val s = store()
        val original = photo(1, 4 * mb)
        s.markCompressed(original, 2 * mb, CompressionLevel.BALANCED)

        assertFalse(photo(1, 2 * mb).isCandidate(s, CompressionLevel.MAX_SAVING))
        assertTrue(photo(1, 5 * mb).isCandidate(s, CompressionLevel.BALANCED)) // edited since
    }

    @Test
    fun skippedFilesAreOnlyRetriedWithAStrongerLevel() {
        val s = store()
        val item = photo(1, 4 * mb)
        s.markSkipped(item, CompressionLevel.BALANCED)

        assertFalse(item.isCandidate(s, CompressionLevel.PRESERVED))
        assertFalse(item.isCandidate(s, CompressionLevel.BALANCED))
        assertTrue(item.isCandidate(s, CompressionLevel.MAX_SAVING))
    }

    @Test
    fun processedLogSurvivesRestart() {
        val file = tmp.newFile()
        val item = photo(7, 4 * mb)
        ProcessedStore(file).markCompressed(item, 2 * mb, CompressionLevel.BALANCED)

        assertFalse(photo(7, 2 * mb).isCandidate(ProcessedStore(file), CompressionLevel.BALANCED))
    }

    @Test
    fun scanCountsPhotosVideosAndSkipped() {
        val items = listOf(photo(1, 3 * mb), video(2, 60 * mb), photo(3, 100 * 1024))
        val out = items.classify(store(), CompressionLevel.BALANCED)

        assertEquals(1, out.summary.photos.count)
        assertEquals(1, out.summary.videos.count)
        assertEquals(1, out.summary.skipped.count)
        assertEquals(2, out.summary.eligibleCount)
    }

    @Test
    fun biggestFilesComeFirst() {
        val items = listOf(photo(1, 2 * mb), video(2, 90 * mb), photo(3, 6 * mb))
        val out = items.classify(store(), CompressionLevel.BALANCED)

        assertEquals(listOf(2L, 3L, 1L), out.candidates.map { it.id })
    }

    @Test
    fun strongerLevelsKeepLessQuality() {
        assertTrue(CompressionLevel.PRESERVED.jpegQuality > CompressionLevel.BALANCED.jpegQuality)
        assertTrue(CompressionLevel.BALANCED.jpegQuality > CompressionLevel.MAX_SAVING.jpegQuality)
        assertTrue(CompressionLevel.PRESERVED.videoBitrateFactor > CompressionLevel.MAX_SAVING.videoBitrateFactor)
    }
}