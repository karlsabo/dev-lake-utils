package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.DirectoryPicker
import com.github.karlsabo.devlake.enghub.EngHubConfig
import com.github.karlsabo.devlake.enghub.EngHubConfigWriter
import com.github.karlsabo.devlake.enghub.LocalRepositoryConfig
import com.github.karlsabo.devlake.enghub.state.NotificationUiState
import com.github.karlsabo.devlake.enghub.state.PullRequestUiState
import com.github.karlsabo.git.GitWorktreeApi
import com.github.karlsabo.git.RepositoryWorktrees
import com.github.karlsabo.git.Worktree
import com.github.karlsabo.git.WorktreePath
import com.github.karlsabo.git.WorktreeSetupCommandResult
import com.github.karlsabo.git.WorktreeSetupCommandRunner
import com.github.karlsabo.git.WorktreeSetupCoordinator
import com.github.karlsabo.git.WorktreeSetupRequest
import com.github.karlsabo.git.buildWorktreePath
import com.github.karlsabo.github.CheckRunSummary
import com.github.karlsabo.github.CiStatus
import com.github.karlsabo.github.GitHubApi
import com.github.karlsabo.github.GitHubNotificationService
import com.github.karlsabo.github.Issue
import com.github.karlsabo.github.Notification
import com.github.karlsabo.github.PullRequest
import com.github.karlsabo.github.ReviewSummary
import com.github.karlsabo.github.User
import com.github.karlsabo.notifications.IgnoredNotificationThread
import com.github.karlsabo.notifications.NotificationIgnoreStore
import com.github.karlsabo.notifications.SaveIgnoredNotificationThreadRequest
import com.github.karlsabo.system.DesktopLauncher
import com.github.karlsabo.system.OsFamily
import com.github.karlsabo.system.osFamily
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Instant

const val DEV_LAKE_ROOT = "/repos/dev-lake-utils"
const val DEV_LAKE_SELECTED_WORKTREE = "/repos/dev-lake-utils-feature-worktree-panel"
const val DOCS_ROOT = "/repos/docs"
const val DOCS_SELECTED_WORKTREE = "/repos/docs-feature-notes"
const val EXAMPLE_WEB_ROOT = "/workspace/example-web"
const val NEW_LOCAL_REPO_ROOT = "/workspace/new-local-repo"

fun devLakeAndDocsRepositoryWorktreesBySelectedPath(): Map<String, RepositoryWorktrees> = mapOf(
    DEV_LAKE_SELECTED_WORKTREE to RepositoryWorktrees(
        rootPath = DEV_LAKE_ROOT,
        selectedWorktreePath = DEV_LAKE_SELECTED_WORKTREE,
        worktrees = listOf(
            Worktree(path = DEV_LAKE_ROOT, branch = "main", commitHash = "abc123"),
            Worktree(path = DEV_LAKE_SELECTED_WORKTREE, branch = "feature/worktree-panel", commitHash = "def456"),
        ),
    ),
    DOCS_SELECTED_WORKTREE to RepositoryWorktrees(
        rootPath = DOCS_ROOT,
        selectedWorktreePath = DOCS_SELECTED_WORKTREE,
        worktrees = listOf(
            Worktree(path = DOCS_ROOT, branch = "main", commitHash = "123abc"),
            Worktree(path = DOCS_SELECTED_WORKTREE, branch = "feature/notes", commitHash = "456def"),
        ),
    ),
)

fun pollingWorktrees(listCountsByRepo: MutableMap<String, Int>): (String) -> List<Worktree> = { repoPath ->
    val callCount = listCountsByRepo.getOrElse(repoPath) { 0 } + 1
    listCountsByRepo[repoPath] = callCount
    when (repoPath) {
        DEV_LAKE_ROOT -> devLakePollWorktrees(callCount)
        DOCS_ROOT -> listOf(Worktree(path = DOCS_ROOT, branch = "docs-main", commitHash = "123abc"))
        else -> error("Unexpected repo path $repoPath")
    }
}

fun devLakePollWorktrees(callCount: Int): List<Worktree> = if (callCount == 1) {
    listOf(Worktree(path = DEV_LAKE_ROOT, branch = "main", commitHash = "abc123"))
} else {
    listOf(
        Worktree(path = DEV_LAKE_ROOT, branch = "main", commitHash = "abc123"),
        Worktree(
            path = DEV_LAKE_SELECTED_WORKTREE,
            branch = "feature/worktree-panel",
            commitHash = "def456",
            isDirty = true,
        ),
    )
}

class RecordingGitHubApi(
    private val pullRequestsByUrl: Map<String, PullRequest>,
) : GitHubApi {
    val pullRequestByUrlCalls = mutableListOf<String>()
    var openPullRequestCalls = 0
        private set
    var notificationListCalls = 0
        private set

    override suspend fun getPullRequestByUrl(url: String): PullRequest {
        pullRequestByUrlCalls += url
        return pullRequestsByUrl.getValue(url)
    }

    override suspend fun getOpenPullRequestsByAuthor(organizationIds: List<String>, author: String): List<Issue> {
        openPullRequestCalls += 1
        error("Unexpected call")
    }

    override suspend fun getCheckRunsForRef(
        owner: String,
        repo: String,
        ref: String,
    ): CheckRunSummary = CheckRunSummary(total = 3, passed = 3, failed = 0, inProgress = 0, status = CiStatus.PASSED)

    override suspend fun getReviewSummary(
        owner: String,
        repo: String,
        prNumber: Int,
    ): ReviewSummary = ReviewSummary(approvedCount = 0, requestedCount = 0, reviews = emptyList())

    override suspend fun getMergedPullRequestCount(
        gitHubUserId: String,
        organizationIds: List<String>,
        startDate: Instant,
        endDate: Instant,
    ): UInt {
        error("Unexpected call")
    }

    override suspend fun getPullRequestReviewCount(
        gitHubUserId: String,
        organizationIds: List<String>,
        startDate: Instant,
        endDate: Instant,
    ): UInt {
        error("Unexpected call")
    }

    override suspend fun getMergedPullRequests(
        gitHubUserId: String,
        organizationIds: List<String>,
        startDate: Instant,
        endDate: Instant,
    ): List<Issue> {
        error("Unexpected call")
    }

    override suspend fun searchPullRequestsByText(
        searchText: String,
        organizationIds: List<String>,
        startDateInclusive: Instant,
        endDateInclusive: Instant,
    ): List<Issue> {
        error("Unexpected call")
    }

    override suspend fun listNotifications(): List<Notification> {
        notificationListCalls += 1
        error("Unexpected call")
    }

    override suspend fun approvePullRequestByUrl(url: String, body: String?) {
        error("Unexpected call")
    }

    override suspend fun markNotificationAsDone(threadId: String) {
        error("Unexpected call")
    }

    override suspend fun unsubscribeFromNotification(threadId: String) {
        error("Unexpected call")
    }

    override suspend fun hasAnyApprovedReview(url: String): Boolean {
        error("Unexpected call")
    }
}

fun testIssue(issueApiUrl: String, pullApiUrl: String?): Issue {
    val now = Clock.System.now()
    return Issue(
        url = issueApiUrl,
        repositoryUrl = "https://api.github.com/repos/test-org/test-repo",
        id = 25843L,
        number = 25843,
        state = "open",
        title = "Use the PR API URL",
        user = User(
            login = "test-user",
            id = 1L,
            avatarUrl = null,
            url = "https://api.github.com/users/test-user",
            htmlUrl = "https://github.com/test-user",
        ),
        body = null,
        htmlUrl = "https://github.com/test-org/test-repo/pull/25843",
        labels = emptyList(),
        draft = false,
        createdAt = now,
        updatedAt = now,
        closedAt = null,
        pullRequest = pullApiUrl?.let { PullRequest(url = it) },
        comments = 0,
    )
}

fun sharedProgressPullRequest(
    repoFullName: String,
    branch: String,
): PullRequestUiState = PullRequestUiState(
    number = 1,
    title = "Shared progress",
    htmlUrl = "https://github.com/$repoFullName/pull/1",
    repositoryFullName = repoFullName,
    owner = repoFullName.substringBefore('/'),
    repo = repoFullName.substringAfter('/'),
    isDraft = false,
    ciStatus = CiStatus.PENDING,
    ciSummaryText = "0/0 passed",
    approvedCount = 0,
    requestedCount = 0,
    reviewSummaryText = "0/0 approved",
    headRef = branch,
    apiUrl = "https://api.github.com/repos/$repoFullName/pulls/1",
)

fun sharedProgressNotification(
    repoFullName: String,
    branch: String,
): NotificationUiState = NotificationUiState(
    notificationThreadId = "thread-1",
    title = "Shared progress",
    reason = "review_requested",
    updatedAtEpochMs = 2_026_052_910_000,
    repositoryFullName = repoFullName,
    subjectType = "PullRequest",
    htmlUrl = "https://github.com/$repoFullName/pull/1",
    apiUrl = "https://api.github.com/repos/$repoFullName/pulls/1",
    isPullRequest = true,
    pullRequestNumber = 1,
    unread = true,
    headRef = branch,
)

data class LocalRepositoryViewModelTestConfig(
    val worktreePollIntervalMs: Long = 120_000,
    val repositoriesBaseDir: String = "",
    val setupShell: String = "/bin/zsh",
)

fun localRepositoryConfigs(vararg paths: String) = paths.map { LocalRepositoryConfig(path = it) }

data class LocalRepositoryViewModelServices(
    val gitHubApi: RecordingGitHubApi = RecordingGitHubApi(emptyMap()),
    val worktreeSetupCoordinator: WorktreeSetupCoordinator? = null,
)

fun createLocalRepositoryViewModel(
    gitWorktreeApi: RecordingGitWorktreeApi,
    configWriter: RecordingEngHubConfigWriter,
    localRepositoryConfigs: List<LocalRepositoryConfig> = emptyList(),
    services: LocalRepositoryViewModelServices = LocalRepositoryViewModelServices(),
    testConfig: LocalRepositoryViewModelTestConfig = LocalRepositoryViewModelTestConfig(),
): EngHubViewModel = EngHubViewModel(
    gitHubServices = EngHubGitHubServices(
        api = services.gitHubApi,
        notificationService = GitHubNotificationService(services.gitHubApi),
    ),
    worktreeServices = EngHubWorktreeServices(
        gitWorktreeApi = gitWorktreeApi,
        worktreeSetupCoordinator = services.worktreeSetupCoordinator
            ?: WorktreeSetupCoordinator(gitWorktreeApi = gitWorktreeApi),
        directoryPicker = LocalRepositoryNoOpDirectoryPicker(),
        configWriter = configWriter,
    ),
    desktopServices = EngHubDesktopServices(LocalRepositoryNoOpDesktopLauncher()),
    config = EngHubConfig(
        pollIntervalMs = 60_000,
        worktreePollIntervalMs = testConfig.worktreePollIntervalMs,
        repositoriesBaseDir = testConfig.repositoriesBaseDir,
        localRepositories = localRepositoryConfigs,
        setupShell = testConfig.setupShell,
    ),
    notificationIgnoreStore = NoOpNotificationIgnoreStore(),
)

fun createWorktreeSetupViewModel(
    gitWorktreeApi: RecordingGitWorktreeApi,
    setupRunner: WorktreeSetupCommandRunner,
    setupCommands: List<String>,
): EngHubViewModel = createLocalRepositoryViewModel(
    gitWorktreeApi = gitWorktreeApi,
    configWriter = RecordingEngHubConfigWriter(),
    localRepositoryConfigs = listOf(
        LocalRepositoryConfig(path = DEV_LAKE_ROOT, setupCommands = setupCommands),
    ),
    services = LocalRepositoryViewModelServices(
        worktreeSetupCoordinator = WorktreeSetupCoordinator(
            gitWorktreeApi = gitWorktreeApi,
            setupCommandRunner = setupRunner,
        ),
    ),
)

class BlockingCoordinatorSetupRunner : WorktreeSetupCommandRunner {
    private val signals = WorktreeSetupRunnerSignals()
    private val requests = MutableStateFlow<Map<WorktreePath, WorktreeSetupRequest>>(emptyMap())

    override suspend fun runSetup(request: WorktreeSetupRequest): WorktreeSetupCommandResult {
        requests.update { it + (request.worktreePath to request) }
        signals.recordStarted(request.worktreePath)
        signals.awaitUnblocked(request.worktreePath)
        return WorktreeSetupCommandResult(exitCode = 0, stdout = "setup complete", stderr = "")
    }

    fun calls(): Int = signals.calls()

    fun requestFor(worktreePath: WorktreePath): WorktreeSetupRequest? = requests.value[worktreePath]

    suspend fun awaitStarted(worktreePath: WorktreePath) {
        signals.awaitStarted(worktreePath)
    }

    fun complete(worktreePath: WorktreePath) {
        signals.unblock(worktreePath)
    }
}

class FailingBlockingSetupRunner(
    private val failureMessage: (WorktreePath) -> String = { worktreePath -> "setup failed for ${worktreePath.value}" },
) : WorktreeSetupCommandRunner {
    private val signals = WorktreeSetupRunnerSignals()

    override suspend fun runSetup(request: WorktreeSetupRequest): WorktreeSetupCommandResult {
        signals.recordStarted(request.worktreePath)
        signals.awaitUnblocked(request.worktreePath)
        throw IllegalStateException(failureMessage(request.worktreePath))
    }

    fun calls(): Int = signals.calls()

    suspend fun awaitStarted(worktreePath: WorktreePath) {
        signals.awaitStarted(worktreePath)
    }

    fun fail(worktreePath: WorktreePath) {
        signals.unblock(worktreePath)
    }
}

class WorktreeSetupRunnerSignals {
    private val callCount = MutableStateFlow(0)
    private val startedSignals = MutableStateFlow<Map<WorktreePath, CompletableDeferred<Unit>>>(emptyMap())
    private val unblockSignals = MutableStateFlow<Map<WorktreePath, CompletableDeferred<Unit>>>(emptyMap())

    fun recordStarted(worktreePath: WorktreePath) {
        callCount.update { it + 1 }
        signalFor(startedSignals, worktreePath).complete(Unit)
    }

    fun calls(): Int = callCount.value

    suspend fun awaitStarted(worktreePath: WorktreePath) {
        signalFor(startedSignals, worktreePath).await()
    }

    suspend fun awaitUnblocked(worktreePath: WorktreePath) {
        signalFor(unblockSignals, worktreePath).await()
    }

    fun unblock(worktreePath: WorktreePath) {
        signalFor(unblockSignals, worktreePath).complete(Unit)
    }

    private fun signalFor(
        signals: MutableStateFlow<Map<WorktreePath, CompletableDeferred<Unit>>>,
        worktreePath: WorktreePath,
    ): CompletableDeferred<Unit> {
        while (true) {
            val currentSignals = signals.value
            currentSignals[worktreePath]?.let { return it }

            val signal = CompletableDeferred<Unit>()
            if (signals.compareAndSet(currentSignals, currentSignals + (worktreePath to signal))) return signal
        }
    }
}

data class CreateBranchWorktreeCall(
    val repoPath: String,
    val baseWorktreePath: String,
    val baseBranch: String,
    val targetBranch: String,
    val allowUnrelatedExistingBranch: Boolean = false,
)

data class CreateBranchWorktreeFromCommitIshCall(
    val repoPath: String,
    val baseWorktreePath: String,
    val baseCommitIsh: String,
    val targetBranch: String,
)

data class BranchNeedsRebaseCall(
    val repoPath: String,
    val parentBranch: String,
    val childBranch: String,
)

data class RebaseWorktreeOntoParentCall(
    val worktreePath: String,
    val parentBranch: String,
)

data class AbortRebaseCall(
    val worktreePath: String,
)

data class RecordingGitWorktreeApiResponses(
    val worktreesByRepoPath: Map<String, List<Worktree>>? = null,
    val worktreesForRepoPath: ((String) -> List<Worktree>)? = null,
    val defaultBranchRefsByRepoPath: Map<String, String?> = emptyMap(),
    val parentBranchesByRepoPath: Map<String, Map<String, String>> = emptyMap(),
    val branchNeedsRebaseByCall: Map<BranchNeedsRebaseCall, Boolean> = emptyMap(),
    val inferDefaultBranchRefFailure: RuntimeException? = null,
    val listWorktreesFailure: RuntimeException? = null,
    val branchNeedsRebaseFailure: RuntimeException? = null,
    val archiveWorktreeFailure: RuntimeException? = null,
    val rebaseWorktreeFailure: RuntimeException? = null,
    val abortRebaseFailure: RuntimeException? = null,
)

data class RecordingGitWorktreeApiCallbacks(
    val onListWorktrees: (String) -> Unit = {},
    val onArchiveWorktree: (String, String, Boolean) -> Unit = { _, _, _ -> },
    val onCreateBranchWorktree: (CreateBranchWorktreeCall) -> String = {
        error("Unexpected call")
    },
    val onCreateBranchWorktreeFromCommitIsh: (CreateBranchWorktreeFromCommitIshCall) -> String = {
        error("Unexpected call")
    },
    val onRebaseWorktreeOntoParent: (RebaseWorktreeOntoParentCall) -> Unit = {},
    val onAbortRebase: (AbortRebaseCall) -> Unit = {},
)

class RecordingGitWorktreeApi(
    private val repositoryWorktreesBySelectedPath: Map<String, RepositoryWorktrees> = emptyMap(),
    private val responses: RecordingGitWorktreeApiResponses = RecordingGitWorktreeApiResponses(),
    private val callbacks: RecordingGitWorktreeApiCallbacks = RecordingGitWorktreeApiCallbacks(),
) : GitWorktreeApi {
    constructor(
        worktreesForRepoPath: (String) -> List<Worktree>,
    ) : this(
        responses = RecordingGitWorktreeApiResponses(worktreesForRepoPath = worktreesForRepoPath),
    )

    constructor(
        worktreesByRepoPath: Map<String, List<Worktree>>,
        onListWorktrees: (String) -> Unit = {},
    ) : this(
        responses = RecordingGitWorktreeApiResponses(worktreesByRepoPath = worktreesByRepoPath),
        callbacks = RecordingGitWorktreeApiCallbacks(onListWorktrees = onListWorktrees),
    )

    constructor(
        listWorktreesFailure: RuntimeException,
    ) : this(
        responses = RecordingGitWorktreeApiResponses(listWorktreesFailure = listWorktreesFailure),
    )

    constructor(repositoryWorktrees: RepositoryWorktrees) : this(
        repositoryWorktreesBySelectedPath = mapOf(repositoryWorktrees.selectedWorktreePath to repositoryWorktrees),
    )

    val resolvedPaths = mutableListOf<String>()
    private val recordedListWorktreeRepoPaths = MutableStateFlow<List<String>>(emptyList())
    val listWorktreeRepoPaths: List<String>
        get() = recordedListWorktreeRepoPaths.value
    val ensureRepositoryCalls = mutableListOf<Pair<String, String>>()
    val ensureWorktreeCalls = mutableListOf<Pair<String, String>>()
    val createBranchWorktreeCalls = mutableListOf<CreateBranchWorktreeCall>()
    val createBranchWorktreeFromCommitIshCalls = mutableListOf<CreateBranchWorktreeFromCommitIshCall>()
    val inferDefaultBranchRefCalls = mutableListOf<String>()
    val branchNeedsRebaseCalls = mutableListOf<BranchNeedsRebaseCall>()
    val rebaseWorktreeOntoParentCalls = mutableListOf<RebaseWorktreeOntoParentCall>()
    val abortRebaseCalls = mutableListOf<AbortRebaseCall>()
    val archiveWorktreeCalls = mutableListOf<Pair<String, String>>()
    val archiveWorktreeForceValues = mutableListOf<Boolean>()
    private val worktreesByRepoPath = responses.worktreesByRepoPath
        ?: repositoryWorktreesBySelectedPath.values.associate { it.rootPath to it.worktrees }

    override fun ensureRepository(repoPath: String, cloneUrl: String) {
        ensureRepositoryCalls += repoPath to cloneUrl
    }

    override fun ensureWorktree(repoPath: String, branch: String): String {
        ensureWorktreeCalls += repoPath to branch
        val worktreePath = buildWorktreePath(repoPath, branch).value
        SystemFileSystem.createDirectories(Path(worktreePath))
        return worktreePath
    }

    override fun createBranchWorktree(
        repoPath: String,
        baseWorktreePath: String,
        baseBranch: String,
        targetBranch: String,
        allowUnrelatedExistingBranch: Boolean,
    ): String {
        val call = CreateBranchWorktreeCall(
            repoPath = repoPath,
            baseWorktreePath = baseWorktreePath,
            baseBranch = baseBranch,
            targetBranch = targetBranch,
            allowUnrelatedExistingBranch = allowUnrelatedExistingBranch,
        )
        createBranchWorktreeCalls += call
        return callbacks.onCreateBranchWorktree(call)
    }

    override fun createBranchWorktreeFromCommitIsh(
        repoPath: String,
        baseWorktreePath: String,
        baseCommitIsh: String,
        targetBranch: String,
    ): String {
        val call = CreateBranchWorktreeFromCommitIshCall(
            repoPath = repoPath,
            baseWorktreePath = baseWorktreePath,
            baseCommitIsh = baseCommitIsh,
            targetBranch = targetBranch,
        )
        createBranchWorktreeFromCommitIshCalls += call
        return callbacks.onCreateBranchWorktreeFromCommitIsh(call)
    }

    override fun worktreeExists(repoPath: String, branch: String): Boolean = false
    override fun isBranchAncestor(
        repoPath: String,
        baseBranch: String,
        childBranch: String,
    ): Boolean {
        TODO("Not yet implemented")
    }

    override fun resolveRepositoryRoot(selectedPath: String): RepositoryWorktrees {
        resolvedPaths += selectedPath
        return repositoryWorktreesBySelectedPath.getValue(selectedPath)
    }

    override fun listWorktrees(repoPath: String): List<Worktree> {
        recordedListWorktreeRepoPaths.update { it + repoPath }
        callbacks.onListWorktrees(repoPath)
        responses.listWorktreesFailure?.let { throw it }
        return responses.worktreesForRepoPath?.invoke(repoPath) ?: worktreesByRepoPath.getValue(repoPath)
    }

    override fun inferDefaultBranchRef(repoPath: String): String? {
        inferDefaultBranchRefCalls += repoPath
        responses.inferDefaultBranchRefFailure?.let { throw it }
        return responses.defaultBranchRefsByRepoPath[repoPath]
    }

    override fun inferWorktreeParentBranches(repoPath: String) = responses.parentBranchesByRepoPath[repoPath].orEmpty()

    override fun branchNeedsRebase(
        repoPath: String,
        parentBranch: String,
        childBranch: String,
    ): Boolean {
        val call = BranchNeedsRebaseCall(repoPath, parentBranch, childBranch)
        branchNeedsRebaseCalls += call
        responses.branchNeedsRebaseFailure?.let { throw it }
        return responses.branchNeedsRebaseByCall[call] == true
    }

    override fun removeWorktree(worktreePath: String, force: Boolean) = Unit

    override fun rebaseWorktreeOntoParent(
        worktreePath: String,
        parentBranch: String,
    ) {
        val call = RebaseWorktreeOntoParentCall(worktreePath, parentBranch)
        rebaseWorktreeOntoParentCalls += call
        responses.rebaseWorktreeFailure?.let { throw it }
        callbacks.onRebaseWorktreeOntoParent(call)
    }

    override fun abortRebase(worktreePath: String) {
        val call = AbortRebaseCall(worktreePath)
        abortRebaseCalls += call
        responses.abortRebaseFailure?.let { throw it }
        callbacks.onAbortRebase(call)
    }

    override fun archiveWorktree(
        repoPath: String,
        worktreePath: String,
        force: Boolean,
    ) {
        archiveWorktreeCalls += repoPath to worktreePath
        archiveWorktreeForceValues += force
        responses.archiveWorktreeFailure?.let { throw it }
        callbacks.onArchiveWorktree(repoPath, worktreePath, force)
    }
}

fun nativeSetupShell(): String = if (osFamily() == OsFamily.WINDOWS) "powershell.exe" else "/bin/bash"

fun writeWorkingDirectorySetupCommand(fileName: String): String = if (osFamily() == OsFamily.WINDOWS) {
    "[IO.File]::WriteAllText('$fileName', (Get-Location).Path)"
} else {
    "pwd > '$fileName'"
}

fun waitForSetupFileCommand(fileName: String): String = if (osFamily() == OsFamily.WINDOWS) {
    "while (-not (Test-Path -LiteralPath '$fileName')) { Start-Sleep -Milliseconds 10 }"
} else {
    "while [ ! -f '$fileName' ]; do sleep 0.01; done"
}

fun writeSetupFileCommand(fileName: String, contents: String): String = if (osFamily() == OsFamily.WINDOWS) {
    "[IO.File]::WriteAllText('$fileName', '$contents')"
} else {
    "printf '%s' '$contents' > '$fileName'"
}

fun appendSetupFileCommand(path: Path, contents: String): String = if (osFamily() == OsFamily.WINDOWS) {
    "[IO.File]::AppendAllText('$path', '$contents')"
} else {
    "printf '%s' '$contents' >> '$path'"
}

fun failingSetupCommands(message: String, exitCode: Int): List<String> = if (osFamily() == OsFamily.WINDOWS) {
    listOf(
        "[Console]::Error.WriteLine('$message')",
        "& powershell.exe -NoProfile -Command \"exit $exitCode\"",
    )
} else {
    listOf("echo '$message' >&2", "exit $exitCode")
}

fun createTempDir(prefix: String): String {
    val dirName = "$prefix-${Random.nextLong().toULong().toString(16)}"
    val path = Path(SystemTemporaryDirectory, dirName)
    SystemFileSystem.createDirectories(path)
    return path.toString()
}

suspend fun removeTempDir(path: String) {
    val root = Path(path)
    if (!SystemFileSystem.exists(root)) return

    var lastFailure: Throwable? = null
    repeat(10) { attempt ->
        val deletion = runCatching { deleteRecursively(root) }
        if (deletion.isSuccess || !SystemFileSystem.exists(root)) return
        lastFailure = deletion.exceptionOrNull()
        if (attempt < 9) delay(10)
    }
    throw lastFailure ?: IllegalStateException("Failed to delete $path")
}

fun deleteRecursively(path: Path) {
    if (SystemFileSystem.metadataOrNull(path)?.isDirectory == true) {
        SystemFileSystem.list(path).forEach { deleteRecursively(it) }
    }
    SystemFileSystem.delete(path, mustExist = false)
}

fun readText(path: Path): String = SystemFileSystem.source(path).buffered().use { it.readString() }

fun writeText(path: Path, text: String) {
    SystemFileSystem.sink(path).buffered().use { it.writeString(text) }
}

class RecordingEngHubConfigWriter : EngHubConfigWriter {
    val savedConfigs = MutableStateFlow<List<EngHubConfig>>(emptyList())

    override fun save(config: EngHubConfig) {
        savedConfigs.value += config
    }
}

class LocalRepositoryNoOpDirectoryPicker : DirectoryPicker {
    override suspend fun pickDirectory(title: String): String? = null
}

class LocalRepositoryNoOpDesktopLauncher : DesktopLauncher {
    override fun openUrl(url: String) = Unit

    override fun openInIdea(projectPath: String) = Unit
}

class NoOpNotificationIgnoreStore : NotificationIgnoreStore {
    override fun listIgnoredThreadIds(): Set<String> = emptySet()

    override fun listIgnoredThreads(): List<IgnoredNotificationThread> = emptyList()

    override fun saveIgnoredThread(request: SaveIgnoredNotificationThreadRequest) = Unit
}
