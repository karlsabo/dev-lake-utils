package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.LocalRepositoryConfig
import com.github.karlsabo.git.WorktreePath
import com.github.karlsabo.git.WorktreeSetupCoordinator
import com.github.karlsabo.git.WorktreeSetupRequest
import com.github.karlsabo.git.WorktreeSetupStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

class EngHubExistingWorktreeOpenViewModelTest {

    @Test
    fun openingExistingWorktreeCoordinatesSetupForSelectedWorktreePath() = runBlocking {
        val repoRoot = "/virtual/repo"
        val worktreeKey = WorktreePath("/virtual/worktree")
        val setupCommands = listOf("prepare existing worktree")
        val setupRunner = BlockingCoordinatorSetupRunner()
        val api = RecordingGitWorktreeApi(
            responses = RecordingGitWorktreeApiResponses(
                worktreesByRepoPath = emptyMap(),
            ),
        )
        val viewModel = createExistingWorktreeOpenViewModel(
            api = api,
            setupRunner = setupRunner,
            localRepositoryConfig = LocalRepositoryConfig(path = repoRoot, setupCommands = setupCommands),
        )

        viewModel.openLocalWorktree(repoRoot, worktreeKey.value)
        withTimeout(2_000.milliseconds) { setupRunner.awaitStarted(worktreeKey) }

        assertEquals(
            mapOf(worktreeKey to WorktreeSetupStatus.RUNNING_SETUP_COMMANDS),
            viewModel.setupStatusesStateFlow.value,
        )
        assertEquals(
            WorktreeSetupRequest(
                repoPath = repoRoot,
                worktreePath = worktreeKey,
                setupShell = "/bin/test-shell",
                setupCommands = setupCommands,
            ),
            setupRunner.requestFor(worktreeKey),
        )
        assertEquals(emptyList(), api.ensureWorktreeCalls)
        assertEquals(emptyList(), api.ensureRepositoryCalls)

        setupRunner.complete(worktreeKey)

        val completedStatus = withTimeout(2_000.milliseconds) {
            viewModel.setupStatusesStateFlow.first { worktreeKey !in it }
        }
        assertEquals(emptyMap(), completedStatus)
    }

    @Test
    fun openingExistingWorktreeMatchesTrailingSlashRepositoryConfig() = runBlocking {
        val repoRoot = "/virtual/repo"
        val worktreeKey = WorktreePath("/virtual/worktree")
        val setupCommands = listOf("first setup command", "second setup command")
        val setupRunner = BlockingCoordinatorSetupRunner()
        val api = RecordingGitWorktreeApi(
            responses = RecordingGitWorktreeApiResponses(
                worktreesByRepoPath = emptyMap(),
            ),
        )
        val viewModel = createExistingWorktreeOpenViewModel(
            api = api,
            setupRunner = setupRunner,
            localRepositoryConfig = LocalRepositoryConfig(path = "$repoRoot/", setupCommands = setupCommands),
        )

        viewModel.openLocalWorktree(repoRoot, worktreeKey.value)
        withTimeout(2_000.milliseconds) { setupRunner.awaitStarted(worktreeKey) }

        assertEquals(
            mapOf(worktreeKey to WorktreeSetupStatus.RUNNING_SETUP_COMMANDS),
            viewModel.setupStatusesStateFlow.value,
        )
        assertEquals(
            WorktreeSetupRequest(
                repoPath = repoRoot,
                worktreePath = worktreeKey,
                setupShell = "/bin/test-shell",
                setupCommands = setupCommands,
            ),
            setupRunner.requestFor(worktreeKey),
        )
        assertEquals(emptyList(), api.ensureWorktreeCalls)
        assertEquals(emptyList(), api.ensureRepositoryCalls)

        setupRunner.complete(worktreeKey)

        val completedStatus = withTimeout(2_000.milliseconds) {
            viewModel.setupStatusesStateFlow.first { worktreeKey !in it }
        }
        assertEquals(emptyMap(), completedStatus)
    }

    private fun createExistingWorktreeOpenViewModel(
        api: RecordingGitWorktreeApi,
        setupRunner: BlockingCoordinatorSetupRunner,
        localRepositoryConfig: LocalRepositoryConfig,
    ): EngHubViewModel = createLocalRepositoryViewModel(
        gitWorktreeApi = api,
        configWriter = RecordingEngHubConfigWriter(),
        localRepositoryConfigs = listOf(localRepositoryConfig),
        services = LocalRepositoryViewModelServices(
            worktreeSetupCoordinator = WorktreeSetupCoordinator(
                gitWorktreeApi = api,
                setupCommandRunner = setupRunner,
            ),
        ),
        testConfig = LocalRepositoryViewModelTestConfig(setupShell = "/bin/test-shell"),
    )
}
