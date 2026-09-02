package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.LocalRepositoryConfig
import com.github.karlsabo.github.GitHubRepositoryIdentity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

class GlobalQualifiedBranchDiscoveryViewModelTest {
    @Test
    fun globalBranchDiscoveryRecordsGitHubOriginIdentityForConfiguredRepositories() = runBlocking {
        val git = RecordingGitWorktreeApi(
            responses = RecordingGitWorktreeApiResponses(
                existingBranchesByRepoPath = mapOf(
                    DEV_LAKE_ROOT to listOf("main", "feature/shared"),
                    ENGINEERING_DOCS_ROOT to listOf("main", "feature/shared"),
                ),
                originUrlsByRepoPath = mapOf(
                    DEV_LAKE_ROOT to "git@github.com:Owner/Dev-Lake-Utils.git",
                    ENGINEERING_DOCS_ROOT to "https://gitlab.com/owner/engineering-docs.git",
                ),
            ),
        )
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = git,
            configWriter = RecordingEngHubConfigWriter(),
            localRepositoryConfigs = listOf(
                LocalRepositoryConfig(path = DEV_LAKE_ROOT),
                LocalRepositoryConfig(path = ENGINEERING_DOCS_ROOT),
            ),
        )

        viewModel.discoverGlobalExistingBranches()

        val discovery = withTimeout(5.seconds) {
            viewModel.globalExistingBranchDiscoveryStateFlow.first { !it.isLoading }
        }
        assertEquals(
            GitHubRepositoryIdentity("Owner", "Dev-Lake-Utils"),
            discovery.repositories.getValue(DEV_LAKE_ROOT).repositoryIdentity,
        )
        assertNull(discovery.repositories.getValue(ENGINEERING_DOCS_ROOT).repositoryIdentity)
    }
}

private const val ENGINEERING_DOCS_ROOT = "/repos/engineering-docs"
