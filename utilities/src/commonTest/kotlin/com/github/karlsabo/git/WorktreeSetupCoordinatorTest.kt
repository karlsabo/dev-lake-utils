package com.github.karlsabo.git

import com.github.karlsabo.system.executeCommand
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
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

private class FakeGitWorktreeApi : GitWorktreeApi {
    var ensureRepositoryCalls = 0
    var ensureWorktreeCalls = 0
    var createBranchWorktreeCalls = 0
    var createBranchWorktreeFromCommitIshCalls = 0
    lateinit var expectedRepoPath: String
    lateinit var expectedCloneUrl: String
    lateinit var expectedBranch: String
    lateinit var expectedBaseWorktreePath: String
    lateinit var expectedBaseBranch: String
    lateinit var expectedBaseCommitIsh: String
    lateinit var expectedTargetBranch: String
    var expectedAllowUnrelatedExistingBranch: Boolean = false
    var ensuredWorktreePath: WorktreePath? = null
    var createdBranchWorktreePath: WorktreePath? = null
    var onCreateBranchWorktree: () -> Unit = {}
    var onCreateBranchWorktreeFromCommitIsh: () -> Unit = {}

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
        allowUnrelatedExistingBranch: Boolean,
    ): String {
        assertEquals(expectedRepoPath, repoPath)
        assertEquals(expectedBaseWorktreePath, baseWorktreePath)
        assertEquals(expectedBaseBranch, baseBranch)
        assertEquals(expectedTargetBranch, targetBranch)
        assertEquals(expectedAllowUnrelatedExistingBranch, allowUnrelatedExistingBranch)
        createBranchWorktreeCalls += 1
        onCreateBranchWorktree()
        return requireNotNull(createdBranchWorktreePath).value
    }

    override fun createBranchWorktreeFromCommitIsh(
        repoPath: String,
        baseWorktreePath: String,
        baseCommitIsh: String,
        targetBranch: String,
    ): String {
        assertEquals(expectedRepoPath, repoPath)
        assertEquals(expectedBaseWorktreePath, baseWorktreePath)
        assertEquals(expectedBaseCommitIsh, baseCommitIsh)
        assertEquals(expectedTargetBranch, targetBranch)
        createBranchWorktreeFromCommitIshCalls += 1
        onCreateBranchWorktreeFromCommitIsh()
        return requireNotNull(createdBranchWorktreePath).value
    }

    override fun worktreeExists(repoPath: String, branch: String): Boolean = false

    override fun isBranchAncestor(
        repoPath: String,
        baseBranch: String,
        childBranch: String,
    ): Boolean = error("isBranchAncestor is not used by WorktreeSetupCoordinator")

    override fun resolveRepositoryRoot(selectedPath: String): RepositoryWorktrees {
        error("resolveRepositoryRoot is not used by WorktreeSetupCoordinator")
    }

    override fun listWorktrees(repoPath: String): List<Worktree> {
        error("listWorktrees is not used by WorktreeSetupCoordinator")
    }

    override fun inferDefaultBranchRef(repoPath: String): String? {
        error("inferDefaultBranchRef is not used by WorktreeSetupCoordinator")
    }

    override fun inferWorktreeParentBranches(repoPath: String): Map<String, String> {
        error("inferWorktreeParentBranches is not used by WorktreeSetupCoordinator")
    }

    override fun removeWorktree(worktreePath: String, force: Boolean) {
        error("removeWorktree is not used by WorktreeSetupCoordinator")
    }

    override fun archiveWorktree(
        repoPath: String,
        worktreePath: String,
        force: Boolean,
    ) {
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
        allowUnrelatedExistingBranch: Boolean,
    ): String {
        error("createBranchWorktree is not used by WorktreeSetupCoordinator")
    }

    override fun worktreeExists(repoPath: String, branch: String): Boolean = false

    override fun isBranchAncestor(
        repoPath: String,
        baseBranch: String,
        childBranch: String,
    ): Boolean = error("isBranchAncestor is not used by WorktreeSetupCoordinator")

    override fun resolveRepositoryRoot(selectedPath: String): RepositoryWorktrees {
        error("resolveRepositoryRoot is not used by WorktreeSetupCoordinator")
    }

    override fun listWorktrees(repoPath: String): List<Worktree> {
        error("listWorktrees is not used by WorktreeSetupCoordinator")
    }

    override fun inferDefaultBranchRef(repoPath: String): String? {
        error("inferDefaultBranchRef is not used by WorktreeSetupCoordinator")
    }

    override fun inferWorktreeParentBranches(repoPath: String): Map<String, String> {
        error("inferWorktreeParentBranches is not used by WorktreeSetupCoordinator")
    }

    override fun removeWorktree(worktreePath: String, force: Boolean) {
        error("removeWorktree is not used by WorktreeSetupCoordinator")
    }

    override fun archiveWorktree(
        repoPath: String,
        worktreePath: String,
        force: Boolean,
    ) {
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

private class RepositorySerializationFixture {
    val repoPath = "/repos/dev-lake-utils"
    val firstWorktreePath = buildWorktreePath(repoPath, "feature/first")
    val secondWorktreePath = buildWorktreePath(repoPath, "feature/second")
    val git = SerializingFakeGitWorktreeApi()
    val setupRunner = PerPathBlockingSetupRunner()

    private val cloneUrl = "https://github.com/karlsabo/dev-lake-utils.git"
    private val setupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val coordinator = WorktreeSetupCoordinator(
        gitWorktreeApi = git,
        setupCommandRunner = setupRunner,
        scope = setupScope,
    )
    val firstRequest = setupRequest(firstWorktreePath, "feature/first", "echo first")
    val secondRequest = setupRequest(secondWorktreePath, "feature/second", "echo second")

    private fun setupRequest(
        worktreePath: WorktreePath,
        branch: String,
        setupCommand: String,
    ): WorktreeSetupRequest = WorktreeSetupRequest(
        repoPath = repoPath,
        worktreePath = worktreePath,
        cloneUrl = cloneUrl,
        branch = branch,
        setupShell = "/bin/sh",
        setupCommands = listOf(setupCommand),
    )

    suspend fun awaitFirstRepositoryEnsureBlocked() {
        withTimeout(1_000.milliseconds) { git.firstRepositoryEnsureStarted.await() }
        delay(100.milliseconds)
    }

    suspend fun releaseRepositoryEnsureAndAwaitSetups() {
        git.releaseFirstRepositoryEnsure.complete(Unit)
        withTimeout(1_000.milliseconds) { setupRunner.awaitStarted(firstWorktreePath) }
        withTimeout(1_000.milliseconds) { setupRunner.awaitStarted(secondWorktreePath) }
    }

    suspend fun completeSetups() {
        setupRunner.complete(firstWorktreePath)
        setupRunner.complete(secondWorktreePath)
    }

    suspend fun cleanup() {
        git.releaseFirstRepositoryEnsure.complete(Unit)
        completeSetups()
        setupScope.cancel()
    }
}

private fun assertWaitingForRepository(fixture: RepositorySerializationFixture) {
    assertEquals(
        WorktreeSetupStatus.WAITING_FOR_REPOSITORY,
        fixture.coordinator.statuses.value[fixture.firstWorktreePath],
    )
    assertEquals(
        WorktreeSetupStatus.WAITING_FOR_REPOSITORY,
        fixture.coordinator.statuses.value[fixture.secondWorktreePath],
    )
}

class WorktreeSetupCoordinatorTest {
    @Test
    fun setupScriptExpandsRootAndWorktreePlaceholders() {
        val repoPath = "/tmp/base"
        val worktreePath = "/tmp/worktree"
        val request = WorktreeSetupRequest(
            repoPath = repoPath,
            worktreePath = WorktreePath(worktreePath),
            setupShell = "/bin/sh",
            setupCommands = listOf(
                "printf '%s\\n' '${'$'}root-repo-dir|${'$'}worktree-dir' > setup-vars.txt",
            ),
        )

        val script = buildWorktreeSetupScript(request)

        assertTrue("printf '%s\\n' '$repoPath|$worktreePath' > setup-vars.txt" in script)
        assertFalse($$"$root-repo-dir" in script)
        assertFalse($$"$worktree-dir" in script)
    }

    @Test
    fun setupRunsPlaceholderExpandedCommandsInWorktreeDirectory() = runBlocking {
        val repoPath = createArchiveWorktreeTempDir()
        val worktreePath = createArchiveWorktreeTempDir()
        try {
            val coordinator = WorktreeSetupCoordinator(
                gitWorktreeApi = FakeGitWorktreeApi(),
                setupCommandRunner = ShellWorktreeSetupCommandRunner(),
                scope = this,
            )
            val request = WorktreeSetupRequest(
                repoPath = repoPath,
                worktreePath = WorktreePath(worktreePath),
                setupShell = "/bin/sh",
                setupCommands = listOf(
                    "printf '%s\\n' '${'$'}root-repo-dir|${'$'}worktree-dir' > setup-vars.txt",
                ),
            )

            coordinator.setup(request).await()

            val setupVars = SystemFileSystem.source(Path(worktreePath, "setup-vars.txt")).buffered().use {
                it.readString()
            }
            assertEquals("$repoPath|$worktreePath\n", setupVars)
        } finally {
            removeTempDir(repoPath)
            removeTempDir(worktreePath)
        }
    }

    @Test
    fun setupScriptEscapesDoubleQuotedPlaceholderValuesBeforeShellParsing() {
        val repoPath = "/tmp/root-${'$'}UNEXPANDED-`echo wrong`-\"quoted\"-\\slash"
        val worktreePath = "/tmp/worktree-${'$'}UNEXPANDED-`echo wrong`-\"quoted\"-\\slash"
        val request = WorktreeSetupRequest(
            repoPath = repoPath,
            worktreePath = WorktreePath(worktreePath),
            setupShell = "/bin/sh",
            setupCommands = listOf(
                "printf '%s\\n' \"${'$'}root-repo-dir|${'$'}worktree-dir\"",
            ),
        )

        val result = executeCommand(listOf("/bin/sh", "-c", buildWorktreeSetupScript(request)), workingDirectory = null)

        assertEquals(0, result.exitCode, result.stderr)
        assertEquals("$repoPath|$worktreePath\n", result.stdout)
    }

    @Test
    fun setupScriptEscapesSingleQuotedPlaceholderValuesBeforeShellParsing() {
        val repoPath = "/tmp/root-'quote'-${'$'}UNEXPANDED-`echo wrong`"
        val worktreePath = "/tmp/worktree-'quote'-${'$'}UNEXPANDED-`echo wrong`"
        val request = WorktreeSetupRequest(
            repoPath = repoPath,
            worktreePath = WorktreePath(worktreePath),
            setupShell = "/bin/sh",
            setupCommands = listOf(
                "printf '%s\\n' '${'$'}root-repo-dir|${'$'}worktree-dir'",
            ),
        )

        val result = executeCommand(listOf("/bin/sh", "-c", buildWorktreeSetupScript(request)), workingDirectory = null)

        assertEquals(0, result.exitCode, result.stderr)
        assertEquals("$repoPath|$worktreePath\n", result.stdout)
    }

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
    fun createBranchWorktreeRequestCreatesThenRunsSetupInNewWorktree() = runBlocking {
        val repoPath = "/repos/dev-lake-utils"
        val baseBranch = "feature/base-pr"
        val targetBranch = "feature/stacked-pr"
        val baseWorktreePath = buildWorktreePath(repoPath, baseBranch)
        val targetWorktreePath = buildWorktreePath(repoPath, targetBranch)
        val events = mutableListOf<String>()
        val git = FakeGitWorktreeApi().apply {
            expectedRepoPath = repoPath
            expectedBaseWorktreePath = baseWorktreePath.value
            expectedBaseBranch = baseBranch
            expectedTargetBranch = targetBranch
            createdBranchWorktreePath = targetWorktreePath
            onCreateBranchWorktree = { events += "create" }
        }
        val setupStarted = CompletableDeferred<WorktreeSetupRequest>()
        val setupComplete = CompletableDeferred<Unit>()
        val setupRunner = WorktreeSetupCommandRunner { request ->
            assertEquals(listOf("create"), events)
            events += "setup:${request.worktreePath.value}"
            setupStarted.complete(request)
            setupComplete.await()
            WorktreeSetupCommandResult(exitCode = 0, stdout = "setup complete", stderr = "")
        }
        val coordinator = WorktreeSetupCoordinator(
            gitWorktreeApi = git,
            setupCommandRunner = setupRunner,
            scope = this,
        )
        val request = WorktreeSetupRequest(
            repoPath = repoPath,
            worktreePath = targetWorktreePath,
            baseWorktreePath = baseWorktreePath.value,
            baseBranch = baseBranch,
            targetBranch = targetBranch,
            setupShell = "/bin/sh",
            setupCommands = listOf("touch setup-ran.txt"),
        )

        val handle = coordinator.setup(request)

        assertEquals(
            WorktreeSetupStatus.CREATING_OR_REUSING_WORKTREE,
            coordinator.statuses.value[targetWorktreePath],
        )
        val setupRequest = withTimeout(1_000.milliseconds) { setupStarted.await() }
        assertEquals(targetWorktreePath, setupRequest.worktreePath)
        assertEquals(
            WorktreeSetupStatus.RUNNING_SETUP_COMMANDS,
            coordinator.statuses.value[targetWorktreePath],
        )

        setupComplete.complete(Unit)
        val result = withTimeout(1_000.milliseconds) { handle.await() }

        assertEquals(targetWorktreePath, result.worktreePath)
        assertEquals("setup complete", result.setupCommandResult?.stdout)
        assertEquals(listOf("create", "setup:${targetWorktreePath.value}"), events)
        assertEquals(0, git.ensureRepositoryCalls)
        assertEquals(0, git.ensureWorktreeCalls)
        assertEquals(1, git.createBranchWorktreeCalls)
        assertTrue(coordinator.statuses.value.isEmpty())
    }

    @Test
    fun createBranchWorktreeFromCommitIshRequestCreatesThenRunsSetupInNewWorktree() = runBlocking {
        val repoPath = "/repos/dev-lake-utils"
        val baseCommitIsh = "abc123"
        val targetBranch = "feature/from-detached"
        val baseWorktreePath = WorktreePath("$repoPath-detached")
        val targetWorktreePath = buildWorktreePath(repoPath, targetBranch)
        val events = mutableListOf<String>()
        val git = FakeGitWorktreeApi().apply {
            expectedRepoPath = repoPath
            expectedBaseWorktreePath = baseWorktreePath.value
            expectedBaseCommitIsh = baseCommitIsh
            expectedTargetBranch = targetBranch
            createdBranchWorktreePath = targetWorktreePath
            onCreateBranchWorktreeFromCommitIsh = { events += "create" }
        }
        val setupStarted = CompletableDeferred<WorktreeSetupRequest>()
        val setupComplete = CompletableDeferred<Unit>()
        val setupRunner = WorktreeSetupCommandRunner { request ->
            assertEquals(listOf("create"), events)
            events += "setup:${request.worktreePath.value}"
            setupStarted.complete(request)
            setupComplete.await()
            WorktreeSetupCommandResult(exitCode = 0, stdout = "setup complete", stderr = "")
        }
        val coordinator = WorktreeSetupCoordinator(
            gitWorktreeApi = git,
            setupCommandRunner = setupRunner,
            scope = this,
        )
        val request = WorktreeSetupRequest(
            repoPath = repoPath,
            worktreePath = targetWorktreePath,
            baseWorktreePath = baseWorktreePath.value,
            baseCommitIsh = baseCommitIsh,
            targetBranch = targetBranch,
            setupShell = "/bin/sh",
            setupCommands = listOf("touch setup-ran.txt"),
        )

        val handle = coordinator.setup(request)

        val setupRequest = withTimeout(1_000.milliseconds) { setupStarted.await() }
        assertEquals(targetWorktreePath, setupRequest.worktreePath)
        assertEquals(
            WorktreeSetupStatus.RUNNING_SETUP_COMMANDS,
            coordinator.statuses.value[targetWorktreePath],
        )

        setupComplete.complete(Unit)
        val result = withTimeout(1_000.milliseconds) { handle.await() }

        assertEquals(targetWorktreePath, result.worktreePath)
        assertEquals("setup complete", result.setupCommandResult?.stdout)
        assertEquals(listOf("create", "setup:${targetWorktreePath.value}"), events)
        assertEquals(0, git.createBranchWorktreeCalls)
        assertEquals(1, git.createBranchWorktreeFromCommitIshCalls)
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
        val fixture = RepositorySerializationFixture()

        try {
            val first = fixture.coordinator.setup(fixture.firstRequest)
            val second = fixture.coordinator.setup(fixture.secondRequest)
            fixture.awaitFirstRepositoryEnsureBlocked()

            assertEquals(1, fixture.git.repositoryEnsureCalls())
            assertEquals(1, fixture.git.maxConcurrentRepositoryEnsures())
            assertWaitingForRepository(fixture)

            fixture.releaseRepositoryEnsureAndAwaitSetups()

            assertFalse(first.isCompleted)
            assertFalse(second.isCompleted)
            assertEquals(2, fixture.git.repositoryEnsureCalls())
            assertEquals(1, fixture.git.maxConcurrentRepositoryEnsures())
            assertEquals(2, fixture.git.worktreeEnsureCalls())
            assertEquals(2, fixture.setupRunner.calls())

            fixture.completeSetups()

            assertEquals(
                fixture.firstWorktreePath,
                withTimeout(1_000.milliseconds) { first.await() }.worktreePath,
            )
            assertEquals(
                fixture.secondWorktreePath,
                withTimeout(1_000.milliseconds) { second.await() }.worktreePath,
            )
            assertTrue(fixture.coordinator.statuses.value.isEmpty())
        } finally {
            fixture.cleanup()
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
