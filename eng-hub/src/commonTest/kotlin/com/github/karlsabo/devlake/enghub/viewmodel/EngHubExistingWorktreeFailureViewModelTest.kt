package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.LocalRepositoryConfig
import com.github.karlsabo.git.WorktreePath
import com.github.karlsabo.git.WorktreeSetupCoordinator
import com.github.karlsabo.git.buildWorktreePath
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class EngHubExistingWorktreeFailureViewModelTest {

    @Test
    fun openingExistingWorktreeSetupFailureSetsActionError() = runBlocking {
        val repoRoot = createTempDir("repo")
        val worktreePath = createTempDir("worktree")
        try {
            val viewModel = createLocalRepositoryViewModel(
                gitWorktreeApi = RecordingGitWorktreeApi(
                    responses = RecordingGitWorktreeApiResponses(
                        worktreesByRepoPath = emptyMap(),
                    ),
                ),
                configWriter = RecordingEngHubConfigWriter(),
                localRepositoryConfigs = listOf(
                    LocalRepositoryConfig(
                        path = repoRoot,
                        setupCommands = listOf("echo setup failed >&2", "exit 23"),
                    ),
                ),
                testConfig = LocalRepositoryViewModelTestConfig(setupShell = "/bin/bash"),
            )

            viewModel.openLocalWorktree(repoRoot, worktreePath)

            val actionError = withTimeout(2_000.milliseconds) {
                viewModel.actionErrorStateFlow.first { it != null }
            }

            assertTrue(actionError!!.message.contains("Setup failed for $worktreePath"))
            assertTrue(actionError.message.contains("Overall exit code: 23"))
            assertTrue(actionError.message.contains("[2/2] FAILED exit 23"))
            assertTrue(actionError.message.contains("stderr:\nsetup failed"))
            assertEquals(emptyMap(), viewModel.setupStatusesStateFlow.value)
        } finally {
            removeTempDir(repoRoot)
            removeTempDir(worktreePath)
        }
    }

    @Test
    fun concurrentSetupFailuresQueueDuplicateActionErrors() = runBlocking {
        val repoRoot = createTempDir("repo")
        val firstWorktreePath = createTempDir("worktree-first")
        val secondWorktreePath = createTempDir("worktree-second")
        val firstWorktreeKey = WorktreePath(firstWorktreePath)
        val secondWorktreeKey = WorktreePath(secondWorktreePath)
        val failureMessage = "setup failed"
        val setupRunner = FailingBlockingSetupRunner { failureMessage }
        val gitWorktreeApi = RecordingGitWorktreeApi(
            responses = RecordingGitWorktreeApiResponses(
                worktreesByRepoPath = emptyMap(),
            ),
        )
        try {
            val viewModel = createLocalRepositoryViewModel(
                gitWorktreeApi = gitWorktreeApi,
                configWriter = RecordingEngHubConfigWriter(),
                localRepositoryConfigs = listOf(
                    LocalRepositoryConfig(
                        path = repoRoot,
                        setupCommands = listOf("fail in test runner"),
                    ),
                ),
                services = LocalRepositoryViewModelServices(
                    worktreeSetupCoordinator = WorktreeSetupCoordinator(
                        gitWorktreeApi = gitWorktreeApi,
                        setupCommandRunner = setupRunner,
                    ),
                ),
            )

            viewModel.openLocalWorktree(repoRoot, firstWorktreePath)
            viewModel.openLocalWorktree(repoRoot, secondWorktreePath)
            withTimeout(2_000.milliseconds) { awaitStarted(setupRunner, firstWorktreeKey, secondWorktreeKey) }

            setupRunner.fail(firstWorktreeKey)
            setupRunner.fail(secondWorktreeKey)
            val firstError = withTimeout(2_000.milliseconds) {
                viewModel.actionErrorStateFlow.first { it != null }!!
            }
            viewModel.clearActionError()
            val secondError = withTimeout(2_000.milliseconds) {
                viewModel.actionErrorStateFlow.first { it != null }!!
            }

            assertEquals(failureMessage, firstError.message)
            assertEquals(failureMessage, secondError.message)
            assertTrue(secondError.id > firstError.id)
            viewModel.clearActionError()
            assertEquals(null, viewModel.actionErrorStateFlow.value)
            withTimeout(2_000.milliseconds) {
                viewModel.setupStatusesStateFlow.first { it.isEmpty() }
            }
            assertEquals(emptyMap(), viewModel.setupStatusesStateFlow.value)
        } finally {
            removeTempDir(repoRoot)
            removeTempDir(firstWorktreePath)
            removeTempDir(secondWorktreePath)
        }
    }

    @Test
    fun duplicateSetupFailureReportsOneActionErrorForSharedHandle() = runBlocking {
        val repositoriesBaseDir = createTempDir("repos")
        val repoFullName = "test-org/dev-lake-utils"
        val branch = "feature/duplicate-setup"
        val repoPath = "$repositoriesBaseDir/dev-lake-utils"
        val worktreePath = buildWorktreePath(repoPath, branch)
        val setupRunner = FailingBlockingSetupRunner()
        val gitWorktreeApi = RecordingGitWorktreeApi(
            responses = RecordingGitWorktreeApiResponses(
                worktreesByRepoPath = emptyMap(),
            ),
        )
        try {
            val viewModel = createLocalRepositoryViewModel(
                gitWorktreeApi = gitWorktreeApi,
                configWriter = RecordingEngHubConfigWriter(),
                localRepositoryConfigs = listOf(
                    LocalRepositoryConfig(
                        path = repoPath,
                        setupCommands = listOf("fail in test runner"),
                    ),
                ),
                testConfig = LocalRepositoryViewModelTestConfig(repositoriesBaseDir = repositoriesBaseDir),
                services = LocalRepositoryViewModelServices(
                    worktreeSetupCoordinator = WorktreeSetupCoordinator(
                        gitWorktreeApi = gitWorktreeApi,
                        setupCommandRunner = setupRunner,
                    ),
                ),
            )

            val firstJob = viewModel.checkoutAndOpen(repoFullName, branch)
            withTimeout(2_000.milliseconds) { setupRunner.awaitStarted(worktreePath) }
            val secondJob = viewModel.checkoutAndOpen(repoFullName, branch)

            assertEquals(1, setupRunner.calls())
            setupRunner.fail(worktreePath)
            withTimeout(2_000.milliseconds) {
                firstJob.join()
                secondJob.join()
            }

            val actionError = withTimeout(2_000.milliseconds) {
                viewModel.actionErrorStateFlow.first { it != null }
            }
            assertEquals("setup failed for ${worktreePath.value}", actionError?.message)
            viewModel.clearActionError()
            assertEquals(null, viewModel.actionErrorStateFlow.value)
            assertEquals(1, setupRunner.calls())
        } finally {
            removeTempDir(repositoriesBaseDir)
        }
    }

    private suspend fun awaitStarted(
        setupRunner: FailingBlockingSetupRunner,
        firstWorktreeKey: WorktreePath,
        secondWorktreeKey: WorktreePath,
    ) {
        setupRunner.awaitStarted(firstWorktreeKey)
        setupRunner.awaitStarted(secondWorktreeKey)
    }
}
