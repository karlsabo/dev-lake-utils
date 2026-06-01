package com.github.karlsabo.common.pagination

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val DEFAULT_CURSOR_PAGE_SIZE = 100

/**
 * Result of a single page fetch in cursor-based pagination.
 */
@Suppress("MatchingDeclarationName")
data class CursorPageResult(
    val nodes: JsonArray,
    val hasNextPage: Boolean,
    val endCursor: String?,
)

/**
 * Extracts cursor pagination info from a GraphQL response with standard pageInfo structure.
 *
 * @param containerPath Path to the container node (e.g., "issue" -> "children")
 * @return CursorPageResult with nodes and pagination info, or null if structure is invalid
 */
fun JsonObject.extractCursorPage(vararg containerPath: String): CursorPageResult? {
    val container = findCursorContainer(containerPath.toList())
    val nodes = container?.get("nodes")?.jsonArray
    val pageInfo = container?.get("pageInfo")?.jsonObject

    return if (nodes == null) {
        null
    } else {
        CursorPageResult(
            nodes = nodes,
            hasNextPage = pageInfo?.get("hasNextPage")?.jsonPrimitive?.booleanOrNull == true,
            endCursor = pageInfo?.get("endCursor")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
        )
    }
}

/**
 * Generic cursor-based pagination collector.
 *
 * @param fetchPage Function that fetches a page given a cursor (null for first page)
 * @param extractPage Function that extracts CursorPageResult from the response
 * @param transform Function that transforms each JSON node to the desired type
 * @return List of all collected and transformed items
 */
suspend fun <T, R> collectCursorPaginated(
    fetchPage: suspend (cursor: String?) -> R,
    extractPage: (R) -> CursorPageResult?,
    transform: (JsonElement) -> T,
): List<T> = buildList {
    var cursor: String? = null
    var shouldContinue = true

    while (shouldContinue) {
        val response = fetchPage(cursor)
        val page = extractPage(response)

        if (page == null) {
            shouldContinue = false
        } else {
            page.nodes.forEach { node -> add(transform(node)) }
            shouldContinue = page.hasNextPage && page.endCursor != null && page.nodes.isNotEmpty()
            cursor = page.endCursor
        }
    }
}

/**
 * Collects items with cursor pagination, with a maximum item limit.
 *
 * @param maxItems Maximum number of items to collect
 * @param fetchPage Function that fetches a page given a cursor and page size
 * @param extractPage Function that extracts CursorPageResult from the response
 * @param transform Function that transforms each JSON node to the desired type
 * @return List of collected items, up to maxItems
 */
suspend fun <T, R> collectCursorPaginatedWithLimit(
    maxItems: Int,
    fetchPage: suspend (cursor: String?, pageSize: Int) -> R,
    extractPage: (R) -> CursorPageResult?,
    transform: (JsonElement) -> T,
    defaultPageSize: Int = DEFAULT_CURSOR_PAGE_SIZE,
): List<T> = buildList {
    var cursor: String? = null
    var remaining = maxItems
    var shouldContinue = remaining > 0

    while (shouldContinue) {
        val pageSize = minOf(defaultPageSize, remaining)
        val response = fetchPage(cursor, pageSize)
        val page = extractPage(response)

        if (page == null) {
            shouldContinue = false
        } else {
            page.nodes.forEach { node -> add(transform(node)) }
            remaining -= page.nodes.size
            shouldContinue = remaining > 0 && page.hasNextPage && page.endCursor != null && page.nodes.isNotEmpty()
            cursor = page.endCursor
        }
    }
}

private fun JsonObject.findCursorContainer(containerPath: List<String>): JsonObject? {
    val parent = containerPath.dropLast(1).fold(this as JsonObject?) { current, key ->
        current?.get(key)?.jsonObject
    }
    return parent?.get(containerPath.lastOrNull())?.jsonObject
}
