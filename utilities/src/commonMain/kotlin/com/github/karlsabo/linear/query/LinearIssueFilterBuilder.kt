package com.github.karlsabo.linear.query

import kotlin.time.Instant

internal class LinearIssueFilterBuilder {
    fun resolvedIssuesFilter(
        userId: String,
        startDate: Instant,
        endDate: Instant,
    ): String {
        val escapedUserId = escapeLinearGraphQlString(userId)
        val start = escapeLinearGraphQlString(startDate.toString())
        val end = escapeLinearGraphQlString(endDate.toString())

        return """
            {
              assignee: { id: { eq: "$escapedUserId" } }
              completedAt: { gte: "$start", lte: "$end" }
            }
        """.trimIndent()
    }

    fun labelsFilter(
        labels: List<String>,
        completedAfter: Instant? = null,
        completedBefore: Instant? = null,
    ): String {
        val labelNames = labels.joinToString(", ") { "\"${escapeLinearGraphQlString(it)}\"" }
        val completedFilter = completedAtFilter(completedAfter, completedBefore)

        return buildString {
            append("{ labels: { name: { in: [$labelNames] } }")
            if (completedFilter.isNotEmpty()) {
                append(", $completedFilter")
            }
            append(" }")
        }
    }

    fun milestoneIssuesFilter(milestoneId: String): String {
        val escapedMilestoneId = escapeLinearGraphQlString(milestoneId)
        return "{ projectMilestone: { id: { eq: \"$escapedMilestoneId\" } } }"
    }

    fun completedAtFilter(completedAfter: Instant?, completedBefore: Instant?): String {
        if (completedAfter == null && completedBefore == null) return ""

        return buildString {
            append("completedAt: { ")
            val filters = mutableListOf<String>()
            if (completedAfter != null) {
                filters.add("gte: \"${escapeLinearGraphQlString(completedAfter.toString())}\"")
            }
            if (completedBefore != null) {
                filters.add("lte: \"${escapeLinearGraphQlString(completedBefore.toString())}\"")
            }
            append(filters.joinToString(", "))
            append(" }")
        }
    }
}
