package com.github.karlsabo.markdown

import kotlinx.io.files.Path

/**
 * Information about an extracted image.
 */
data class ExtractedImage(
    val referenceName: String,
    val outputPath: Path,
    val sizeBytes: Long,
)
