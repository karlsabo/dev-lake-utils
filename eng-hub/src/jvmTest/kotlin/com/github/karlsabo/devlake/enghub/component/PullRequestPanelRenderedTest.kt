package com.github.karlsabo.devlake.enghub.component

import androidx.compose.material.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.github.karlsabo.devlake.enghub.state.PullRequestUiState
import com.github.karlsabo.devlake.enghub.state.PullRequestsResult
import com.github.karlsabo.devlake.enghub.state.toPullRequestUiState
import com.github.karlsabo.devlake.enghub.viewmodel.EngHubViewModel
import com.github.karlsabo.devlake.enghub.viewmodel.LocalRepositoryViewModelServices
import com.github.karlsabo.devlake.enghub.viewmodel.RecordingEngHubConfigWriter
import com.github.karlsabo.devlake.enghub.viewmodel.RecordingGitWorktreeApi
import com.github.karlsabo.devlake.enghub.viewmodel.createLocalRepositoryViewModel
import com.github.karlsabo.github.CiStatus
import com.github.karlsabo.gitlab.GitLabMergeRequest
import com.github.karlsabo.gitlab.GitLabMergeRequestReferences
import com.github.karlsabo.system.DesktopLauncher
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

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

    @Test
    fun openInBrowserForGitLabMergeRequestOpensTheApiProvidedWebUrl() = runDesktopComposeUiTest(
        width = 1100,
        height = 600,
    ) {
        val launcher = RecordingDesktopLauncher()
        val viewModel = createPullRequestViewModel(launcher)
        val mergeRequest = mergeRequest(project = "games/atlas", title = "Atlas feature")

        setContent {
            MaterialTheme {
                PullRequestPanel(
                    pullRequestsResult = PullRequestsResult(
                        pullRequests = listOf(mergeRequest.toPullRequestUiState()),
                    ),
                    actions = PullRequestPanelActions(
                        onOpenInBrowser = viewModel.openInBrowser,
                        onCheckoutAndOpen = { _, _ -> },
                        setupStatusFor = { _, _ -> null },
                    ),
                )
            }
        }

        onNodeWithText("Open in Browser").performClick()

        waitUntil(timeoutMillis = 2_000) { launcher.openedUrls.isNotEmpty() }
        assertEquals(
            listOf("https://gitlab.internal.example/games/atlas/-/merge_requests/42"),
            launcher.openedUrls,
        )
        assertFalse(launcher.openedUrls.any { it.contains("github.com") })
    }

    @Test
    fun openInBrowserForGitHubPullRequestOpensItsApiProvidedHtmlUrl() = runDesktopComposeUiTest(
        width = 1100,
        height = 600,
    ) {
        val launcher = RecordingDesktopLauncher()
        val viewModel = createPullRequestViewModel(launcher)

        setContent {
            MaterialTheme {
                PullRequestPanel(
                    pullRequestsResult = PullRequestsResult(
                        pullRequests = listOf(gitHubPullRequest()),
                    ),
                    actions = PullRequestPanelActions(
                        onOpenInBrowser = viewModel.openInBrowser,
                        onCheckoutAndOpen = { _, _ -> },
                        setupStatusFor = { _, _ -> null },
                    ),
                )
            }
        }

        onNodeWithText("Open in Browser").performClick()

        waitUntil(timeoutMillis = 2_000) { launcher.openedUrls.isNotEmpty() }
        assertEquals(listOf("https://github.com/games/atlas/pull/7"), launcher.openedUrls)
    }

    private fun createPullRequestViewModel(launcher: DesktopLauncher): EngHubViewModel = createLocalRepositoryViewModel(
        gitWorktreeApi = RecordingGitWorktreeApi(),
        configWriter = RecordingEngHubConfigWriter(),
        services = LocalRepositoryViewModelServices(desktopLauncher = launcher),
    )

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

    private fun mergeRequest(project: String, title: String) = GitLabMergeRequest(
        iid = 42,
        title = title,
        webUrl = "https://gitlab.internal.example/$project/-/merge_requests/42",
        references = GitLabMergeRequestReferences(full = "$project!42"),
    )
}

private class RecordingDesktopLauncher : DesktopLauncher {
    private val urls = CopyOnWriteArrayList<String>()
    val openedUrls: List<String>
        get() = urls.toList()

    override fun openUrl(url: String) {
        urls += url
    }

    override fun openInIdea(projectPath: String) = Unit
}
