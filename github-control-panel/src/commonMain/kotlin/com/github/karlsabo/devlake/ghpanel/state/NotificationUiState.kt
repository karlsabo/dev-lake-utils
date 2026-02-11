package com.github.karlsabo.devlake.ghpanel.state

import com.github.karlsabo.github.Notification
import com.github.karlsabo.github.apiUrlToHtmlUrl

data class NotificationUiState(
    val threadId: String,
    val title: String,
    val reason: String,
    val repositoryFullName: String,
    val subjectType: String,
    val htmlUrl: String?,
    val apiUrl: String?,
    val isPullRequest: Boolean,
    val unread: Boolean,
    val headRef: String? = null,
)

fun Notification.toNotificationUiState(headRef: String? = null): NotificationUiState {
    val subjectUrl = subject.url
    val htmlUrl = if (subjectUrl != null) apiUrlToHtmlUrl(subjectUrl) else null

    return NotificationUiState(
        threadId = id,
        title = subject.title,
        reason = reason,
        repositoryFullName = repository.fullName,
        subjectType = subject.type,
        htmlUrl = htmlUrl,
        apiUrl = subjectUrl,
        isPullRequest = subject.type == "PullRequest",
        unread = unread,
        headRef = headRef,
    )
}
