package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.git.Worktree
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

class EngHubConfiguredRepositoryStartupViewModelTest {
    @Test
    fun configuredRepositoriesStartExpandedAndDiscoverWorktreesImmediately() = runBlocking {
        val discoveryStarted = CompletableDeferred<Unit>()
        val releaseDiscovery = CompletableDeferred<Unit>()
        val api = RecordingGitWorktreeApi(
            responses = RecordingGitWorktreeApiResponses(
                worktreesByRepoPath = mapOf(
                    DEV_LAKE_ROOT to listOf(
                        Worktree(path = DEV_LAKE_ROOT, branch = "main", commitHash = "abc123"),
                    ),
                    DOCS_ROOT to listOf(
                        Worktree(path = DOCS_ROOT, branch = "docs-main", commitHash = "123abc"),
                    ),
                ),
            ),
            callbacks = RecordingGitWorktreeApiCallbacks(
                onListWorktrees = {
                    discoveryStarted.complete(Unit)
                    runBlocking { releaseDiscovery.await() }
                },
            ),
        )
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = api,
            configWriter = RecordingEngHubConfigWriter(),
            localRepositoryConfigs = localRepositoryConfigs(DEV_LAKE_ROOT, DOCS_ROOT),
            testConfig = LocalRepositoryViewModelTestConfig(
                startConfiguredRepositoryPolling = true,
                startConfiguredRepositoriesExpanded = true,
                pollConfiguredRepositoriesImmediately = true,
            ),
        )

        val initialRepositories = viewModel.localRepositoriesStateFlow.value
        assertEquals(listOf(true, true), initialRepositories.map { it.isExpanded })
        assertEquals(listOf(true, true), initialRepositories.map { it.isLoading })
        withTimeout(2_000.milliseconds) { discoveryStarted.await() }

        releaseDiscovery.complete(Unit)
        val discoveredRepositories = withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { repositories ->
                repositories.all { !it.isLoading && it.worktrees.isNotEmpty() }
            }
        }

        assertEquals(setOf(DEV_LAKE_ROOT, DOCS_ROOT), api.listWorktreeRepoPaths.toSet())
        assertEquals(
            listOf("main"),
            discoveredRepositories.single { it.path == DEV_LAKE_ROOT }.worktrees.map { it.branch },
        )
        assertEquals(
            listOf("docs-main"),
            discoveredRepositories.single { it.path == DOCS_ROOT }.worktrees.map { it.branch },
        )
    }

    @Test
    fun configRefreshPreservesSessionRepositoryCollapseState() = runBlocking {
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = RecordingGitWorktreeApi(),
            configWriter = RecordingEngHubConfigWriter(),
            localRepositoryConfigs = localRepositoryConfigs(DEV_LAKE_ROOT),
            testConfig = LocalRepositoryViewModelTestConfig(startConfiguredRepositoriesExpanded = true),
        )

        viewModel.toggleLocalRepositoryExpansion(DEV_LAKE_ROOT)
        viewModel.updateConfig { config -> config.copy(setupShell = "/bin/bash") }

        val repository = viewModel.localRepositoriesStateFlow.value.single()
        assertEquals(false, repository.isExpanded)
        assertEquals(false, repository.isLoading)
    }
}
