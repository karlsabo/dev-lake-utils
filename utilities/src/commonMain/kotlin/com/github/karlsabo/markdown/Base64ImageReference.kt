package com.github.karlsabo.markdown

/**
 * Represents a base64-encoded image reference found in markdown content.
 */
data class Base64ImageReference(
    val fullMatch: String,
    val referenceName: String,
    val imageType: String,
    val base64Data: String,
)
