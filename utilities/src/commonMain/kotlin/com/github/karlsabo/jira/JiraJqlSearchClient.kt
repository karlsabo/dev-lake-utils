package com.github.karlsabo.jira

import com.github.karlsabo.jira.model.Issue
import com.github.karlsabo.tools.lenientJson
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private data class JqlPage(
    val issues: JsonArray,
    val isLast: Boolean,
    val nextPageToken: String?,
    val total: Int?,
)

private data class JqlPageState(
    val startAt: Int,
    val nextPageToken: String?,
    val hasMore: Boolean,
)

internal class JiraJqlSearchClient(
    private val domain: String,
    private val httpApi: JiraHttpApi,
    private val pageSize: Int,
) {
    suspend fun <T> run(jql: String, transform: (Issue) -> T): List<T> = buildList {
        var pageState = JqlPageState(startAt = 0, nextPageToken = null, hasMore = true)

        while (pageState.hasMore) {
            val page = fetchPage(jql, pageState.startAt, pageState.nextPageToken)
            page.issues.mapTo(this) { issue ->
                transform(lenientJson.decodeFromJsonElement(Issue.serializer(), issue.jsonObject))
            }
            pageState = nextPageState(jql, page, pageState.startAt)
        }
    }

    private suspend fun fetchPage(
        jql: String,
        startAt: Int,
        nextPageToken: String?,
    ): JqlPage {
        val url = buildSearchUrl(jql, startAt, nextPageToken)
        return httpApi.getJson(url, "run JQL: $jql").toJqlPage()
    }

    private fun JsonObject.toJqlPage(): JqlPage = JqlPage(
        issues = this["issues"]?.jsonArray ?: JsonArray(emptyList()),
        isLast = this["isLast"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true,
        nextPageToken = this["nextPageToken"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
        total = this["total"]?.jsonPrimitive?.int,
    )

    private fun nextPageState(
        jql: String,
        page: JqlPage,
        startAt: Int,
    ): JqlPageState = when {
        page.isLast -> JqlPageState(startAt, page.nextPageToken, hasMore = false)
        page.nextPageToken != null -> JqlPageState(startAt, page.nextPageToken, hasMore = true)
        page.total != null -> nextOffsetPageState(startAt, page.total)
        page.issues.size < pageSize -> JqlPageState(startAt, null, hasMore = false)
        else -> throw JiraApiException("JQL results appear truncated (no pagination fields) for jql=$jql")
    }

    private fun nextOffsetPageState(startAt: Int, total: Int): JqlPageState {
        val nextStartAt = startAt + pageSize
        return JqlPageState(
            startAt = nextStartAt,
            nextPageToken = null,
            hasMore = nextStartAt < total,
        )
    }

    private fun buildSearchUrl(
        jql: String,
        startAt: Int,
        nextPageToken: String?,
    ): String = buildString {
        append("https://$domain/rest/api/3/search/jql")
        append("?jql=${jql.encodeURLParameter()}")
        append("&maxResults=$pageSize")
        append("&fields=*all")
        if (nextPageToken != null) {
            append("&nextPageToken=${nextPageToken.encodeURLParameter()}")
        } else {
            append("&startAt=$startAt")
        }
    }
}
