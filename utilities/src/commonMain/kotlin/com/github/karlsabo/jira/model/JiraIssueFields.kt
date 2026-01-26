package com.github.karlsabo.jira.model

import com.github.karlsabo.jira.serialization.CustomInstantSerializer
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class JiraIssueFields(
    val summary: String? = null,
    val description: IssueDescription? = null,
    @SerialName("issuetype")
    val issueType: IssueType? = null,
    val status: IssueStatus? = null,
    @SerialName("resolutiondate")
    @Serializable(with = CustomInstantSerializer::class)
    val resolutionDate: Instant? = null,
    @Serializable(with = CustomInstantSerializer::class)
    val created: Instant? = null,
    @Serializable(with = CustomInstantSerializer::class)
    val updated: Instant? = null,
    val parent: IssueParent? = null,
    val priority: IssuePriority? = null,
    val customfield_10100: Double? = null, // Story points
    val timeoriginalestimate: Long? = null,
    val timespent: Long? = null,
    val timeestimate: Long? = null,
    val creator: JiraUser? = null,
    val assignee: JiraUser? = null,
    val customfield_11203: CustomFieldValue? = null, // Severity
    val components: List<IssueComponent>? = null,
    val project: IssueProject? = null,
    val customfield_11202: CustomFieldValue? = null, // Urgency
    val customfield_10018: EpicLink? = null, // Epic link
    @Serializable(with = CustomInstantSerializer::class)
    @SerialName("duedate")
    val dueDate: Instant? = null,
)
