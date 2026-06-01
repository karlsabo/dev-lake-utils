package com.github.karlsabo.github

import com.github.karlsabo.common.datetime.DateTimeFormatting.toIsoUtcDateTime
import com.github.karlsabo.tools.lenientJson
import io.ktor.http.encodeURLParameter
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonElement

internal fun createMergedPrEncodedQuery(
    startDate: Instant,
    endDate: Instant,
    gitHubUserId: String,
    organizationIds: List<String>,
): String {
    val formattedStartDate = startDate.toIsoUtcDateTime()
    val formattedEndDate = endDate.toIsoUtcDateTime()
    val query = buildString {
        append("author:$gitHubUserId ${organizationQualifier(organizationIds)}")
        append(" is:pr is:merged merged:$formattedStartDate..$formattedEndDate")
    }
    return query.encodeURLParameter()
}

internal fun createReviewedPrEncodedQuery(
    startDate: Instant,
    endDate: Instant,
    gitHubUserId: String,
    organizationIds: List<String>,
): String {
    val formattedStartDate = startDate.toIsoUtcDateTime()
    val formattedEndDate = endDate.toIsoUtcDateTime()
    val query = buildString {
        append("reviewed-by:$gitHubUserId ${organizationQualifier(organizationIds)}")
        append(" is:pr updated:$formattedStartDate..$formattedEndDate")
    }
    return query.encodeURLParameter()
}

fun JsonElement.toPullRequest(): Issue = lenientJson.decodeFromJsonElement(Issue.serializer(), this)
