package com.github.karlsabo.git

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

private class FakeGitWorktreeApi : GitWorktreeApi {
    var ensureRepositoryCalls = 0
    var ensureWorktreeCalls = 0
    lateinit var expectedRepoPath: String
    lateinit var expectedCloneUrl: String
    lateinit var expectedBranch: String
    var ensuredWorktreePath: WorktreePath? = null

    override fun ensureRepository(repoPath: String, cloneUrl: String) {
        assertEquals(expectedRepoPath, repoPath)
        assertEquals(expectedCloneUrl, cloneUrl)
        ensureRepositoryCalls += 1
    }

    override fun ensureWorktree(repoPath: String, branch: String): String {
        assertEquals(expectedRepoPath, repoPath)
        assertEquals(expectedBranch, branch)
        ensureWorktreeCalls += 1
        return requireNotNull(ensuredWorktreePath).value
    }

    override fun createBranchWorktree(
        repoPath: String,
        baseWorktreePath: String,
        baseBranch: String,
        targetBranch: String,
    ): String {
        error("createBranchWorktree is not used by WorktreeSetupCoordinator")
    }

    override fun worktreeExists(repoPath: String, branch: String): Boolean = false

    override fun resolveRepositoryRoot(selectedPath: String): RepositoryWorktrees =
        error("resolveRepositoryRoot is not used by WorktreeSetupCoordinator")

    override fun listWorktrees(repoPath: String): List<Worktree> =
        error("listWorktrees is not used by WorktreeSetupCoordinator")

    override fun removeWorktree(worktreePath: String, force: Boolean) {
        error("removeWorktree is not used by WorktreeSetupCoordinator")
    }

    override fun archiveWorktree(repoPath: String, worktreePath: String, force: Boolean) {
        error("archiveWorktree is not used by WorktreeSetupCoordinator")
    }
}

private class BlockingSetupRunner : WorktreeSetupCommandRunner {
    val started = CompletableDeferred<Unit>()
    val complete = CompletableDeferred<Unit>()
    var calls = 0

    override suspend fun runSetup(request: WorktreeSetupRequest): WorktreeSetupCommandResult {
        calls += 1
        started.complete(Unit)
        complete.await()
        return WorktreeSetupCommandResult(exitCode = 0, stdout = "setup complete", stderr = "")
    }
}

private class SerializingFakeGitWorktreeApi : GitWorktreeApi {
    val firstRepositoryEnsureStarted = CompletableDeferred<Unit>()
    val releaseFirstRepositoryEnsure = CompletableDeferred<Unit>()

    private val stateMutex = Mutex()
    private var repositoryEnsureCallCount = 0
    private var worktreeEnsureCallCount = 0
    private var activeRepositoryEnsures = 0
    private var maxActiveRepositoryEnsures = 0

    override fun ensureRepository(repoPath: String, cloneUrl: String) = runBlocking {
        val callNumber = stateMutex.withLock {
            repositoryEnsureCallCount += 1
            activeRepositoryEnsures += 1
            maxActiveRepositoryEnsures = maxOf(maxActiveRepositoryEnsures, activeRepositoryEnsures)
            repositoryEnsureCallCount
        }

        try {
            if (callNumber == 1) {
                firstRepositoryEnsureStarted.complete(Unit)
                releaseFirstRepositoryEnsure.await()
            }
        } finally {
            stateMutex.withLock { activeRepositoryEnsures -= 1 }
        }
    }

    override fun ensureWorktree(repoPath: String, branch: String): String = runBlocking {
        stateMutex.withLock { worktreeEnsureCallCount += 1 }
        buildWorktreePath(repoPath, branch).value
    }

    override fun createBranchWorktree(
        repoPath: String,
        baseWorktreePath: String,
        baseBranch: String,
        targetBranch: String,
    ): String {
        error("createBranchWorktree is not used by WorktreeSetupCoordinator")
    }

    override fun worktreeExists(repoPath: String, branch: String): Boolean = false

    override fun resolveRepositoryRoot(selectedPath: String): RepositoryWorktrees =
        error("resolveRepositoryRoot is not used by WorktreeSetupCoordinator")

    override fun listWorktrees(repoPath: String): List<Worktree> =
        error("listWorktrees is not used by WorktreeSetupCoordinator")

    override fun removeWorktree(worktreePath: String, force: Boolean) {
        error("removeWorktree is not used by WorktreeSetupCoordinator")
    }

    override fun archiveWorktree(repoPath: String, worktreePath: String, force: Boolean) {
        error("archiveWorktree is not used by WorktreeSetupCoordinator")
    }

    suspend fun repositoryEnsureCalls(): Int = stateMutex.withLock { repositoryEnsureCallCount }

    suspend fun worktreeEnsureCalls(): Int = stateMutex.withLock { worktreeEnsureCallCount }

    suspend fun maxConcurrentRepositoryEnsures(): Int = stateMutex.withLock { maxActiveRepositoryEnsures }
}

private class PerPathBlockingSetupRunner : WorktreeSetupCommandRunner {
    private val stateMutex = Mutex()
    private val started = mutableMapOf<WorktreePath, CompletableDeferred<Unit>>()
    private val complete = mutableMapOf<WorktreePath, CompletableDeferred<Unit>>()
    private var setupCallCount = 0

    override suspend fun runSetup(request: WorktreeSetupRequest): WorktreeSetupCommandResult {
        val completeSignal = stateMutex.withLock {
            setupCallCount += 1
            started.getOrPut(request.worktreePath) { CompletableDeferred() }.complete(Unit)
            complete.getOrPut(request.worktreePath) { CompletableDeferred() }
        }
        completeSignal.await()
        return WorktreeSetupCommandResult(exitCode = 0, stdout = "setup complete", stderr = "")
    }

    suspend fun awaitStarted(worktreePath: WorktreePath) {
        stateMutex.withLock {
            started.getOrPut(worktreePath) { CompletableDeferred() }
        }.await()
    }

    suspend fun complete(worktreePath: WorktreePath) {
        stateMutex.withLock {
            complete.getOrPut(worktreePath) { CompletableDeferred() }
        }.complete(Unit)
    }

    suspend fun calls(): Int = stateMutex.withLock { setupCallCount }
}

class WorktreeSetupCoordinatorTest {
    @Test
    fun duplicateSetupRequestsForSameWorktreeShareHandleAndRunUnderlyingSetupOnce() = runBlocking {
        val repoPath = "/repos/dev-lake-utils"
        val cloneUrl = "https://github.com/karlsabo/dev-lake-utils.git"
        val branch = "feature/setup"
        val worktreePath = buildWorktreePath(repoPath, branch)
        val git = FakeGitWorktreeApi().apply {
            expectedRepoPath = repoPath
            expectedCloneUrl = cloneUrl
            expectedBranch = branch
            ensuredWorktreePath = worktreePath
        }
        val setupRunner = BlockingSetupRunner()
        val coordinator = WorktreeSetupCoordinator(
            gitWorktreeApi = git,
            setupCommandRunner = setupRunner,
            scope = this,
        )
        val request = WorktreeSetupRequest(
            repoPath = repoPath,
            worktreePath = worktreePath,
            cloneUrl = cloneUrl,
            branch = branch,
            setupShell = "/bin/sh",
            setupCommands = listOf("echo setup"),
        )

        val first = coordinator.setup(request)
        withTimeout(1_000.milliseconds) { setupRunner.started.await() }
        val second = coordinator.setup(request)

        assertSame(first, second)
        assertFalse(first.isCompleted)
        assertEquals(1, git.ensureRepositoryCalls)
        assertEquals(1, git.ensureWorktreeCalls)
        assertEquals(1, setupRunner.calls)
        assertEquals(
            WorktreeSetupStatus.RUNNING_SETUP_COMMANDS,
            coordinator.statuses.value[worktreePath],
        )

        setupRunner.complete.complete(Unit)
        val result = withTimeout(1_000.milliseconds) { first.await() }

        assertEquals(worktreePath, result.worktreePath)
        assertEquals("setup complete", result.setupCommandResult?.stdout)
        assertEquals(1, git.ensureRepositoryCalls)
        assertEquals(1, git.ensureWorktreeCalls)
        assertEquals(1, setupRunner.calls)
        assertTrue(coordinator.statuses.value.isEmpty())
    }

    @Test
    fun duplicateSetupRequestDuringResultCompletionSharesInFlightHandle() = runBlocking {
        val repoPath = "/repos/dev-lake-utils"
        val cloneUrl = "https://github.com/karlsabo/dev-lake-utils.git"
        val branch = "feature/setup"
        val worktreePath = buildWorktreePath(repoPath, branch)
        val git = FakeGitWorktreeApi().apply {
            expectedRepoPath = repoPath
            expectedCloneUrl = cloneUrl
            expectedBranch = branch
            ensuredWorktreePath = worktreePath
        }
        var duplicate: WorktreeSetupHandle? = null
        lateinit var coordinator: WorktreeSetupCoordinator
        coordinator = WorktreeSetupCoordinator(
            gitWorktreeApi = git,
            scope = this,
            beforeResultCompletion = { completedRequest ->
                if (duplicate == null) {
                    duplicate = coordinator.setup(completedRequest)
                }
            },
        )
        val request = WorktreeSetupRequest(
            repoPath = repoPath,
            worktreePath = worktreePath,
            cloneUrl = cloneUrl,
            branch = branch,
        )

        val first = coordinator.setup(request)
        val result = withTimeout(1_000.milliseconds) { first.await() }
        val second = requireNotNull(duplicate)

        assertSame(first, second)
        assertEquals(worktreePath, result.worktreePath)
        assertEquals(1, git.ensureRepositoryCalls)
        assertEquals(1, git.ensureWorktreeCalls)
        assertTrue(coordinator.statuses.value.isEmpty())
    }

    @Test
    fun repositoryEnsureIsSerializedPerRepoWhileDifferentWorktreesProceedIndependently() = runBlocking {
        val repoPath = "/repos/dev-lake-utils"
        val cloneUrl = "https://github.com/karlsabo/dev-lake-utils.git"
        val firstBranch = "feature/first"
        val secondBranch = "feature/second"
        val firstWorktreePath = buildWorktreePath(repoPath, firstBranch)
        val secondWorktreePath = buildWorktreePath(repoPath, secondBranch)
        val git = SerializingFakeGitWorktreeApi()
        val setupRunner = PerPathBlockingSetupRunner()
        val setupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = WorktreeSetupCoordinator(
            gitWorktreeApi = git,
            setupCommandRunner = setupRunner,
            scope = setupScope,
        )
        val firstRequest = WorktreeSetupRequest(
            repoPath = repoPath,
            worktreePath = firstWorktreePath,
            cloneUrl = cloneUrl,
            branch = firstBranch,
            setupShell = "/bin/sh",
            setupCommands = listOf("echo first"),
        )
        val secondRequest = WorktreeSetupRequest(
            repoPath = repoPath,
            worktreePath = secondWorktreePath,
            cloneUrl = cloneUrl,
            branch = secondBranch,
            setupShell = "/bin/sh",
            setupCommands = listOf("echo second"),
        )

        try {
            val first = coordinator.setup(firstRequest)
            val second = coordinator.setup(secondRequest)
            withTimeout(1_000.milliseconds) { git.firstRepositoryEnsureStarted.await() }
            delay(100.milliseconds)

            assertEquals(1, git.repositoryEnsureCalls())
            assertEquals(1, git.maxConcurrentRepositoryEnsures())
            assertEquals(
                WorktreeSetupStatus.WAITING_FOR_REPOSITORY,
                coordinator.statuses.value[firstWorktreePath],
            )
            assertEquals(
                WorktreeSetupStatus.WAITING_FOR_REPOSITORY,
                coordinator.statuses.value[secondWorktreePath],
            )

            git.releaseFirstRepositoryEnsure.complete(Unit)
            withTimeout(1_000.milliseconds) { setupRunner.awaitStarted(firstWorktreePath) }
            withTimeout(1_000.milliseconds) { setupRunner.awaitStarted(secondWorktreePath) }

            assertFalse(first.isCompleted)
            assertFalse(second.isCompleted)
            assertEquals(2, git.repositoryEnsureCalls())
            assertEquals(1, git.maxConcurrentRepositoryEnsures())
            assertEquals(2, git.worktreeEnsureCalls())
            assertEquals(2, setupRunner.calls())

            setupRunner.complete(firstWorktreePath)
            setupRunner.complete(secondWorktreePath)

            assertEquals(firstWorktreePath, withTimeout(1_000.milliseconds) { first.await() }.worktreePath)
            assertEquals(secondWorktreePath, withTimeout(1_000.milliseconds) { second.await() }.worktreePath)
            assertTrue(coordinator.statuses.value.isEmpty())
        } finally {
            git.releaseFirstRepositoryEnsure.complete(Unit)
            setupRunner.complete(firstWorktreePath)
            setupRunner.complete(secondWorktreePath)
            setupScope.cancel()
        }
    }

    @Test
    fun cancelledAwaiterDoesNotCancelSharedSetupOperation() = runBlocking {
        val repoPath = "/repos/dev-lake-utils"
        val cloneUrl = "https://github.com/karlsabo/dev-lake-utils.git"
        val branch = "feature/setup"
        val worktreePath = buildWorktreePath(repoPath, branch)
        val git = FakeGitWorktreeApi().apply {
            expectedRepoPath = repoPath
            expectedCloneUrl = cloneUrl
            expectedBranch = branch
            ensuredWorktreePath = worktreePath
        }
        val setupRunner = BlockingSetupRunner()
        val coordinator = WorktreeSetupCoordinator(
            gitWorktreeApi = git,
            setupCommandRunner = setupRunner,
            scope = this,
        )
        val request = WorktreeSetupRequest(
            repoPath = repoPath,
            worktreePath = worktreePath,
            cloneUrl = cloneUrl,
            branch = branch,
            setupShell = "/bin/sh",
            setupCommands = listOf("echo setup"),
        )
        val sharedSetup = coordinator.setup(request)
        withTimeout(1_000.milliseconds) { setupRunner.started.await() }

        val awaiterStarted = CompletableDeferred<Unit>()
        val awaiter = async {
            awaiterStarted.complete(Unit)
            sharedSetup.await()
        }
        withTimeout(1_000.milliseconds) { awaiterStarted.await() }
        awaiter.cancelAndJoin()

        assertFalse(sharedSetup is Job)
        setupRunner.complete.complete(Unit)
        val result = withTimeout(1_000.milliseconds) { sharedSetup.await() }

        assertEquals(worktreePath, result.worktreePath)
        assertEquals(1, git.ensureRepositoryCalls)
        assertEquals(1, git.ensureWorktreeCalls)
        assertEquals(1, setupRunner.calls)
    }
}
