package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.github.GitHubRepositoryIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LocalRepositoryStateMappersTest {

    private val widgets = GitHubRepositoryIdentity(owner = "acme", repository = "widgets")

    @Test
    fun connectedPullRequestMatchesExactRepositoryAndBranch() {
        val matching = sharedProgressPullRequest("acme/widgets", "feature/login").copy(number = 123)
        val pullRequests = listOf(
            sharedProgressPullRequest("acme/other", "feature/login").copy(number = 999),
            sharedProgressPullRequest("acme/widgets", "feature/Login").copy(number = 998),
            matching,
        )

        assertEquals(matching, connectedPullRequest(widgets, "feature/login", pullRequests))
    }

    @Test
    fun connectedPullRequestChoosesHighestPullRequestNumber() {
        val pullRequests = listOf(
            sharedProgressPullRequest("acme/widgets", "feature/login").copy(number = 122),
            sharedProgressPullRequest("acme/widgets", "feature/login").copy(number = 123),
        )

        assertEquals(123, connectedPullRequest(widgets, "feature/login", pullRequests)?.number)
    }

    @Test
    fun connectedPullRequestRequiresResolvedGitHubRepositoryIdentity() {
        val pullRequest = sharedProgressPullRequest("acme/widgets", "feature/login").copy(number = 123)

        assertNull(connectedPullRequest(null, "feature/login", listOf(pullRequest)))
    }
}
