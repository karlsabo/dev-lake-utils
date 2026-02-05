package com.github.karlsabo.markdown

/**
 * Represents a markdown reference definition (e.g., [refName]: path/to/image.png).
 */
data class ReferenceDefinition(
    val referenceName: String,
    val path: String,
)
