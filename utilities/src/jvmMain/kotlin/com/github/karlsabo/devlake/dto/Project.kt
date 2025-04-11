package com.github.karlsabo.devlake.dto

import kotlinx.serialization.Serializable

@Serializable
data class Project(
    val id: Long,
    val parentId: Long? = null,
    val title: String? = null,
    val links: List<String>? = emptyList<String>(),
    val slackProjectChannel: String? = null,
    val projectLeadUserId: String? = null,
    val projectContributors: List<String>? = emptyList<String>(),
    val productManager: String? = null,
    val topLevelIssueKeys: List<String> = emptyList<String>(),
    val topLevelIssueIds: List<String> = emptyList<String>(),
)
