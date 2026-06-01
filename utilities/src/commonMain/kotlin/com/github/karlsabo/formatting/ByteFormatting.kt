package com.github.karlsabo.formatting

private const val BYTES_PER_KILOBYTE = 1024
private const val BYTES_PER_MEGABYTE = BYTES_PER_KILOBYTE * BYTES_PER_KILOBYTE

/**
 * Formats a byte count as a human-readable string (e.g., "1.5 KB", "3 MB").
 */
fun Long.formatBytes(): String = when {
    this < BYTES_PER_KILOBYTE -> "$this bytes"
    this < BYTES_PER_MEGABYTE -> "${this / BYTES_PER_KILOBYTE} KB"
    else -> "${this / BYTES_PER_MEGABYTE} MB"
}
