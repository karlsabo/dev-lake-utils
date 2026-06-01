package com.github.karlsabo.github

interface GitHubNotificationApi {
    suspend fun listNotifications(): List<Notification>

    suspend fun markNotificationAsDone(threadId: String)

    suspend fun unsubscribeFromNotification(threadId: String)
}
