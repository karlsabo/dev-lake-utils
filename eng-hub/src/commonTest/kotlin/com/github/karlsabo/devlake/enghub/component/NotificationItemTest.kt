package com.github.karlsabo.devlake.enghub.component

import androidx.compose.material.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.github.karlsabo.devlake.enghub.state.NotificationUiState
import kotlin.test.Test

class NotificationItemTest {

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun pullRequestNotificationDoesNotRenderReviewButton() = runComposeUiTest {
        setContent {
            MaterialTheme {
                NotificationItem(
                    notification = pullRequestNotification(),
                    actions = notificationActions(),
                    setupStatus = null,
                )
            }
        }

        onNodeWithText("Approve").assertIsDisplayed()
        onAllNodesWithText("Review").assertCountEquals(0)
    }
}

private fun pullRequestNotification() = NotificationUiState(
    notificationThreadId = "123",
    title = "Remove notification review button",
    reason = "review_requested",
    updatedAtEpochMs = 0,
    repositoryFullName = "karlsabo/dev-lake-utils",
    subjectType = "PullRequest",
    htmlUrl = "https://github.com/karlsabo/dev-lake-utils/pull/1",
    apiUrl = "https://api.github.com/repos/karlsabo/dev-lake-utils/pulls/1",
    isPullRequest = true,
    unread = true,
)

private fun notificationActions() = NotificationActions(
    onOpenInBrowser = {},
    onCheckoutAndOpen = { _, _ -> },
    onApprove = {},
    onMarkDone = {},
    onUnsubscribe = {},
)
