package com.digifello.compressorfordrive.ui.Core

const val MIN_IMAGE_BYTES = 500L * 1024
const val MIN_VIDEO_BYTES = 5L * 1024 * 1024

/** One run handles the biggest files first; this keeps the single permission prompt manageable. */
const val MAX_FILES_PER_RUN = 2000

data class KindSummary(val count: Int, val bytes: Long)

fun LocalMedia.isBigEnough(): Boolean = size >= if (isVideo) MIN_VIDEO_BYTES else MIN_IMAGE_BYTES

fun LocalMedia.isCandidate(store: ProcessedStore, level: CompressionLevel): Boolean =
    isBigEnough() && store.shouldProcess(this, level)

data class ScanResult(
    val photos: KindSummary,
    val videos: KindSummary,
    val skipped: KindSummary,
) {
    val eligibleCount: Int get() = photos.count + videos.count
}

class ScanOutcome(val summary: ScanResult, val candidates: List<LocalMedia>)

fun List<LocalMedia>.classify(store: ProcessedStore, level: CompressionLevel): ScanOutcome {
    fun List<LocalMedia>.summary() = KindSummary(size, sumOf { it.size })
    val (eligible, rest) = partition { it.isCandidate(store, level) }
    return ScanOutcome(
        summary = ScanResult(
            photos = eligible.filter { !it.isVideo }.summary(),
            videos = eligible.filter { it.isVideo }.summary(),
            skipped = rest.summary(),
        ),
        candidates = eligible.sortedByDescending { it.size },
    )
}

fun CompressionLevel.photoRatio(): Double = when (this) {
    CompressionLevel.PRESERVED -> 0.70
    CompressionLevel.BALANCED -> 0.50
    CompressionLevel.MAX_SAVING -> 0.35
}

fun CompressionLevel.videoRatio(): Double = videoBitrateFactor

fun ScanResult.estimatedSavedBytes(level: CompressionLevel): Long =
    (photos.bytes * (1 - level.photoRatio()) + videos.bytes * (1 - level.videoRatio())).toLong()