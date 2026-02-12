package com.github.karlsabo.github

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Notification(
    val id: String,
    val unread: Boolean,
    val reason: String,
    @SerialName("updated_at")
    val updatedAt: Instant,
    @SerialName("last_read_at")
    val lastReadAt: Instant? = null,
    val subject: NotificationSubject,
    val repository: NotificationRepository,
)

@Serializable
data class NotificationSubject(
    val title: String,
    val url: String? = null,
    @SerialName("latest_comment_url")
    val latestCommentUrl: String? = null,
    val type: String,
)

@Serializable
data class NotificationRepository(
    val id: Long,
    val name: String,
    @SerialName("full_name")
    val fullName: String,
    @SerialName("html_url")
    val htmlUrl: String? = null,
)

val Notification.isPullRequest: Boolean get() = subject.type.equals("PullRequest", ignoreCase = true)
val Notification.subjectApiUrl: String? get() = subject.url
