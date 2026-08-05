package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.LocalRepositoryConfig
import com.github.karlsabo.devlake.enghub.component.checkoutSetupStatus
import com.github.karlsabo.git.WorktreeSetupCommandResult
import com.github.karlsabo.git.WorktreeSetupCommandRunner
import com.github.karlsabo.git.WorktreeSetupCoordinator
import com.github.karlsabo.git.WorktreeSetupRequest
import com.github.karlsabo.git.WorktreeSetupStatus
import com.github.karlsabo.git.buildWorktreePath
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

class EngHubCheckoutSetupViewModelTest {

    @Test
    fun checkoutAndOpenDelegatesCompleteSetupRequest() = runBlocking {
        val repositoriesBaseDir = Path("virtual", "repositories").toString()
        val repoPath = Path(repositoriesBaseDir, "example-service").toString()
        val branch = "feature/worktree-loading"
        val worktreePath = buildWorktreePath(repoPath, branch)
        val setupCommands = listOf("prepare checkout")
        val setupRequests = mutableListOf<WorktreeSetupRequest>()
        val setupRunner = WorktreeSetupCommandRunner { request ->
            setupRequests += request
            WorktreeSetupCommandResult(exitCode = 0, stdout = "setup complete", stderr = "")
        }
        val api = RecordingGitWorktreeApi(
            responses = RecordingGitWorktreeApiResponses(
                worktreesByRepoPath = emptyMap(),
            ),
        )
        val viewModel = createCheckoutSetupViewModel(
            api = api,
            setupRunner = setupRunner,
            repositoriesBaseDir = repositoriesBaseDir,
            localRepositoryConfigs = listOf(
                LocalRepositoryConfig(path = "$repoPath/", setupCommands = setupCommands),
            ),
        )

        val checkoutJob = viewModel.checkoutAndOpen("example-org/example-service", branch)

        checkoutJob.join()

        assertEquals(
            listOf(
                WorktreeSetupRequest(
                    repoPath = repoPath,
                    worktreePath = worktreePath,
                    cloneUrl = "https://github.com/example-org/example-service.git",
                    branch = branch,
                    setupShell = "/bin/zsh",
                    setupCommands = setupCommands,
                ),
            ),
            setupRequests,
        )
        assertEquals(
            listOf(repoPath to "https://github.com/example-org/example-service.git"),
            api.ensureRepositoryCalls,
        )
        assertEquals(listOf(repoPath to branch), api.ensureWorktreeCalls)
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
