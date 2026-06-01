package com.github.karlsabo.github

import com.github.karlsabo.tools.lenientJson
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders

private val logger = KotlinLogging.logger {}

internal class GitHubNotificationRestApi(
    private val restClient: GitHubRestClient,
) : GitHubNotificationApi {
    override suspend fun listNotifications(): List<Notification> {
        val notifications = mutableListOf<Notification>()
        var page = 1
        var hasMoreResults = true

        while (hasMoreResults) {
            val url = notificationsUrl(page)
            val response = restClient.client.get(url) {
                header(HttpHeaders.CacheControl, "no-cache")
            }
            val responseText = response.bodyAsText()

            if (response.status.value !in successStatusCodes) {
                logger.error {
                    "Failed to list notifications $url response.status=${response.status} " +
                        "responseText=```$responseText```"
                }
                throwGitHubApiException(
                    operation = "list notifications",
                    statusCode = response.status.value,
                    responseText = responseText,
                )
            }

            val pageNotifications = lenientJson.decodeFromString<List<Notification>>(responseText)
            hasMoreResults = pageNotifications.isNotEmpty()

            if (hasMoreResults) {
                notifications.addAll(pageNotifications)
                page++
            }
        }

        return notifications
    }

    override suspend fun markNotificationAsDone(threadId: String) {
        val url = "https://api.github.com/notifications/threads/$threadId"
        val response = restClient.client.delete(url)
        val responseText = response.bodyAsText()
        if (response.status.value !in successStatusCodes) {
            logger.error {
                "Failed to mark notification done $url response.status=${response.status} " +
                    "responseText=```$responseText```"
            }
            throwGitHubApiException(
                operation = "mark notification as done",
                statusCode = response.status.value,
                context = "for threadId=$threadId",
                responseText = responseText,
            )
        }
    }

    override suspend fun unsubscribeFromNotification(threadId: String) {
        val url = "https://api.github.com/notifications/threads/$threadId/subscription"
        val response = restClient.client.delete(url)
        if (response.status.value !in listOf(HTTP_NO_CONTENT, HTTP_NOT_FOUND)) {
            val responseText = response.bodyAsText()
            logger.error { "unsubscribeFromNotification responseText=```$responseText```" }
            throwGitHubApiException(
                operation = "unsubscribe from notification",
                statusCode = response.status.value,
                context = "for threadId=$threadId",
                responseText = responseText,
            )
        }
    }
}
