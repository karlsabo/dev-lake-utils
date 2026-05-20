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
import com.github.karlsabo.github.PullRequestHead
import com.github.karlsabo.github.ReviewStateValue
import com.github.karlsabo.github.ReviewSummary
import com.github.karlsabo.notifications.NotificationIgnoreReason
import com.github.karlsabo.notifications.NotificationIgnoreStore
import com.github.karlsabo.system.DesktopLauncher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class EngHubNotificationPersistenceViewModelTest {

    @Test
    fun enrichesPullRequestNotificationsWithNumberAndDisplayTitle() = runBlocking {
        val pullRequestUrl = "https://api.github.com/repos/test-org/test-repo/pulls/1234"
        val notification = testNotification(
            id = "thread-1234",
            subjectType = "PullRequest",
            subjectUrl = pullRequestUrl,
        )
        val api = NotificationPersistenceGitHubApi(
            notifications = listOf(notification),
            pullRequestsByUrl = mapOf(
                pullRequestUrl to PullRequest(
                    number = 1234,
                    url = pullRequestUrl,
                    head = PullRequestHead(ref = "feature/pr-number"),
                ),
            ),
        )
        val viewModel = createViewModel(api, RecordingNotificationIgnoreStore())

        val notifications = withTimeout(2_000.milliseconds) {
            viewModel.notifications.filterNotNull().first().getOrThrow()
        }
        val uiState = notifications.single()

        assertEquals(1234, uiState.pullRequestNumber)
        assertEquals("#1234 Notification thread-1234", uiState.displayTitle)
        assertEquals("feature/pr-number", uiState.headRef)
    }

    @Test
    fun filtersOutPullRequestNotificationsWhenDetailLookupFails() = runBlocking {
        val pullRequestUrl = "https://api.github.com/repos/test-org/test-repo/pulls/9999"
        val notification = testNotification(
            id = "thread-9999",
            subjectType = "PullRequest",
            subjectUrl = pullRequestUrl,
        )
        val api = NotificationPersistenceGitHubApi(
            notifications = listOf(notification),
            pullRequestFailureUrls = setOf(pullRequestUrl),
        )
        val viewModel = createViewModel(api, RecordingNotificationIgnoreStore())

        val notifications = withTimeout(2_000.milliseconds) {
            viewModel.notifications.filterNotNull().first().getOrThrow()
        }

        assertTrue(notifications.isEmpty())
        assertTrue(api.pullRequestByUrlCalls.contains(pullRequestUrl))
    }

    @Test
    fun mixedNotificationBatchDropsOnlyFailedPullRequestNotification() = runBlocking {
        val successfulPullRequestUrl = "https://api.github.com/repos/test-org/test-repo/pulls/1234"
        val failedPullRequestUrl = "https://api.github.com/repos/test-org/test-repo/pulls/9999"
        val issueUrl = "https://api.github.com/repos/test-org/test-repo/issues/77"
        val api = NotificationPersistenceGitHubApi(
            notifications = listOf(
                testNotification(
                    id = "thread-1234",
                    subjectType = "PullRequest",
                    subjectUrl = successfulPullRequestUrl,
                ),
                testNotification(
                    id = "thread-9999",
                    subjectType = "PullRequest",
                    subjectUrl = failedPullRequestUrl,
                ),
                testNotification(
                    id = "thread-issue",
                    subjectType = "Issue",
                    subjectUrl = issueUrl,
                ),
            ),
            pullRequestsByUrl = mapOf(
                successfulPullRequestUrl to PullRequest(
                    number = 1234,
                    url = successfulPullRequestUrl,
                    head = PullRequestHead(ref = "feature/pr-number"),
                ),
            ),
            pullRequestFailureUrls = setOf(failedPullRequestUrl),
        )
        val viewModel = createViewModel(api, RecordingNotificationIgnoreStore())

        val notifications = withTimeout(2_000.milliseconds) {
            viewModel.notifications.filterNotNull().first().getOrThrow()
        }
        val notificationsById = notifications.associateBy { it.notificationThreadId }

        assertEquals(setOf("thread-1234", "thread-issue"), notificationsById.keys)
        assertEquals(setOf(successfulPullRequestUrl, failedPullRequestUrl), api.pullRequestByUrlCalls.toSet())

        val successfulPullRequest = notificationsById.getValue("thread-1234")
        assertEquals(1234, successfulPullRequest.pullRequestNumber)
        assertEquals("#1234 Notification thread-1234", successfulPullRequest.displayTitle)
        assertEquals("feature/pr-number", successfulPullRequest.headRef)

        val issueNotification = notificationsById.getValue("thread-issue")
        assertEquals("Notification thread-issue", issueNotification.title)
        assertEquals("Notification thread-issue", issueNotification.displayTitle)
        assertEquals("Issue", issueNotification.subjectType)
        assertEquals(issueUrl, issueNotification.apiUrl)
        assertEquals(false, issueNotification.isPullRequest)
        assertEquals(null, issueNotification.pullRequestNumber)
        assertEquals(null, issueNotification.headRef)
    }

    @Test
    fun loadsPersistedIgnoredThreadIdsBeforeNotificationEnrichment() = runBlocking {
        val hiddenNotification = testNotification(
            id = "thread-hidden",
            subjectType = "PullRequest",
            subjectUrl = "https://api.github.com/repos/test-org/test-repo/pulls/1",
        )
        val visibleNotification = testNotification(
            id = "thread-visible",
            subjectType = "Issue",
            subjectUrl = null,
        )
        val api = NotificationPersistenceGitHubApi(
            notifications = listOf(hiddenNotification, visibleNotification),
        )
        val store = RecordingNotificationIgnoreStore(initialThreadIds = setOf("thread-hidden"))
        val viewModel = createViewModel(api, store)

        val notifications = withTimeout(2_000.milliseconds) {
            viewModel.notifications.filterNotNull().first().getOrThrow()
        }

        assertEquals(listOf("thread-visible"), notifications.map { it.notificationThreadId })
        assertTrue(api.pullRequestByUrlCalls.isEmpty(), "Hidden notifications should be filtered before enrichment")
    }

    @Test
    fun automaticallyMarkedDoneNotificationsArePersistedAndFilteredAfterRestart() = runBlocking {
        val pullRequestUrl = "https://api.github.com/repos/test-org/test-repo/pulls/1"
        val notification = testNotification(
            id = "thread-auto-done",
            subjectType = "PullRequest",
            subjectUrl = pullRequestUrl,
        )
        val api = NotificationPersistenceGitHubApi(
            notifications = listOf(notification),
            pullRequestsByUrl = mapOf(
                pullRequestUrl to PullRequest(
                    number = 1,
                    url = pullRequestUrl,
                    state = "closed",
                ),
            ),
        )
        val store = RecordingNotificationIgnoreStore()
        val viewModel = createViewModel(api, store)

        val notifications = withTimeout(2_000.milliseconds) {
            viewModel.notifications.filterNotNull().first().getOrThrow()
        }

        assertTrue(notifications.isEmpty())
        assertEquals(listOf("thread-auto-done"), api.markedDoneThreadIds.awaitValue())
        assertEquals(
            listOf(
                SavedThread(
                    threadId = "thread-auto-done",
                    repositoryFullName = "test-org/test-repo",
                    subjectType = "PullRequest",
                    reason = NotificationIgnoreReason.DONE,
                ),
            ),
            store.savedThreads.awaitValue().map { it.withoutTimestamp() },
        )

        val restartedViewModel = createViewModel(api, store)
        val restartedNotifications = withTimeout(2_000.milliseconds) {
            restartedViewModel.notifications.filterNotNull().first().getOrThrow()
        }
        assertTrue(restartedNotifications.isEmpty())
        assertEquals(listOf("thread-auto-done"), api.markedDoneThreadIds.value)
    }

    @Test
    fun automaticDonePersistenceFailureIsLogOnly() = runBlocking {
        val pullRequestUrl = "https://api.github.com/repos/test-org/test-repo/pulls/1"
        val notification = testNotification(
            id = "thread-auto-done",
            subjectType = "PullRequest",
            subjectUrl = pullRequestUrl,
        )
        val api = NotificationPersistenceGitHubApi(
            notifications = listOf(notification),
            pullRequestsByUrl = mapOf(
                pullRequestUrl to PullRequest(
                    number = 1,
                    url = pullRequestUrl,
                    state = "closed",
                ),
            ),
        )
        val store = RecordingNotificationIgnoreStore(
            saveFailure = IllegalStateException("persist failed"),
        )
        val viewModel = createViewModel(api, store)

        val notifications = withTimeout(2_000.milliseconds) {
            viewModel.notifications.filterNotNull().first().getOrThrow()
        }

        assertTrue(notifications.isEmpty())
        assertEquals(listOf("thread-auto-done"), api.markedDoneThreadIds.awaitValue())
        assertTrue(store.savedThreads.value.isEmpty(), "Failed automatic persistence should not save the thread")
        assertEquals(null, viewModel.actionErrorStateFlow.value)
    }

    @Test
    fun donePersistsThreadAndFiltersAfterRestart() = runBlocking {
        val notification = testNotification(
            id = "thread-1",
            subjectType = "PullRequest",
            subjectUrl = "https://api.github.com/repos/test-org/test-repo/pulls/1",
        )
        val api = NotificationPersistenceGitHubApi(notifications = listOf(notification))
        val store = RecordingNotificationIgnoreStore()
        val viewModel = createViewModel(api, store)

        viewModel.markNotificationDone(testNotificationUiState("thread-1"))

        assertEquals(listOf("thread-1"), api.markedDoneThreadIds.awaitValue())
        assertEquals(
            listOf(
                SavedThread(
                    threadId = "thread-1",
                    repositoryFullName = "test-org/test-repo",
                    subjectType = "PullRequest",
                    reason = NotificationIgnoreReason.DONE,
                ),
            ),
            store.savedThreads.awaitValue().map { it.withoutTimestamp() },
        )

        val restartedViewModel = createViewModel(api, store)
        val restartedNotifications = withTimeout(2_000.milliseconds) {
            restartedViewModel.notifications.filterNotNull().first().getOrThrow()
        }
        assertTrue(restartedNotifications.isEmpty())
    }

    @Test
    fun approvePersistsDoneThreadAndMarksNotificationDone() = runBlocking {
        val api = NotificationPersistenceGitHubApi()
        val store = RecordingNotificationIgnoreStore()
        val viewModel = createViewModel(api, store)
        val notification = testNotificationUiState("thread-approve")

        viewModel.approvePullRequest(notification)

        assertEquals(listOf(notification.apiUrl!!), api.approvedPullRequestUrls.awaitValue())
        assertEquals(listOf("thread-approve"), api.markedDoneThreadIds.awaitValue())
        assertEquals(
            listOf(
                SavedThread(
                    threadId = "thread-approve",
                    repositoryFullName = "test-org/test-repo",
                    subjectType = "PullRequest",
                    reason = NotificationIgnoreReason.DONE,
                ),
            ),
            store.savedThreads.awaitValue().map { it.withoutTimestamp() },
        )
    }

    @Test
    fun submitReviewPersistsDoneThreadAndMarksNotificationDone() = runBlocking {
        val api = NotificationPersistenceGitHubApi()
        val store = RecordingNotificationIgnoreStore()
        val viewModel = createViewModel(api, store)
        val notification = testNotificationUiState("thread-review")

        viewModel.submitReview(notification, ReviewStateValue.COMMENTED, "Looks good")

        assertEquals(
            listOf(SubmittedReview(notification.apiUrl!!, ReviewStateValue.COMMENTED, "Looks good")),
            api.submittedReviews.awaitValue(),
        )
        assertEquals(listOf("thread-review"), api.markedDoneThreadIds.awaitValue())
        assertEquals(
            listOf(
                SavedThread(
                    threadId = "thread-review",
                    repositoryFullName = "test-org/test-repo",
                    subjectType = "PullRequest",
                    reason = NotificationIgnoreReason.DONE,
                ),
            ),
            store.savedThreads.awaitValue().map { it.withoutTimestamp() },
        )
    }

    @Test
    fun submitReviewMarkDoneFailureReportsFailureRestoresVisibilityAndDoesNotPersist() = runBlocking {
        val pullRequestUrl = "https://api.github.com/repos/test-org/test-repo/pulls/1"
        val api = NotificationPersistenceGitHubApi(
            notifications = listOf(
                testNotification(
                    id = "thread-review",
                    subjectType = "PullRequest",
                    subjectUrl = pullRequestUrl,
                ),
            ),
            markDoneFailure = IllegalStateException("mark done failed"),
        )
        val store = RecordingNotificationIgnoreStore()
        val viewModel = createViewModel(api, store)
        val notification = withTimeout(2_000.milliseconds) {
            viewModel.notifications
                .filterNotNull()
                .map { it.getOrThrow() }
                .first { notifications -> notifications.any { it.notificationThreadId == "thread-review" } }
                .single { it.notificationThreadId == "thread-review" }
        }

        viewModel.submitReview(notification, ReviewStateValue.COMMENTED, "Looks good")

        assertEquals(
            listOf(SubmittedReview(pullRequestUrl, ReviewStateValue.COMMENTED, "Looks good")),
            api.submittedReviews.awaitValue(),
        )
        assertEquals(listOf("thread-review"), api.markedDoneThreadIds.awaitValue())
        assertEquals(
            "mark done failed",
            withTimeout(2_000.milliseconds) { viewModel.actionErrorStateFlow.filterNotNull().first().message },
        )
        withTimeout(2_000.milliseconds) {
            viewModel.notifications
                .filterNotNull()
                .map { it.getOrThrow() }
                .first { notifications -> notifications.any { it.notificationThreadId == "thread-review" } }
        }
        assertTrue(store.savedThreads.value.isEmpty(), "Failed mark-done should not persist the thread locally")
    }

    @Test
    fun submitReviewPersistenceFailureReportsLocalFailureAndRestoresNotificationVisibility() = runBlocking {
        val pullRequestUrl = "https://api.github.com/repos/test-org/test-repo/pulls/1"
        val api = NotificationPersistenceGitHubApi(
            notifications = listOf(
                testNotification(
                    id = "thread-review",
                    subjectType = "PullRequest",
                    subjectUrl = pullRequestUrl,
                ),
            ),
        )
        val store = RecordingNotificationIgnoreStore(
            saveFailure = IllegalStateException("persist failed"),
        )
        val viewModel = createViewModel(api, store)
        val notification = withTimeout(2_000.milliseconds) {
            viewModel.notifications
                .filterNotNull()
                .map { it.getOrThrow() }
                .first { notifications -> notifications.any { it.notificationThreadId == "thread-review" } }
                .single { it.notificationThreadId == "thread-review" }
        }

        viewModel.submitReview(notification, ReviewStateValue.COMMENTED, "Looks good")

        assertEquals(
            listOf(SubmittedReview(pullRequestUrl, ReviewStateValue.COMMENTED, "Looks good")),
            api.submittedReviews.awaitValue(),
        )
        assertEquals(listOf("thread-review"), api.markedDoneThreadIds.awaitValue())
        assertEquals(
            "persist failed",
            withTimeout(2_000.milliseconds) { viewModel.actionErrorStateFlow.filterNotNull().first().message },
        )
        withTimeout(2_000.milliseconds) {
            viewModel.notifications
                .filterNotNull()
                .map { it.getOrThrow() }
                .first { notifications -> notifications.any { it.notificationThreadId == "thread-review" } }
        }
        assertTrue(store.savedThreads.value.isEmpty(), "Failed persistence should not leave a saved local thread")
    }

    @Test
    fun donePersistenceFailureReportsLocalFailureAndRestoresNotificationVisibility() = runBlocking {
        val notification = testNotification(
            id = "thread-1",
            subjectType = "PullRequest",
            subjectUrl = "https://api.github.com/repos/test-org/test-repo/pulls/1",
        )
        val api = NotificationPersistenceGitHubApi(notifications = listOf(notification))
        val store = RecordingNotificationIgnoreStore(
            saveFailure = IllegalStateException("persist failed"),
        )
        val viewModel = createViewModel(api, store)

        withTimeout(2_000.milliseconds) {
            viewModel.notifications
                .filterNotNull()
                .map { it.getOrThrow() }
                .first { notifications -> notifications.any { it.notificationThreadId == "thread-1" } }
        }

        viewModel.markNotificationDone(testNotificationUiState("thread-1"))

        assertEquals(listOf("thread-1"), api.markedDoneThreadIds.awaitValue())
        assertEquals(
            "persist failed",
            withTimeout(2_000.milliseconds) { viewModel.actionErrorStateFlow.filterNotNull().first().message },
        )
        withTimeout(2_000.milliseconds) {
            viewModel.notifications
                .filterNotNull()
                .map { it.getOrThrow() }
                .first { notifications -> notifications.any { it.notificationThreadId == "thread-1" } }
        }
        assertTrue(store.savedThreads.value.isEmpty(), "Failed persistence should not leave a saved local thread")
    }

    @Test
    fun unsubscribePersistsThreadAndMarksNotificationDone() = runBlocking {
        val api = NotificationPersistenceGitHubApi()
        val store = RecordingNotificationIgnoreStore()
        val viewModel = createViewModel(api, store)
        val notification = testNotificationUiState("thread-1")

        viewModel.unsubscribeFromNotification(notification)

        assertEquals(listOf("thread-1"), api.unsubscribedThreadIds.awaitValue())
        assertEquals(listOf("thread-1"), api.markedDoneThreadIds.awaitValue())
        assertEquals(
            listOf(
                SavedThread(
                    threadId = "thread-1",
                    repositoryFullName = "test-org/test-repo",
                    subjectType = "PullRequest",
                    reason = NotificationIgnoreReason.UNSUBSCRIBED,
                ),
            ),
            store.savedThreads.awaitValue().map { it.withoutTimestamp() },
        )
    }

    @Test
    fun failedUnsubscribeDoesNotPersistThread() = runBlocking {
        val api = NotificationPersistenceGitHubApi(
            unsubscribeFailure = IllegalStateException("boom"),
        )
        val store = RecordingNotificationIgnoreStore()
        val viewModel = createViewModel(api, store)

        viewModel.unsubscribeFromNotification(testNotificationUiState("thread-1"))

        assertEquals(
            "boom",
            withTimeout(2_000.milliseconds) { viewModel.actionErrorStateFlow.filterNotNull().first().message })
        assertTrue(store.savedThreads.value.isEmpty(), "Failed unsubscribe should not persist the thread locally")
        assertTrue(api.markedDoneThreadIds.value.isEmpty(), "Failed unsubscribe should not mark the thread done")
    }

    @Test
    fun failedPersistenceKeepsNotificationVisibleAndDoesNotMarkDone() = runBlocking {
        val notification = testNotification(
            id = "thread-1",
            subjectType = "PullRequest",
            subjectUrl = "https://api.github.com/repos/test-org/test-repo/pulls/1",
        )
        val api = NotificationPersistenceGitHubApi(notifications = listOf(notification))
        val store = RecordingNotificationIgnoreStore(
            saveFailure = IllegalStateException("persist failed"),
        )
        val viewModel = createViewModel(api, store)

        withTimeout(2_000.milliseconds) {
            viewModel.notifications
                .filterNotNull()
                .map { it.getOrThrow() }
                .first { notifications -> notifications.any { it.notificationThreadId == "thread-1" } }
        }

        viewModel.unsubscribeFromNotification(testNotificationUiState("thread-1"))

        assertEquals(
            "persist failed",
            withTimeout(2_000.milliseconds) { viewModel.actionErrorStateFlow.filterNotNull().first().message })
        withTimeout(2_000.milliseconds) {
            viewModel.notifications
                .filterNotNull()
                .map { it.getOrThrow() }
                .first { notifications -> notifications.any { it.notificationThreadId == "thread-1" } }
        }
        assertEquals(listOf("thread-1"), api.unsubscribedThreadIds.awaitValue())
        assertTrue(store.savedThreads.value.isEmpty(), "Failed persistence should not leave a saved local thread")
        assertTrue(api.markedDoneThreadIds.value.isEmpty(), "Failed persistence should not mark the thread done")
    }
}

private fun createViewModel(
    api: NotificationPersistenceGitHubApi,
    store: RecordingNotificationIgnoreStore,
): EngHubViewModel {
    val gitWorktreeApi = NoOpGitWorktreeApi()
    return EngHubViewModel(
        gitHubApi = api,
        gitHubNotificationService = GitHubNotificationService(api),
        gitWorktreeApi = gitWorktreeApi,
        worktreeSetupCoordinator = WorktreeSetupCoordinator(gitWorktreeApi = gitWorktreeApi),
        desktopLauncher = NoOpDesktopLauncher(),
        directoryPicker = NoOpDirectoryPicker(),
        configWriter = NoOpEngHubConfigWriter(),
        config = EngHubConfig(
            organizationIds = listOf("test-org"),
            repositoriesBaseDir = "/tmp/repos",
            gitHubAuthor = "test-user",
            pollIntervalMs = 60_000,
        ),
        notificationIgnoreStore = store,
    )
}

private suspend fun <T> MutableStateFlow<List<T>>.awaitValue(): List<T> =
    withTimeout(2_000.milliseconds) { first { it.isNotEmpty() } }

private class NoOpGitWorktreeApi : GitWorktreeApi {
    override fun ensureRepository(repoPath: String, cloneUrl: String) = Unit

    override fun ensureWorktree(repoPath: String, branch: String): String = buildWorktreePath(repoPath, branch).value

    override fun worktreeExists(repoPath: String, branch: String): Boolean = false

    override fun resolveRepositoryRoot(selectedPath: String): RepositoryWorktrees {
        error("Unexpected call")
    }

    override fun listWorktrees(repoPath: String) = emptyList<com.github.karlsabo.git.Worktree>()

    override fun removeWorktree(worktreePath: String, force: Boolean) = Unit

    override fun archiveWorktree(repoPath: String, worktreePath: String, force: Boolean) = Unit
}

private class NoOpDirectoryPicker : DirectoryPicker {
    override suspend fun pickDirectory(title: String): String? = null
}

private class NoOpEngHubConfigWriter : EngHubConfigWriter {
    override fun save(config: EngHubConfig) = Unit
}

private class NoOpDesktopLauncher : DesktopLauncher {
    override fun openUrl(url: String) = Unit

    override fun openInIdea(projectPath: String) = Unit
}

private data class SavedThread(
    val threadId: String,
    val repositoryFullName: String,
    val subjectType: String,
    val reason: NotificationIgnoreReason,
    val ignoredAtEpochMs: Long? = null,
) {
    fun withoutTimestamp(): SavedThread = copy(ignoredAtEpochMs = null)
}

private class RecordingNotificationIgnoreStore(
    initialThreadIds: Set<String> = emptySet(),
    private val saveFailure: Exception? = null,
) : NotificationIgnoreStore {
    private var storedThreadIds = initialThreadIds.toMutableSet()
    val savedThreads = MutableStateFlow<List<SavedThread>>(emptyList())

    override fun listIgnoredThreadIds(): Set<String> = storedThreadIds.toSet()

    override fun saveIgnoredThread(
        threadId: String,
        repositoryFullName: String,
        subjectType: String,
        reason: NotificationIgnoreReason,
        ignoredAtEpochMs: Long,
    ) {
        saveFailure?.let { throw it }
        storedThreadIds += threadId
        savedThreads.value += SavedThread(
            threadId = threadId,
            repositoryFullName = repositoryFullName,
            subjectType = subjectType,
            reason = reason,
            ignoredAtEpochMs = ignoredAtEpochMs,
        )
    }
}

private data class SubmittedReview(
    val prApiUrl: String,
    val event: ReviewStateValue,
    val reviewComment: String?,
)

private class NotificationPersistenceGitHubApi(
    private val notifications: List<Notification> = emptyList(),
    private val pullRequestsByUrl: Map<String, PullRequest> = emptyMap(),
    private val pullRequestFailureUrls: Set<String> = emptySet(),
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
        if (url in pullRequestFailureUrls) throw IllegalStateException("failed to load pull request: $url")
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

    override suspend fun hasAnyApprovedReview(url: String): Boolean {
        error("Unexpected call")
    }

    override suspend fun getOpenPullRequestsByAuthor(
        organizationIds: List<String>,
        author: String,
    ): List<Issue> = emptyList()

    override suspend fun getCheckRunsForRef(owner: String, repo: String, ref: String): CheckRunSummary {
        return CheckRunSummary(total = 0, passed = 0, failed = 0, inProgress = 0, status = CiStatus.PENDING)
    }

    override suspend fun getReviewSummary(owner: String, repo: String, prNumber: Int): ReviewSummary {
        return ReviewSummary(approvedCount = 0, requestedCount = 0, reviews = emptyList())
    }

    override suspend fun submitReview(prApiUrl: String, event: ReviewStateValue, reviewComment: String?) {
        submittedReviews.value += SubmittedReview(prApiUrl, event, reviewComment)
    }
}

private fun testNotification(
    id: String,
    subjectType: String,
    subjectUrl: String?,
): Notification {
    val now = Clock.System.now()
    return Notification(
        id = id,
        unread = true,
        reason = "review_requested",
        updatedAt = now,
        lastReadAt = null,
        subject = NotificationSubject(
            title = "Notification $id",
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
}

private fun testNotificationUiState(@Suppress("SameParameterValue") threadId: String): NotificationUiState {
    return NotificationUiState(
        notificationThreadId = threadId,
        title = "Notification $threadId",
        reason = "review_requested",
        repositoryFullName = "test-org/test-repo",
        subjectType = "PullRequest",
        htmlUrl = "https://github.com/test-org/test-repo/pull/1",
        apiUrl = "https://api.github.com/repos/test-org/test-repo/pulls/1",
        isPullRequest = true,
        pullRequestNumber = 1,
        unread = true,
        headRef = "feature/test",
    )
}
