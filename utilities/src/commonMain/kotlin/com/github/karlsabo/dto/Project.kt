package com.github.karlsabo.dto

import kotlinx.serialization.Serializable

@Serializable
data class Project(
    val id: Long,
    val parentId: Long? = null,
    val title: String? = null,
    val links: List<String>? = emptyList(),
    val slackProjectChannel: String? = null,
    val projectLeadUserId: String? = null,
    val projectContributors: List<String>? = emptyList(),
    val productManager: String? = null,
    val topLevelIssueKeys: List<String> = emptyList(),
    val isVerboseMilestones: Boolean = false,
    val isTagMilestoneOwners: Boolean = false,
)
