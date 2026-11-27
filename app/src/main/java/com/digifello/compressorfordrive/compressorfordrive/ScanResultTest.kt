package com.digifello.compressorfordrive

import com.digifello.compressorfordrive.ui.Core.CompressionLevel
import com.digifello.compressorfordrive.ui.Core.DriveApi
import com.digifello.compressorfordrive.ui.Core.DriveFile
import com.digifello.compressorfordrive.ui.Core.isCandidate
import com.digifello.compressorfordrive.ui.Core.toScanResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanResultTest {

    private fun file(
        mime: String,
        size: Long,
        owned: Boolean = true,
        state: String? = null,
    ) = DriveFile(
        id = "id", name = "f", mimeType = mime, size = size, md5 = null,
        ownedByMe = owned, shared = false, modifiedTime = null, compressState = state,
    )

    @Test
    fun smallImagesAreNotCandidates() {
        assertFalse(file("image/jpeg", 100_000).isCandidate())
        assertTrue(file("image/jpeg", 2_000_000).isCandidate())
    }

    @Test
    fun filesOwnedByOthersOrAlreadyHandledAreNotCandidates() {
        assertFalse(file("image/jpeg", 2_000_000, owned = false).isCandidate())
        assertFalse(file("video/mp4", 5_000_000, state = DriveApi.STATE_DONE).isCandidate())
        assertFalse(file("video/mp4", 5_000_000, state = DriveApi.STATE_FAILED).isCandidate())
    }

    @Test
    fun pdfsAreNeverCandidates() {
        assertFalse(file("application/pdf", 50_000_000).isCandidate())
    }

    @Test
    fun scanResultCountsPhotosVideosAndSkipped() {
        val scan = listOf(
            file("image/jpeg", 2_000_000),
            file("video/mp4", 10_000_000),
            file("application/pdf", 1_000_000),
        ).toScanResult()
        assertEquals(1, scan.photos.count)
        assertEquals(1, scan.videos.count)
        assertEquals(1, scan.skipped.count)
        assertEquals(2, scan.eligibleCount)
    }

    @Test
    fun safeLevelPromisesLessSavingThanMaxSaving() {
        assertTrue(CompressionLevel.SAFE.jpegQuality > CompressionLevel.MAX_SAVING.jpegQuality)
    }
}