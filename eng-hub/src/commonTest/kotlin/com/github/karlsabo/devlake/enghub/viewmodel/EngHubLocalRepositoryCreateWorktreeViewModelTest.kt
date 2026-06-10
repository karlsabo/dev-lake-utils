package com.github.karlsabo.devlake.enghub.viewmodel

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds

class EngHubLocalRepositoryCreateWorktreeViewModelTest {

    @Test
    fun requestCreateLocalWorktreeFromRepositoryResolvesBaseFromDefaultBranchInference() = runBlocking {
        val api = RecordingGitWorktreeApi(
            repositoryWorktreesBySelectedPath = emptyMap(),
            responses = RecordingGitWorktreeApiResponses(
                defaultBranchRefsByRepoPath = mapOf(DEV_LAKE_ROOT to "main"),
            ),
        )
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = api,
            configWriter = RecordingEngHubConfigWriter(),
            localRepositoryConfigs = localRepositoryConfigs(DEV_LAKE_ROOT),
        )

        viewModel.requestCreateLocalWorktreeFromRepository(DEV_LAKE_ROOT)

        val request = withTimeout(2_000.milliseconds) {
            viewModel.lastCreateLocalWorktreeFromRepositoryRequestStateFlow.first { it != null }
        }

        assertEquals(listOf(DEV_LAKE_ROOT), api.inferDefaultBranchRefCalls)
        assertEquals(
            CreateLocalWorktreeFromRepositoryRequest(
                repoRootPath = DEV_LAKE_ROOT,
                baseWorktreePath = DEV_LAKE_ROOT,
                baseBranch = "main",
            ),
            request,
        )
        assertEquals(emptyList(), api.createBranchWorktreeCalls)
    }

    @Test
    fun requestCreateLocalWorktreeFromRepositoryReportsActionErrorWhenDefaultBranchIsAbsent() = runBlocking {
        val api = RecordingGitWorktreeApi(repositoryWorktreesBySelectedPath = emptyMap())
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = api,
            configWriter = RecordingEngHubConfigWriter(),
            localRepositoryConfigs = localRepositoryConfigs(DEV_LAKE_ROOT),
        )

        viewModel.requestCreateLocalWorktreeFromRepository(DEV_LAKE_ROOT)

        val actionError = withTimeout(2_000.milliseconds) {
            viewModel.actionErrorStateFlow.first { it != null }
        }

        assertEquals(listOf(DEV_LAKE_ROOT), api.inferDefaultBranchRefCalls)
        assertEquals("Could not infer default branch for $DEV_LAKE_ROOT", actionError?.message)
        assertNull(viewModel.lastCreateLocalWorktreeFromRepositoryRequestStateFlow.value)
        assertEquals(emptyList(), api.createBranchWorktreeCalls)
    }

    @Test
    fun requestCreateLocalWorktreeFromRepositoryReportsActionErrorWhenDefaultBranchInferenceThrows() = runBlocking {
        val api = RecordingGitWorktreeApi(
            repositoryWorktreesBySelectedPath = emptyMap(),
            responses = RecordingGitWorktreeApiResponses(
                inferDefaultBranchRefFailure = IllegalStateException("default branch lookup failed"),
            ),
        )
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = api,
            configWriter = RecordingEngHubConfigWriter(),
            localRepositoryConfigs = localRepositoryConfigs(DEV_LAKE_ROOT),
        )

        viewModel.requestCreateLocalWorktreeFromRepository(DEV_LAKE_ROOT)

        val actionError = withTimeout(2_000.milliseconds) {
            viewModel.actionErrorStateFlow.first { it != null }
        }

        assertEquals(listOf(DEV_LAKE_ROOT), api.inferDefaultBranchRefCalls)
        assertEquals("default branch lookup failed", actionError?.message)
        assertNull(viewModel.lastCreateLocalWorktreeFromRepositoryRequestStateFlow.value)
        assertEquals(emptyList(), api.createBranchWorktreeCalls)
    }
}
