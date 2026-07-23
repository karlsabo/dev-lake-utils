package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.LocalRepositoryConfig
import com.github.karlsabo.git.WorktreePath
import com.github.karlsabo.git.WorktreeSetupStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

class EngHubExistingWorktreeOpenViewModelTest {

    @Test
    fun openingExistingWorktreeRunsConfiguredSetupInSelectedWorktreePath() = runBlocking {
        val repoRoot = createTempDir("repo")
        val worktreePath = createTempDir("worktree")
        val worktreeKey = WorktreePath(worktreePath)
        try {
            val api = RecordingGitWorktreeApi(
                responses = RecordingGitWorktreeApiResponses(
                    worktreesByRepoPath = emptyMap(),
                ),
            )
            val viewModel = createLocalRepositoryViewModel(
                gitWorktreeApi = api,
                configWriter = RecordingEngHubConfigWriter(),
                localRepositoryConfigs = listOf(
                    LocalRepositoryConfig(
                        path = repoRoot,
                        setupCommands = listOf(
                            writeWorkingDirectorySetupCommand("opened-path.txt"),
                            waitForSetupFileCommand("release-open"),
                        ),
                    ),
                ),
                testConfig = LocalRepositoryViewModelTestConfig(setupShell = nativeSetupShell()),
            )

            viewModel.openLocalWorktree(repoRoot, worktreePath)

            val runningStatus = withTimeout(2_000.milliseconds) {
                viewModel.setupStatusesStateFlow.first {
                    it[worktreeKey] == WorktreeSetupStatus.RUNNING_SETUP_COMMANDS
                }
            }
            assertEquals(mapOf(worktreeKey to WorktreeSetupStatus.RUNNING_SETUP_COMMANDS), runningStatus)
            withTimeout(2_000.milliseconds) {
                while (!SystemFileSystem.exists(Path(worktreePath, "opened-path.txt"))) {
                    delay(10.milliseconds)
                }
            }

            val openedPath = readText(Path(worktreePath, "opened-path.txt")).trim()
            assertEquals(
                SystemFileSystem.resolve(Path(worktreePath)),
                SystemFileSystem.resolve(Path(openedPath)),
            )
            writeText(Path(worktreePath, "release-open"), "")
            val completedStatus = withTimeout(2_000.milliseconds) {
                viewModel.setupStatusesStateFlow.first { worktreeKey !in it }
            }
            assertEquals(emptyMap(), completedStatus)
            assertEquals(emptyList(), api.ensureWorktreeCalls)
            assertEquals(emptyList(), api.ensureRepositoryCalls)
        } finally {
            if (SystemFileSystem.exists(Path(worktreePath))) {
                writeText(Path(worktreePath, "release-open"), "")
            }
            removeTempDir(repoRoot)
            removeTempDir(worktreePath)
        }
    }

    @Test
    fun openingExistingWorktreeRunsUnifiedRepositorySetupCommands() = runBlocking {
        val repoRoot = createTempDir("repo")
        val worktreePath = createTempDir("worktree")
        val worktreeKey = WorktreePath(worktreePath)
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
                        path = "$repoRoot/",
                        setupCommands = listOf(
                            writeWorkingDirectorySetupCommand("unified-opened-path.txt"),
                            waitForSetupFileCommand("release-unified-open"),
                        ),
                    ),
                ),
                testConfig = LocalRepositoryViewModelTestConfig(setupShell = nativeSetupShell()),
            )

            viewModel.openLocalWorktree(repoRoot, worktreePath)

            val runningStatus = withTimeout(2_000.milliseconds) {
                viewModel.setupStatusesStateFlow.first {
                    it[worktreeKey] == WorktreeSetupStatus.RUNNING_SETUP_COMMANDS
                }
            }
            assertEquals(mapOf(worktreeKey to WorktreeSetupStatus.RUNNING_SETUP_COMMANDS), runningStatus)
            withTimeout(2_000.milliseconds) {
                while (!SystemFileSystem.exists(Path(worktreePath, "unified-opened-path.txt"))) {
                    delay(10.milliseconds)
                }
            }

            val openedPath = readText(Path(worktreePath, "unified-opened-path.txt")).trim()
            assertEquals(
                SystemFileSystem.resolve(Path(worktreePath)),
                SystemFileSystem.resolve(Path(openedPath)),
            )
            writeText(Path(worktreePath, "release-unified-open"), "")
            val completedStatus = withTimeout(2_000.milliseconds) {
                viewModel.setupStatusesStateFlow.first { worktreeKey !in it }
            }
            assertEquals(emptyMap(), completedStatus)
        } finally {
            if (SystemFileSystem.exists(Path(worktreePath))) {
                writeText(Path(worktreePath, "release-unified-open"), "")
            }
            removeTempDir(repoRoot)
            removeTempDir(worktreePath)
        }
    }
}
