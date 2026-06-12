package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.DirectoryPicker
import com.github.karlsabo.devlake.enghub.EngHubConfig
import com.github.karlsabo.devlake.enghub.EngHubConfigWriter
import com.github.karlsabo.devlake.enghub.state.NotificationUiState
import com.github.karlsabo.git.GitWorktreeApi
import com.github.karlsabo.git.RepositoryWorktrees
import com.github.karlsabo.git.WorktreeSetupCoordinator
import com.github.karlsabo.git.buildWorktreePath
import com.github.karlsabo.github.CheckRunSummary
import com.github.karlsabo.github.CiStatus
import com.github.karlsabo.github.GitHubApi
import com.github.karlsabo.github.GitHubNotificationService
import com.github.karlsabo.github.Issue
import com.github.karlsabo.github.Notification
import com.github.karlsabo.github.NotificationRepository
import com.github.karlsabo.github.NotificationSubject
import com.github.karlsabo.github.PullRequest
import com.github.karlsabo.github.ReviewStateValue
import com.github.karlsabo.github.ReviewSummary
import com.github.karlsabo.notifications.IgnoredNotificationThread
import com.github.karlsabo.notifications.NotificationIgnoreReason
import com.github.karlsabo.notifications.NotificationIgnoreStore
import com.github.karlsabo.notifications.SaveIgnoredNotificationThreadRequest
import com.github.karlsabo.notifications.toIgnoredNotificationThread
import com.github.karlsabo.system.DesktopLauncher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.time.Duration.Companion.milliseconds

fun createViewModel(
    api: NotificationPersistenceGitHubApi,
    store: RecordingNotificationIgnoreStore,
    pollIntervalMs: Long = 60_000,
): EngHubViewModel {
    val gitWorktreeApi = NoOpGitWorktreeApi()
    return EngHubViewModel(
        gitHubServices = EngHubGitHubServices(
            api = api,
            notificationService = GitHubNotificationService(api),
        ),
        worktreeServices = EngHubWorktreeServices(
            gitWorktreeApi = gitWorktreeApi,
            worktreeSetupCoordinator = WorktreeSetupCoordinator(gitWorktreeApi = gitWorktreeApi),
            directoryPicker = NoOpDirectoryPicker(),
            configWriter = NoOpEngHubConfigWriter(),
        ),
        desktopServices = EngHubDesktopServices(NoOpDesktopLauncher()),
        config = EngHubConfig(
            organizationIds = listOf("test-org"),
            repositoriesBaseDir = "/tmp/repos",
            gitHubAuthor = "test-user",
            pollIntervalMs = pollIntervalMs,
        ),
        notificationIgnoreStore = store,
    )
}

suspend fun <T> MutableStateFlow<List<T>>.awaitValue(): List<T> = withTimeout(2_000.milliseconds) {
    first { it.isNotEmpty() }
}

suspend fun <T> MutableStateFlow<List<T>>.awaitSize(size: Int): List<T> = withTimeout(2_000.milliseconds) {
    first { it.size >= size }
}

class NoOpGitWorktreeApi : GitWorktreeApi {
    override fun ensureRepository(repoPath: String, cloneUrl: String) = Unit

    override fun ensureWorktree(repoPath: String, branch: String): String = buildWorktreePath(repoPath, branch).value

    override fun createBranchWorktree(
        repoPath: String,
        baseWorktreePath: String,
        baseBranch: String,
        targetBranch: String,
        allowUnrelatedExistingBranch: Boolean,
    ): String {
        error("Unexpected call")
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
        error("Unexpected call")
    }

    override fun listWorktrees(repoPath: String) = emptyList<com.github.karlsabo.git.Worktree>()

    override fun inferDefaultBranchRef(repoPath: String): String? = null

    override fun inferWorktreeParentBranches(repoPath: String): Map<String, String> = emptyMap()

    override fun removeWorktree(worktreePath: String, force: Boolean) = Unit

    override fun archiveWorktree(
        repoPath: String,
        worktreePath: String,
        force: Boolean,
    ) = Unit
}

class NoOpDirectoryPicker : DirectoryPicker {
    override suspend fun pickDirectory(title: String): String? = null
}

class NoOpEngHubConfigWriter : EngHubConfigWriter {
    override fun save(config: EngHubConfig) = Unit
}

class NoOpDesktopLauncher : DesktopLauncher {
    override fun openUrl(url: String) = Unit

    override fun openInIdea(projectPath: String) = Unit
}

data class SavedThread(
    val threadId: String,
    val repositoryFullName: String,
    val subjectType: String,
    val reason: NotificationIgnoreReason,
    val ignoredAtEpochMs: Long? = null,
    val notificationUpdatedAtEpochMs: Long? = null,
) {
    fun withoutTimestamp(): SavedThread = copy(ignoredAtEpochMs = null, notificationUpdatedAtEpochMs = null)
}

fun SaveIgnoredNotificationThreadRequest.toSavedThread(): SavedThread = SavedThread(
    threadId = threadId,
    repositoryFullName = repositoryFullName,
    subjectType = subjectType,
    reason = reason,
    ignoredAtEpochMs = ignoredAtEpochMs,
    notificationUpdatedAtEpochMs = notificationUpdatedAtEpochMs,
)

fun ignoredThread(
    threadId: String,
    reason: NotificationIgnoreReason,
    notificationUpdatedAtEpochMs: Long? = null,
): IgnoredNotificationThread = IgnoredNotificationThread(
    threadId = threadId,
    repositoryFullName = "test-org/test-repo",
    subjectType = "PullRequest",
    reason = reason,
    ignoredAtEpochMs = Clock.System.now().toEpochMilliseconds(),
    notificationUpdatedAtEpochMs = notificationUpdatedAtEpochMs,
)

class RecordingNotificationIgnoreStore(
    initialThreadIds: Set<String> = emptySet(),
    initialIgnoredThreads: List<IgnoredNotificationThread> = emptyList(),
    private val saveFailure: Exception? = null,
    saveFailuresBeforeSuccess: List<Exception> = emptyList(),
) : NotificationIgnoreStore {
    private var storedIgnoredThreads = (
        initialIgnoredThreads + initialThreadIds.map { threadId ->
            ignoredThread(threadId = threadId, reason = NotificationIgnoreReason.UNSUBSCRIBED)
        }
        ).associateBy { it.threadId }.toMutableMap()
    private val queuedSaveFailures = saveFailuresBeforeSuccess.toMutableList()
    val savedThreads = MutableStateFlow<List<SavedThread>>(emptyList())

    override fun listIgnoredThreadIds(): Set<String> = storedIgnoredThreads.keys

    override fun listIgnoredThreads(): List<IgnoredNotificationThread> = storedIgnoredThreads.values.toList()

    override fun saveIgnoredThread(request: SaveIgnoredNotificationThreadRequest) {
        saveFailure?.let { throw it }
        if (queuedSaveFailures.isNotEmpty()) throw queuedSaveFailures.removeAt(0)
        storedIgnoredThreads[request.threadId] = request.toIgnoredNotificationThread()
        savedThreads.value += request.toSavedThread()
    }
}

data class SubmittedReview(
    val prApiUrl: String,
    val event: ReviewStateValue,
    val reviewComment: String?,
)

class NotificationPersistenceGitHubApi(
    private val notifications: List<Notification> = emptyList(),
    private val pullRequestsByUrl: Map<String, PullRequest> = emptyMap(),
    private val pullRequestFailureUrls: Set<String> = emptySet(),
    private val approvedReviewUrls: Set<String> = emptySet(),
    private val unsubscribeFailure: Exception? = null,
    private val markDoneFailure: Exception? = null,
) : GitHubApi {
    val pullRequestByUrlCalls = mutableListOf<String>()
    val approvedPullRequestUrls = MutableStateFlow<List<String>>(emptyList())
    val submittedReviews = MutableStateFlow<List<SubmittedReview>>(emptyList())
    val unsubscribedThreadIds = MutableStateFlow<List<String>>(emptyList())
    val markedDoneThreadIds = MutableStateFlow<List<String>>(emptyList())

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

    override suspend fun listNotifications(): List<Notification> = notifications

    override suspend fun getPullRequestByUrl(url: String): PullRequest {
        pullRequestByUrlCalls += url
        if (url in pullRequestFailureUrls) error("failed to load pull request: $url")
        return pullRequestsByUrl[url] ?: PullRequest(url = url)
    }

    override suspend fun approvePullRequestByUrl(url: String, body: String?) {
        approvedPullRequestUrls.value += url
    }

    override suspend fun markNotificationAsDone(threadId: String) {
        markedDoneThreadIds.value += threadId
        markDoneFailure?.let { throw it }
    }

    override suspend fun unsubscribeFromNotification(threadId: String) {
        unsubscribedThreadIds.value += threadId
        unsubscribeFailure?.let { throw it }
    }

    override suspend fun hasAnyApprovedReview(url: String): Boolean = url in approvedReviewUrls

    override suspend fun getOpenPullRequestsByAuthor(
        organizationIds: List<String>,
        author: String,
    ): List<Issue> = emptyList()

    override suspend fun getCheckRunsForRef(
        owner: String,
        repo: String,
        ref: String,
    ): CheckRunSummary = CheckRunSummary(total = 0, passed = 0, failed = 0, inProgress = 0, status = CiStatus.PENDING)

    override suspend fun getReviewSummary(
        owner: String,
        repo: String,
        prNumber: Int,
    ): ReviewSummary = ReviewSummary(approvedCount = 0, requestedCount = 0, reviews = emptyList())

    override suspend fun submitReview(
        prApiUrl: String,
        event: ReviewStateValue,
        reviewComment: String?,
    ) {
        submittedReviews.value += SubmittedReview(prApiUrl, event, reviewComment)
    }
}

fun testNotification(
    id: String,
    subjectType: String,
    subjectUrl: String?,
    title: String = "Notification $id",
    updatedAt: Instant = Clock.System.now(),
): Notification = Notification(
    id = id,
    unread = true,
    reason = "review_requested",
    updatedAt = updatedAt,
    lastReadAt = null,
    subject = NotificationSubject(
        title = title,
        url = subjectUrl,
        latestCommentUrl = null,
        type = subjectType,
    ),
    repository = NotificationRepository(
        id = 1L,
        name = "test-repo",
        fullName = "test-org/test-repo",
        htmlUrl = "https://github.com/test-org/test-repo",
    ),
)

fun testNotificationUiState(): NotificationUiState = NotificationUiState(
    notificationThreadId = "thread-1",
    title = "Notification thread-1",
    reason = "review_requested",
    updatedAtEpochMs = 2_026_052_910_000,
    repositoryFullName = "test-org/test-repo",
    subjectType = "PullRequest",
    htmlUrl = "https://github.com/test-org/test-repo/pull/1",
    apiUrl = "https://api.github.com/repos/test-org/test-repo/pulls/1",
    isPullRequest = true,
    pullRequestNumber = 1,
    unread = true,
    headRef = "feature/test",
)
