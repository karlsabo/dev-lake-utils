package com.github.karlsabo.common.pagination

import kotlinx.serialization.json.JsonArray

/**
 * Result of a single page fetch in cursor-based pagination.
 */
data class CursorPageResult(
    val nodes: JsonArray,
    val hasNextPage: Boolean,
    val endCursor: String?,
)
