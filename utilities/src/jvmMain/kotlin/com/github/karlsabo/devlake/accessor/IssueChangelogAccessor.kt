package com.github.karlsabo.devlake.accessor

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

interface IssueChangelogAccessor {
    fun getChangelogsByIssueId(issueId: String): List<IssueChangelog>
    fun getChangelogById(changelogId: String): IssueChangelog?
    fun getPaginatedChangelogsByIssueIdsAndField(
        issueIds: Set<String>,
        fieldNames: Set<String>,
        excludedAuthorNames: Set<String>,
        limit: Int,
        offset: Int
    ): List<IssueChangelog>
}

@Serializable
data class IssueChangelog(
    val id: String,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
    val rawDataParams: String? = null,
    val rawDataTable: String? = null,
    val rawDataId: Long? = null,
    val rawDataRemark: String? = null,
    val issueId: String? = null,
    val authorId: String? = null,
    val authorName: String? = null,
    val fieldId: String? = null,
    val fieldName: String? = null,
    val originalFromValue: String? = null,
    val originalToValue: String? = null,
    val fromValue: String? = null,
    val toValue: String? = null,
    val createdDate: Instant? = null,
)
