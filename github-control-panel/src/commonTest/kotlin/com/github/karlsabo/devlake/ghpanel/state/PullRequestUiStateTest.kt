package com.github.karlsabo.devlake.ghpanel.state

import com.github.karlsabo.github.CheckRunSummary
import com.github.karlsabo.github.CiStatus
import com.github.karlsabo.github.Issue
import com.github.karlsabo.github.Label
import com.github.karlsabo.github.ReviewSummary
import com.github.karlsabo.github.User
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals

class PullRequestUiStateTest {
    @Test
    fun rendersPendingReviewersWhenApprovalsAreStillOutstanding() {
        val uiState =
            testIssue().toPullRequestUiState(
                checkRunSummary = CheckRunSummary(total = 3, passed = 3, failed = 0, inProgress = 0, status = CiStatus.PASSED),
                reviewSummary = ReviewSummary(approvedCount = 0, requestedCount = 1, reviews = emptyList()),
                headRef = "feature/team-review",
            )

        assertEquals("waiting on 1 reviewer", uiState.reviewSummaryText)
    }
}

private fun testIssue(): Issue {
    val now = Clock.System.now()
    return Issue(
        url = "https://api.github.com/repos/test-org/test-repo/issues/25843",
        repositoryUrl = "https://api.github.com/repos/test-org/test-repo",
        id = 25843L,
        number = 25843,
        state = "open",
        title = "Count pending team reviewers",
        user = User(
            login = "test-user",
            id = 1L,
            avatarUrl = null,
            url = "https://api.github.com/users/test-user",
            htmlUrl = "https://github.com/test-user",
        ),
        body = null,
        htmlUrl = "https://github.com/test-org/test-repo/pull/25843",
        labels = emptyList<Label>(),
        draft = false,
        createdAt = now,
        updatedAt = now,
        closedAt = null,
        pullRequest = null,
        comments = 0,
    )
}
