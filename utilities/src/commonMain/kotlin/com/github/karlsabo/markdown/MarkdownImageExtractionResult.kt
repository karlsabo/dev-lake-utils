package com.github.karlsabo.markdown

import kotlinx.io.files.Path

/**
 * Result of processing a markdown file for embedded images.
 */
data class MarkdownImageExtractionResult(
    val markdownFile: Path,
    val extractedImages: List<ExtractedImage>,
    val updatedContent: String,
)
