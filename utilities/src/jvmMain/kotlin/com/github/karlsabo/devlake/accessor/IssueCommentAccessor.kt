package com.github.karlsabo.devlake.accessor

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

interface IssueCommentAccessor {
    fun getCommentsByIssueId(issueId: String): List<IssueComment>
    fun getCommentById(commentId: String): IssueComment?
    fun getPaginatedCommentsByIssueIds(issueId: Set<String>, limit: Int, offset: Int): List<IssueComment>
}

@Serializable
data class IssueComment(
    val id: String,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
    val rawDataParams: String? = null,
    val rawDataTable: String? = null,
    val rawDataId: Long? = null,
    val rawDataRemark: String? = null,
    val issueId: String? = null,
    val body: String? = null,
    val accountId: String? = null,
    val createdDate: Instant? = null,
    val updatedDate: Instant? = null
)
