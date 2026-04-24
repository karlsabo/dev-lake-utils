package com.github.karlsabo.devlake.enghub.state

import kotlin.test.Test
import kotlin.test.assertEquals

class NotificationUiStateTest {
    @Test
    fun displayTitlePrefixesPullRequestNumber() {
        val uiState = NotificationUiState(
            notificationThreadId = "thread-1234",
            title = "Ship the slice",
            reason = "review_requested",
            repositoryFullName = "test-org/test-repo",
            subjectType = "PullRequest",
            htmlUrl = "https://github.com/test-org/test-repo/pull/1234",
            apiUrl = "https://api.github.com/repos/test-org/test-repo/pulls/1234",
            isPullRequest = true,
            pullRequestNumber = 1234,
            unread = true,
            headRef = "feature/pr-number",
        )

        assertEquals("#1234 Ship the slice", uiState.displayTitle)
    }
}
