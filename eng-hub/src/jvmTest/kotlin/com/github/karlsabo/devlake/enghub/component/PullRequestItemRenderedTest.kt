package com.github.karlsabo.devlake.enghub.component

import androidx.compose.material.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.github.karlsabo.devlake.enghub.state.PullRequestUiState
import com.github.karlsabo.devlake.enghub.state.toPullRequestUiState
import com.github.karlsabo.github.CiStatus
import com.github.karlsabo.gitlab.GitLabMergeRequest
import com.github.karlsabo.gitlab.GitLabMergeRequestReferences
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class PullRequestItemRenderedTest {
    @Test
    fun gitLabMergeRequestShowsTheBangReferenceAndProviderLabel() = runDesktopComposeUiTest(
        width = 1100,
        height = 400,
    ) {
        val mergeRequest = GitLabMergeRequest(
            iid = 42,
            title = "Add atlas feature",
            webUrl = "https://gitlab.internal.example/games/atlas/-/merge_requests/42",
            references = GitLabMergeRequestReferences(full = "games/atlas!42"),
        )

        setContent {
            MaterialTheme {
                PullRequestItem(
                    pr = mergeRequest.toPullRequestUiState(),
                    onOpenInBrowser = {},
                    onCheckoutAndOpen = { _, _ -> },
                    setupStatus = null,
                )
            }
        }

        onNodeWithText("!42 Add atlas feature").assertIsDisplayed()
        onNodeWithTag("pull-request-provider-GitLab").assertIsDisplayed()
        onNodeWithText("GitLab").assertIsDisplayed()
    }

    @Test
    fun gitHubPullRequestKeepsItsHashReferenceAndCiSummary() = runDesktopComposeUiTest(
        width = 1100,
        height = 400,
    ) {
        setContent {
            MaterialTheme {
                PullRequestItem(
                    pr = gitHubPullRequest(),
                    onOpenInBrowser = {},
                    onCheckoutAndOpen = { _, _ -> },
                    setupStatus = null,
                )
            }
        }

        onNodeWithText("#7 Add hub feature").assertIsDisplayed()
        onNodeWithTag("pull-request-provider-GitHub").assertIsDisplayed()
        onNodeWithText("0/0 passed").assertIsDisplayed()
        onNodeWithText("0/0 approved").assertIsDisplayed()
    }

    private fun gitHubPullRequest() = PullRequestUiState(
        number = 7,
        title = "Add hub feature",
        htmlUrl = "https://github.com/games/atlas/pull/7",
        repositoryFullName = "games/atlas",
        owner = "games",
        repo = "atlas",
        isDraft = false,
        ciStatus = CiStatus.PENDING,
        ciSummaryText = "0/0 passed",
        approvedCount = 0,
        requestedCount = 0,
        reviewSummaryText = "0/0 approved",
        headRef = "feature/hub",
        apiUrl = "https://api.github.com/repos/games/atlas/pulls/7",
    )
}
