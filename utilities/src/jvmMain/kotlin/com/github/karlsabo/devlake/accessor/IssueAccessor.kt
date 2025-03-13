package com.github.karlsabo.devlake.accessor

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable


interface IssueAccessor {
    fun getIssues(limit: Int, offset: Int): List<Issue>
    fun getIssuesByKey(issueKey: String): Issue?
    fun getIssuesByKey(issueKeys: List<String>): List<Issue>
    fun getIssuesByCreatorId(creatorId: String): List<Issue>
    fun getIssuesByAssigneeId(assigneeId: String): List<Issue>
    fun getIssuesByAssigneeIdAndAfterResolutionDate(assigneeId: String, resolutionDate: Instant): List<Issue>
    fun getIssuesByParentIssueId(parentIssueId: String): List<Issue>
    fun getChildIssues(issueIds: List<String>): List<Issue>
    fun getAllChildIssues(issueIds: List<String>): Set<Issue>
}

@Serializable
data class Issue(
    val id: String,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
    val rawDataParams: String? = null,
    val rawDataTable: String? = null,
    val rawDataId: Long? = null,
    val rawDataRemark: String? = null,
    val url: String? = null,
    val iconUrl: String? = null,
    val issueKey: String,
    val title: String? = null,
    val description: String? = null,
    val epicKey: String? = null,
    val type: String? = null,
    val originalType: String? = null,
    val status: String? = null,
    val originalStatus: String? = null,
    val resolutionDate: Instant? = null,
    val createdDate: Instant? = null,
    val updatedDate: Instant? = null,
    val leadTimeMinutes: Long? = null,
    val parentIssueId: String? = null,
    val priority: String? = null,
    val storyPoint: Double? = null,
    val originalEstimateMinutes: Long? = null,
    val timeSpentMinutes: Long? = null,
    val timeRemainingMinutes: Long? = null,
    val creatorId: String? = null,
    val creatorName: String? = null,
    val assigneeId: String? = null,
    val assigneeName: String? = null,
    val severity: String? = null,
    val component: String? = null,
    val originalProject: String? = null,
    val urgency: String? = null,
    val isSubtask: Boolean? = null,
)

fun Issue.isIssueOrBug(): Boolean {
    if (type == null) return false
    return when (type.lowercase()) {
        "bug", "issue", "story", "subtask", "artifact", "task" -> true
        "epic", "theme", "parent artifact" -> false
        else -> {
            val message = "Unhandled issue type $type, Info: $issueKey, $title"
            print(message)
            throw RuntimeException(message)
        }
    }
}

fun Issue.isCompleted(): Boolean {
    return resolutionDate != null
}

fun Issue.isMilestone(): Boolean {
    return type != null && type.lowercase() == "epic"
}
