package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.LocalRepositoryConfig
import com.github.karlsabo.devlake.enghub.component.checkoutSetupStatus
import com.github.karlsabo.git.WorktreeSetupCommandRunner
import com.github.karlsabo.git.WorktreeSetupCoordinator
import com.github.karlsabo.git.WorktreeSetupStatus
import com.github.karlsabo.git.buildWorktreePath
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

class EngHubCheckoutSetupViewModelTest {

    @Test
    fun checkoutAndOpenRunsUnifiedRepositorySetupCommands() = runBlocking {
        val repositoriesBaseDir = createTempDir("repositories")
        val repoPath = Path(repositoriesBaseDir, "example-service").toString()
        val worktreePath = Path(buildWorktreePath(repoPath, "feature/worktree-loading").value)
        val markerFileName = "unified-checkout-setup.txt"
        val markerPath = Path(worktreePath, markerFileName)
        val markerContents = "checkout setup complete"
        SystemFileSystem.createDirectories(worktreePath)
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
                        path = "$repoPath/",
                        setupCommands = listOf(writeSetupFileCommand(markerFileName, markerContents)),
                    ),
                ),
                testConfig = LocalRepositoryViewModelTestConfig(
                    repositoriesBaseDir = repositoriesBaseDir,
                    setupShell = nativeSetupShell(),
                ),
            )

            val checkoutJob = viewModel.checkoutAndOpen("example-org/example-service", "feature/worktree-loading")

            withTimeout(10_000.milliseconds) { checkoutJob.join() }

            assertEquals(markerContents, readText(markerPath))
            assertEquals(
                listOf(repoPath to "https://github.com/example-org/example-service.git"),
                api.ensureRepositoryCalls,
            )
            assertEquals(listOf(repoPath to "feature/worktree-loading"), api.ensureWorktreeCalls)
        } finally {
            removeTempDir(repositoriesBaseDir)
        }
    }

    @Test
    fun checkoutAndOpenTracksCoordinatorStatusPerWorktreePath(): Unit = runBlocking {
        val repositoriesBaseDir = createTempDir("repositories")
        val firstRepoPath = Path(repositoriesBaseDir, "example-service").toString()
        val secondRepoPath = Path(repositoriesBaseDir, "example-web").toString()
        try {
            val api = RecordingGitWorktreeApi(
                responses = RecordingGitWorktreeApiResponses(
                    worktreesByRepoPath = emptyMap(),
                ),
            )
            val setupRunner = BlockingCoordinatorSetupRunner()
            val viewModel = createCheckoutSetupViewModel(
                api = api,
                setupRunner = setupRunner,
                repositoriesBaseDir = repositoriesBaseDir,
                localRepositoryConfigs = listOf(
                    LocalRepositoryConfig(path = firstRepoPath, setupCommands = listOf("setup first")),
                    LocalRepositoryConfig(path = secondRepoPath, setupCommands = listOf("setup second")),
                ),
            )
            val firstWorktreePath = buildWorktreePath(firstRepoPath, "feature/first")
            val secondWorktreePath = buildWorktreePath(secondRepoPath, "feature/second")

            viewModel.checkoutAndOpen("example-org/example-service", "feature/first")
            withTimeout(2_000.milliseconds) { setupRunner.awaitStarted(firstWorktreePath) }

            var statuses = viewModel.setupStatusesStateFlow.value
            assertEquals(WorktreeSetupStatus.RUNNING_SETUP_COMMANDS, statuses[firstWorktreePath])
            assertEquals(null, statuses[secondWorktreePath])

            viewModel.checkoutAndOpen("example-org/example-web", "feature/second")
            statuses = withTimeout(2_000.milliseconds) {
                viewModel.setupStatusesStateFlow.first { current ->
                    current[firstWorktreePath] == WorktreeSetupStatus.RUNNING_SETUP_COMMANDS &&
                        current[secondWorktreePath] == WorktreeSetupStatus.RUNNING_SETUP_COMMANDS
                }
            }

            assertEquals(setOf(firstWorktreePath, secondWorktreePath), statuses.keys)
            assertEquals(
                listOf(
                    firstRepoPath to "https://github.com/example-org/example-service.git",
                    secondRepoPath to "https://github.com/example-org/example-web.git",
                ),
                api.ensureRepositoryCalls,
            )
            assertEquals(
                listOf(firstRepoPath to "feature/first", secondRepoPath to "feature/second"),
                api.ensureWorktreeCalls,
            )

            setupRunner.complete(firstWorktreePath)
            setupRunner.complete(secondWorktreePath)
            withTimeout(2_000.milliseconds) {
                viewModel.setupStatusesStateFlow.first { it.isEmpty() }
            }
        } finally {
            removeTempDir(repositoriesBaseDir)
        }
    }

    @Test
    fun matchingPullRequestAndNotificationRowsShareCheckoutSetupProgress(): Unit = runBlocking {
        val repositoriesBaseDir = createTempDir("repositories")
        val repoPath = Path(repositoriesBaseDir, "example-service").toString()
        val repoFullName = "example-org/example-service"
        val branch = "feature/shared-progress"
        try {
            val api = RecordingGitWorktreeApi(
                responses = RecordingGitWorktreeApiResponses(
                    worktreesByRepoPath = emptyMap(),
                ),
            )
            val setupRunner = BlockingCoordinatorSetupRunner()
            val viewModel = createCheckoutSetupViewModel(
                api = api,
                setupRunner = setupRunner,
                repositoriesBaseDir = repositoriesBaseDir,
                localRepositoryConfigs = listOf(
                    LocalRepositoryConfig(path = repoPath, setupCommands = listOf("setup shared")),
                ),
            )
            val pullRequestWorktreePath = viewModel.checkoutWorktreePath(repoFullName, branch)
            val setupStatusFor = { currentRepoFullName: String, currentBranch: String ->
                viewModel.setupStatusesStateFlow.value[
                    viewModel.checkoutWorktreePath(
                        currentRepoFullName,
                        currentBranch,
                    ),
                ]
            }

            val firstCheckout = viewModel.checkoutAndOpen(repoFullName, branch)
            withTimeout(2_000.milliseconds) { setupRunner.awaitStarted(pullRequestWorktreePath) }

            assertEquals(
                WorktreeSetupStatus.RUNNING_SETUP_COMMANDS,
                sharedProgressPullRequest(repoFullName, branch).checkoutSetupStatus(setupStatusFor),
            )
            assertEquals(
                WorktreeSetupStatus.RUNNING_SETUP_COMMANDS,
                sharedProgressNotification(repoFullName, branch).checkoutSetupStatus(setupStatusFor),
            )

            val duplicateSetup = viewModel.requestCheckoutSetup(repoFullName, branch)

            assertEquals(
                listOf(repoPath to "https://github.com/$repoFullName.git"),
                api.ensureRepositoryCalls,
            )
            assertEquals(listOf(repoPath to branch), api.ensureWorktreeCalls)
            assertEquals(1, setupRunner.calls())

            setupRunner.complete(pullRequestWorktreePath)
            withTimeout(2_000.milliseconds) {
                firstCheckout.join()
                duplicateSetup.await()
                viewModel.setupStatusesStateFlow.first { it.isEmpty() }
            }
            assertEquals(1, setupRunner.calls())
        } finally {
            removeTempDir(repositoriesBaseDir)
        }
    }

    private fun createCheckoutSetupViewModel(
        api: RecordingGitWorktreeApi,
        setupRunner: WorktreeSetupCommandRunner,
        repositoriesBaseDir: String,
        localRepositoryConfigs: List<LocalRepositoryConfig>,
    ): EngHubViewModel = createLocalRepositoryViewModel(
        gitWorktreeApi = api,
        configWriter = RecordingEngHubConfigWriter(),
        localRepositoryConfigs = localRepositoryConfigs,
        testConfig = LocalRepositoryViewModelTestConfig(repositoriesBaseDir = repositoriesBaseDir),
        services = LocalRepositoryViewModelServices(
            worktreeSetupCoordinator = WorktreeSetupCoordinator(
                gitWorktreeApi = api,
                setupCommandRunner = setupRunner,
            ),
        ),
    )
}
