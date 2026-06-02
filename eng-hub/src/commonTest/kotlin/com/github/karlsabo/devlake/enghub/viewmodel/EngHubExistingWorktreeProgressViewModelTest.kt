package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.LocalRepositoryConfig
import com.github.karlsabo.devlake.enghub.component.checkoutSetupStatus
import com.github.karlsabo.git.WorktreePath
import com.github.karlsabo.git.WorktreeSetupCommandRunner
import com.github.karlsabo.git.WorktreeSetupCoordinator
import com.github.karlsabo.git.WorktreeSetupStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

class EngHubExistingWorktreeProgressViewModelTest {

    @Test
    fun existingWorktreeSetupSharesProgressWithMatchingPullRequestAndNotificationRows(): Unit = runBlocking {
        val repositoriesBaseDir = createTempDir("repositories")
        val repoFullName = "example-org/example-service"
        val repoPath = Path(repositoriesBaseDir, repoFullName.substringAfterLast('/')).toString()
        val branch = "feature/existing-worktree"
        try {
            val api = RecordingGitWorktreeApi(
                responses = RecordingGitWorktreeApiResponses(
                    worktreesByRepoPath = emptyMap(),
                ),
            )
            val setupRunner = BlockingCoordinatorSetupRunner()
            val viewModel = createExistingWorktreeSetupViewModel(
                api = api,
                setupRunner = setupRunner,
                repositoriesBaseDir = repositoriesBaseDir,
                repoPath = repoPath,
            )
            val worktreePath = viewModel.checkoutWorktreePath(repoFullName, branch)
            val setupStatusFor = { currentRepoFullName: String, currentBranch: String ->
                viewModel.setupStatusesStateFlow.value[
                    viewModel.checkoutWorktreePath(
                        currentRepoFullName,
                        currentBranch,
                    ),
                ]
            }

            viewModel.openLocalWorktree(repoPath, worktreePath.value)
            withTimeout(2_000.milliseconds) { setupRunner.awaitStarted(worktreePath) }

            assertEquals(
                WorktreeSetupStatus.RUNNING_SETUP_COMMANDS,
                sharedProgressPullRequest(repoFullName, branch).checkoutSetupStatus(setupStatusFor),
            )
            assertEquals(
                WorktreeSetupStatus.RUNNING_SETUP_COMMANDS,
                sharedProgressNotification(repoFullName, branch).checkoutSetupStatus(setupStatusFor),
            )

            val duplicateCheckoutSetup = viewModel.requestCheckoutSetup(repoFullName, branch)

            assertEquals(emptyList(), api.ensureRepositoryCalls)
            assertEquals(emptyList(), api.ensureWorktreeCalls)
            assertEquals(1, setupRunner.calls())

            setupRunner.complete(worktreePath)
            withTimeout(2_000.milliseconds) {
                duplicateCheckoutSetup.await()
                viewModel.setupStatusesStateFlow.first { it.isEmpty() }
            }
            assertEquals(1, setupRunner.calls())
        } finally {
            removeTempDir(repositoriesBaseDir)
        }
    }

    @Test
    fun openingExistingWorktreeTracksProgressForSelectedWorktreeOnly() = runBlocking {
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
                        path = repoRoot,
                        setupCommands = listOf("while [ ! -f release-open ]; do sleep 0.01; done"),
                    ),
                ),
                testConfig = LocalRepositoryViewModelTestConfig(setupShell = "/bin/bash"),
            )

            viewModel.openLocalWorktree(repoRoot, worktreePath)

            val inProgress = withTimeout(2_000.milliseconds) {
                viewModel.setupStatusesStateFlow.first { worktreeKey in it }
            }
            assertEquals(
                mapOf(worktreeKey to WorktreeSetupStatus.RUNNING_SETUP_COMMANDS),
                inProgress,
            )

            writeText(Path(worktreePath, "release-open"), "")
            val completed = withTimeout(2_000.milliseconds) {
                viewModel.setupStatusesStateFlow.first { worktreeKey !in it }
            }
            assertEquals(emptyMap(), completed)
        } finally {
            removeTempDir(repoRoot)
            removeTempDir(worktreePath)
        }
    }

    @Test
    fun concurrentExistingWorktreeCompletionsClearAllProgress() = runBlocking {
        val repoRoot = createTempDir("repo")
        val firstWorktreePath = createTempDir("worktree-first")
        val secondWorktreePath = createTempDir("worktree-second")
        val firstWorktreeKey = WorktreePath(firstWorktreePath)
        val secondWorktreeKey = WorktreePath(secondWorktreePath)
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
                        setupCommands = listOf("while [ ! -f release-open ]; do sleep 0.01; done"),
                    ),
                ),
                testConfig = LocalRepositoryViewModelTestConfig(setupShell = "/bin/bash"),
            )

            viewModel.openLocalWorktree(repoRoot, firstWorktreePath)
            viewModel.openLocalWorktree(repoRoot, secondWorktreePath)

            val inProgress = withTimeout(2_000.milliseconds) {
                viewModel.setupStatusesStateFlow.first { statuses ->
                    firstWorktreeKey in statuses && secondWorktreeKey in statuses
                }
            }
            assertEquals(
                mapOf(
                    firstWorktreeKey to WorktreeSetupStatus.RUNNING_SETUP_COMMANDS,
                    secondWorktreeKey to WorktreeSetupStatus.RUNNING_SETUP_COMMANDS,
                ),
                inProgress,
            )

            writeText(Path(firstWorktreePath, "release-open"), "")
            writeText(Path(secondWorktreePath, "release-open"), "")
            val completed = withTimeout(2_000.milliseconds) {
                viewModel.setupStatusesStateFlow.first { statuses ->
                    firstWorktreeKey !in statuses && secondWorktreeKey !in statuses
                }
            }
            assertEquals(emptyMap(), completed)
        } finally {
            removeTempDir(repoRoot)
            removeTempDir(firstWorktreePath)
            removeTempDir(secondWorktreePath)
        }
    }

    @Test
    fun concurrentDuplicateOpenAttemptsStartOneSetupJob() = runBlocking {
        val repoRoot = createTempDir("repo")
        val worktreePath = createTempDir("worktree")
        val worktreeKey = WorktreePath(worktreePath)
        val setupCountPath = Path(repoRoot, "setup-count.txt")
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
                        setupCommands = listOf(
                            "printf x >> '$setupCountPath'",
                            "while [ ! -f release-open ]; do sleep 0.01; done",
                        ),
                    ),
                ),
                testConfig = LocalRepositoryViewModelTestConfig(setupShell = "/bin/bash"),
            )
            val releaseOpenAttempts = CompletableDeferred<Unit>()
            val openAttempts = List(50) {
                async(Dispatchers.Default) {
                    releaseOpenAttempts.await()
                    viewModel.openLocalWorktree(repoRoot, worktreePath)
                }
            }

            releaseOpenAttempts.complete(Unit)
            openAttempts.awaitAll()
            val inProgress = withTimeout(2_000.milliseconds) {
                viewModel.setupStatusesStateFlow.first { worktreeKey in it }
            }
            assertEquals(
                mapOf(worktreeKey to WorktreeSetupStatus.RUNNING_SETUP_COMMANDS),
                inProgress,
            )

            writeText(Path(worktreePath, "release-open"), "")
            withTimeout(2_000.milliseconds) {
                viewModel.setupStatusesStateFlow.first { worktreeKey !in it }
            }

            assertEquals("x", readText(setupCountPath))
        } finally {
            removeTempDir(repoRoot)
            removeTempDir(worktreePath)
        }
    }

    private fun createExistingWorktreeSetupViewModel(
        api: RecordingGitWorktreeApi,
        setupRunner: WorktreeSetupCommandRunner,
        repositoriesBaseDir: String,
        repoPath: String,
    ): EngHubViewModel = createLocalRepositoryViewModel(
        gitWorktreeApi = api,
        configWriter = RecordingEngHubConfigWriter(),
        localRepositoryConfigs = listOf(
            LocalRepositoryConfig(path = repoPath, setupCommands = listOf("setup existing")),
        ),
        testConfig = LocalRepositoryViewModelTestConfig(repositoriesBaseDir = repositoriesBaseDir),
        services = LocalRepositoryViewModelServices(
            worktreeSetupCoordinator = WorktreeSetupCoordinator(
                gitWorktreeApi = api,
                setupCommandRunner = setupRunner,
            ),
        ),
    )
}
