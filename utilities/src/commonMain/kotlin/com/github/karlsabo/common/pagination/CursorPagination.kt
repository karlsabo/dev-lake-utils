package com.github.karlsabo.common.pagination

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Result of a single page fetch in cursor-based pagination.
 */
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
    var current: JsonObject = this
    for (key in containerPath.dropLast(1)) {
        current = current[key]?.jsonObject ?: return null
    }
    val container = current[containerPath.last()]?.jsonObject ?: return null
    val nodes = container["nodes"]?.jsonArray ?: return null
    val pageInfo = container["pageInfo"]?.jsonObject

    return CursorPageResult(
        nodes = nodes,
        hasNextPage = pageInfo?.get("hasNextPage")?.jsonPrimitive?.booleanOrNull == true,
        endCursor = pageInfo?.get("endCursor")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
    )
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
    transform: (kotlinx.serialization.json.JsonElement) -> T,
): List<T> = buildList {
    var cursor: String? = null

    while (true) {
        val response = fetchPage(cursor)
        val page = extractPage(response) ?: break

        page.nodes.forEach { node ->
            add(transform(node))
        }

        if (!page.hasNextPage || page.endCursor == null || page.nodes.isEmpty()) break
        cursor = page.endCursor
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
    transform: (kotlinx.serialization.json.JsonElement) -> T,
    defaultPageSize: Int = 100,
): List<T> = buildList {
    var cursor: String? = null
    var remaining = maxItems

    while (remaining > 0) {
        val pageSize = minOf(defaultPageSize, remaining)
        val response = fetchPage(cursor, pageSize)
        val page = extractPage(response) ?: break

        page.nodes.forEach { node ->
            add(transform(node))
        }

        remaining -= page.nodes.size

        if (!page.hasNextPage || page.endCursor == null || page.nodes.isEmpty()) break
        cursor = page.endCursor
    }
}
