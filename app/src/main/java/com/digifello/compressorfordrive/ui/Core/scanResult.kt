package com.digifello.compressorfordrive.ui.Core

const val MIN_IMAGE_BYTES = 500L * 1024

fun DriveFile.isCandidate(): Boolean = ownedByMe && !alreadyCompressed && when (kind) {
    FileKind.IMAGE -> size >= MIN_IMAGE_BYTES
    FileKind.VIDEO -> true
    else -> false
}

data class ScanResult(
    val photos: KindSummary,
    val videos: KindSummary,
    val skipped: KindSummary,
) {
    val eligibleCount: Int get() = photos.count + videos.count
}

fun List<DriveFile>.toScanResult(): ScanResult {
    fun List<DriveFile>.summary() = KindSummary(size, sumOf { it.size })
    val (eligible, rest) = partition { it.isCandidate() }
    return ScanResult(
        photos = eligible.filter { it.kind == FileKind.IMAGE }.summary(),
        videos = eligible.filter { it.kind == FileKind.VIDEO }.summary(),
        skipped = rest.filter { it.ownedByMe }.summary(),
    )
}

fun CompressionLevel.photoRatio(): Double = when (this) {
    CompressionLevel.SAFE -> 0.60
    CompressionLevel.BALANCED -> 0.45
    CompressionLevel.MAX_SAVING -> 0.30
}

fun CompressionLevel.videoRatio(): Double = when (this) {
    CompressionLevel.SAFE -> 0.70
    CompressionLevel.BALANCED -> 0.50
    CompressionLevel.MAX_SAVING -> 0.35
}

fun ScanResult.estimatedSavedBytes(level: CompressionLevel): Long =
    (photos.bytes * (1 - level.photoRatio()) + videos.bytes * (1 - level.videoRatio())).toLong()