package com.github.karlsabo.formatting

/**
 * Formats a byte count as a human-readable string (e.g., "1.5 KB", "3 MB").
 */
fun Long.formatBytes(): String = when {
    this < 1024 -> "$this bytes"
    this < 1024 * 1024 -> "${this / 1024} KB"
    else -> "${this / (1024 * 1024)} MB"
}
