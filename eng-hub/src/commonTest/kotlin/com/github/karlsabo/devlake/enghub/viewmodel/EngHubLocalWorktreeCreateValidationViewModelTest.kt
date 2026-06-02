package com.github.karlsabo.devlake.enghub.viewmodel

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

class EngHubLocalWorktreeCreateValidationViewModelTest {

    @Test
    fun createLocalWorktreeFromBaseRejectsInvalidTargetBranchWithoutCreatingWorktree() = runBlocking {
        val api = RecordingGitWorktreeApi(repositoryWorktreesBySelectedPath = emptyMap())
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = api,
            configWriter = RecordingEngHubConfigWriter(),
            localRepositoryConfigs = localRepositoryConfigs(DEV_LAKE_ROOT),
        )

        viewModel.createLocalWorktreeFromBase(
            repoRootPath = DEV_LAKE_ROOT,
            baseWorktreePath = "$DEV_LAKE_ROOT-feature-base-pr",
            baseBranch = "feature/base-pr",
            targetBranch = "feature/stacked pr",
        )

        val actionError = withTimeout(2_000.milliseconds) {
            viewModel.actionErrorStateFlow.first { it != null }
        }

        assertEquals("Invalid target branch: Branch name must not contain whitespace", actionError?.message)
        assertEquals(emptyList(), api.createBranchWorktreeCalls)
    }

    @Test
    fun createLocalWorktreeFromBaseRejectsTargetBranchMatchingBaseWithoutCreatingWorktree() = runBlocking {
        val api = RecordingGitWorktreeApi(repositoryWorktreesBySelectedPath = emptyMap())
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = api,
            configWriter = RecordingEngHubConfigWriter(),
            localRepositoryConfigs = localRepositoryConfigs(DEV_LAKE_ROOT),
        )

        viewModel.createLocalWorktreeFromBase(
            repoRootPath = DEV_LAKE_ROOT,
            baseWorktreePath = "$DEV_LAKE_ROOT-feature-base-pr",
            baseBranch = "feature/base-pr",
            targetBranch = "feature/base-pr",
        )

        val actionError = withTimeout(2_000.milliseconds) {
            viewModel.actionErrorStateFlow.first { it != null }
        }

        assertEquals("Target branch must differ from the base branch", actionError?.message)
        assertEquals(emptyList(), api.createBranchWorktreeCalls)
    }
}
