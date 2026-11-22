package com.github.karlsabo.devlake.enghub.component

import androidx.compose.material.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.github.karlsabo.devlake.enghub.state.PullRequestsResult
import com.github.karlsabo.devlake.enghub.state.toPullRequestUiState
import com.github.karlsabo.gitlab.GitLabMergeRequest
import com.github.karlsabo.gitlab.GitLabMergeRequestReferences
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class PullRequestPanelRenderedTest {
    @Test
    fun mergeRequestsWithTheSameIidInDifferentProjectsBothRender() = runDesktopComposeUiTest(
        width = 1100,
        height = 600,
    ) {
        val atlas = mergeRequest(project = "games/atlas", title = "Atlas feature")
        val other = mergeRequest(project = "games/other", title = "Other feature")

        setContent {
            MaterialTheme {
                PullRequestPanel(
                    pullRequestsResult = PullRequestsResult(
                        pullRequests = listOf(atlas.toPullRequestUiState(), other.toPullRequestUiState()),
                    ),
                    actions = PullRequestPanelActions(
                        onOpenInBrowser = {},
                        onCheckoutAndOpen = { _, _ -> },
                        setupStatusFor = { _, _ -> null },
                    ),
                )
            }
        }

        onNodeWithText("!42 Atlas feature").assertIsDisplayed()
        onNodeWithText("!42 Other feature").assertIsDisplayed()
    }

    private fun mergeRequest(project: String, title: String) = GitLabMergeRequest(
        iid = 42,
        title = title,
        webUrl = "https://gitlab.internal.example/$project/-/merge_requests/42",
        references = GitLabMergeRequestReferences(full = "$project!42"),
    )
}
