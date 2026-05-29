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
import com.github.karlsabo.notifications.IgnoredNotificationThread
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
    fun showsNewGitHubActivityOnPreviouslyDoneThread() = runBlocking {
        val pullRequestUrl = "https://api.github.com/repos/test-org/test-repo/pulls/1"
        val notification = testNotification(
            id = "thread-1",
            title = "Please review feature",
            subjectType = "PullRequest",
            subjectUrl = pullRequestUrl,
            updatedAt = Instant.parse("2026-05-29T10:05:00Z"),
        )
        val api = NotificationPersistenceGitHubApi(
            notifications = listOf(notification),
            pullRequestsByUrl = mapOf(
                pullRequestUrl to PullRequest(
                    number = 1,
                    url = pullRequestUrl,
                    state = "open",
                    head = PullRequestHead(ref = "feature/revived"),
                ),
            ),
        )
        val store = RecordingNotificationIgnoreStore(
            initialIgnoredThreads = listOf(
                ignoredThread(
                    threadId = "thread-1",
                    reason = NotificationIgnoreReason.DONE,
                    notificationUpdatedAtEpochMs = Instant.parse("2026-05-29T10:00:00Z").toEpochMilliseconds(),
                ),
            ),
        )
        val viewModel = createViewModel(api, store)

        val notifications = withTimeout(2_000.milliseconds) {
            viewModel.notifications.filterNotNull().first().getOrThrow()
        }

        assertEquals(listOf("thread-1"), notifications.map { it.notificationThreadId })
        assertTrue(api.markedDoneThreadIds.value.isEmpty(), "Open non-auto-approvable notifications should be shown")
    }

    @Test
    fun automaticClosedPullRequestCleanupPersistsDoneThreadAndFiltersAfterRestart() = runBlocking {
        assertAutomaticPullRequestCleanupPersistsDoneThreadAndFiltersAfterRestart(
            PullRequest(
                number = 24,
                url = "https://api.github.com/repos/test-org/test-repo/pulls/24",
                state = "closed",
            ),
        )
    }

    @Test
    fun automaticMergedPullRequestCleanupPersistsDoneThreadAndFiltersAfterRestart() = runBlocking {
        assertAutomaticPullRequestCleanupPersistsDoneThreadAndFiltersAfterRestart(
            PullRequest(
                number = 24,
                url = "https://api.github.com/repos/test-org/test-repo/pulls/24",
                state = "closed",
                mergedAt = Instant.parse("2026-05-20T00:00:00Z"),
            ),
        )
    }

    private suspend fun assertAutomaticPullRequestCleanupPersistsDoneThreadAndFiltersAfterRestart(
        pullRequest: PullRequest,
    ) {
        val pullRequestUrl = requireNotNull(pullRequest.url)
        val notification = testNotification(
            id = "thread-4",
            subjectType = "PullRequest",
            subjectUrl = pullRequestUrl,
        )
        val api = NotificationPersistenceGitHubApi(
            notifications = listOf(notification),
            pullRequestsByUrl = mapOf(pullRequestUrl to pullRequest),
        )
        val store = RecordingNotificationIgnoreStore()
        val viewModel = createViewModel(api, store)

        val notifications = withTimeout(2_000.milliseconds) {
            viewModel.notifications.filterNotNull().first().getOrThrow()
        }

        assertTrue(notifications.isEmpty())
        assertEquals(listOf("thread-4"), api.markedDoneThreadIds.awaitValue())
        assertEquals(
            listOf(
                SavedThread(
                    threadId = "thread-4",
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
        assertEquals(listOf("thread-4"), api.markedDoneThreadIds.value)
    }

    @Test
    fun automaticAppfileApprovalPersistsDoneThreadAndFiltersAfterRestart() = runBlocking {
        assertAutomaticAppfileCleanupPersistsDoneThreadAndFiltersAfterRestart(
            threadId = "thread-5",
            pullRequestNumber = 25,
            alreadyApproved = false,
        )
    }

    @Test
    fun alreadyApprovedAutomaticAppfilePersistsDoneThreadAndFiltersAfterRestart() = runBlocking {
        assertAutomaticAppfileCleanupPersistsDoneThreadAndFiltersAfterRestart(
            threadId = "thread-6",
            pullRequestNumber = 26,
            alreadyApproved = true,
        )
    }

    private suspend fun assertAutomaticAppfileCleanupPersistsDoneThreadAndFiltersAfterRestart(
        threadId: String,
        pullRequestNumber: Int,
        alreadyApproved: Boolean,
    ) {
        val pullRequestUrl = "https://api.github.com/repos/test-org/test-repo/pulls/$pullRequestNumber"
        val notification = testNotification(
            id = threadId,
            title = "Updating appfile demo service",
            subjectType = "PullRequest",
            subjectUrl = pullRequestUrl,
        )
        val expectedApprovedPullRequestUrls = if (alreadyApproved) emptyList() else listOf(pullRequestUrl)
        val api = NotificationPersistenceGitHubApi(
            notifications = listOf(notification),
            pullRequestsByUrl = mapOf(
                pullRequestUrl to PullRequest(
                    number = pullRequestNumber,
                    url = pullRequestUrl,
                    state = "open",
                ),
            ),
            approvedReviewUrls = if (alreadyApproved) setOf(pullRequestUrl) else emptySet(),
        )
        val store = RecordingNotificationIgnoreStore()
        val viewModel = createViewModel(api, store)

        val notifications = withTimeout(2_000.milliseconds) {
            viewModel.notifications.filterNotNull().first().getOrThrow()
        }

        assertTrue(notifications.isEmpty())
        assertEquals(listOf(threadId), api.markedDoneThreadIds.awaitValue())
        assertEquals(expectedApprovedPullRequestUrls, api.approvedPullRequestUrls.value)
        assertEquals(
            listOf(
                SavedThread(
                    threadId = threadId,
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
        assertEquals(expectedApprovedPullRequestUrls, api.approvedPullRequestUrls.value)
        assertEquals(listOf(threadId), api.markedDoneThreadIds.value)
    }

    @Test
    fun automaticDonePersistenceFailureIsLogOnlyAndRetriesOnLaterPoll() = runBlocking {
        val pullRequestUrl = "https://api.github.com/repos/test-org/test-repo/pulls/7"
        val notification = testNotification(
            id = "thread-7",
            subjectType = "PullRequest",
            subjectUrl = pullRequestUrl,
        )
        val api = NotificationPersistenceGitHubApi(
            notifications = listOf(notification),
            pullRequestsByUrl = mapOf(
                pullRequestUrl to PullRequest(
                    number = 7,
                    url = pullRequestUrl,
                    state = "closed",
                ),
            ),
        )
        val store = RecordingNotificationIgnoreStore(
            saveFailuresBeforeSuccess = listOf(IllegalStateException("persist failed")),
        )
        val viewModel = createViewModel(api, store, pollIntervalMs = 50)

        val notifications = withTimeout(2_000.milliseconds) {
            viewModel.notifications.filterNotNull().first().getOrThrow()
        }

        assertTrue(notifications.isEmpty())
        assertEquals(listOf("thread-7", "thread-7"), api.markedDoneThreadIds.awaitSize(2))
        val savedThreads = store.savedThreads.awaitValue()
        assertEquals(
            listOf(
                SavedThread(
                    threadId = "thread-7",
                    repositoryFullName = "test-org/test-repo",
                    subjectType = "PullRequest",
                    reason = NotificationIgnoreReason.DONE,
                ),
            ),
            savedThreads.map { it.withoutTimestamp() },
        )
        assertEquals(notification.updatedAt.toEpochMilliseconds(), savedThreads.single().notificationUpdatedAtEpochMs)
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
        val notificationUiState = withTimeout(2_000.milliseconds) {
            viewModel.notifications
                .filterNotNull()
                .map { it.getOrThrow() }
                .first { notifications -> notifications.any { it.notificationThreadId == "thread-1" } }
                .single { it.notificationThreadId == "thread-1" }
        }

        viewModel.markNotificationDone(notificationUiState)

        assertEquals(listOf("thread-1"), api.markedDoneThreadIds.awaitValue())
        val savedThreads = store.savedThreads.awaitValue()
        assertEquals(
            listOf(
                SavedThread(
                    threadId = "thread-1",
                    repositoryFullName = "test-org/test-repo",
                    subjectType = "PullRequest",
                    reason = NotificationIgnoreReason.DONE,
                ),
            ),
            savedThreads.map { it.withoutTimestamp() },
        )
        assertEquals(notification.updatedAt.toEpochMilliseconds(), savedThreads.single().notificationUpdatedAtEpochMs)

        val restartedViewModel = createViewModel(api, store)
        val restartedNotifications = withTimeout(2_000.milliseconds) {
            restartedViewModel.notifications.filterNotNull().first().getOrThrow()
        }
        assertTrue(restartedNotifications.isEmpty())
    }

    @Test
    fun doneMarkDoneFailureRestoresVisibilityAndDoesNotPersist() = runBlocking {
        val notification = testNotification(
            id = "thread-1",
            subjectType = "PullRequest",
            subjectUrl = "https://api.github.com/repos/test-org/test-repo/pulls/1",
        )
        val api = NotificationPersistenceGitHubApi(
            notifications = listOf(notification),
            markDoneFailure = IllegalStateException("mark done failed"),
        )
        val store = RecordingNotificationIgnoreStore()
        val viewModel = createViewModel(api, store)
        val notificationUiState = withTimeout(2_000.milliseconds) {
            viewModel.notifications
                .filterNotNull()
                .map { it.getOrThrow() }
                .first { notifications -> notifications.any { it.notificationThreadId == "thread-1" } }
                .single { it.notificationThreadId == "thread-1" }
        }

        viewModel.markNotificationDone(notificationUiState)

        assertEquals(listOf("thread-1"), api.markedDoneThreadIds.awaitValue())
        assertEquals(
            "mark done failed",
            withTimeout(2_000.milliseconds) { viewModel.actionErrorStateFlow.filterNotNull().first().message },
        )
        withTimeout(2_000.milliseconds) {
            viewModel.notifications
                .filterNotNull()
                .map { it.getOrThrow() }
                .first { notifications -> notifications.any { it.notificationThreadId == "thread-1" } }
        }
        assertTrue(store.savedThreads.value.isEmpty(), "Failed mark-done should not persist the thread locally")
    }

    @Test
    fun approvePersistsDoneThreadAndFiltersAfterRestart() = runBlocking {
        val pullRequestUrl = "https://api.github.com/repos/test-org/test-repo/pulls/22"
        val notification = testNotification(
            id = "thread-2",
            subjectType = "PullRequest",
            subjectUrl = pullRequestUrl,
        )
        val api = NotificationPersistenceGitHubApi(
            notifications = listOf(notification),
            pullRequestsByUrl = mapOf(
                pullRequestUrl to PullRequest(
                    number = 22,
                    url = pullRequestUrl,
                    head = PullRequestHead(ref = "feature/approve"),
                ),
            ),
        )
        val store = RecordingNotificationIgnoreStore()
        val viewModel = createViewModel(api, store)
        val notificationUiState = withTimeout(2_000.milliseconds) {
            viewModel.notifications
                .filterNotNull()
                .map { it.getOrThrow() }
                .first { notifications -> notifications.any { it.notificationThreadId == "thread-2" } }
                .single { it.notificationThreadId == "thread-2" }
        }

        viewModel.approvePullRequest(notificationUiState)

        assertEquals(listOf(pullRequestUrl), api.approvedPullRequestUrls.awaitValue())
        assertEquals(listOf("thread-2"), api.markedDoneThreadIds.awaitValue())
        val savedThreads = store.savedThreads.awaitValue()
        assertEquals(
            listOf(
                SavedThread(
                    threadId = "thread-2",
                    repositoryFullName = "test-org/test-repo",
                    subjectType = "PullRequest",
                    reason = NotificationIgnoreReason.DONE,
                ),
            ),
            savedThreads.map { it.withoutTimestamp() },
        )
        assertEquals(notification.updatedAt.toEpochMilliseconds(), savedThreads.single().notificationUpdatedAtEpochMs)

        val restartedViewModel = createViewModel(api, store)
        val restartedNotifications = withTimeout(2_000.milliseconds) {
            restartedViewModel.notifications.filterNotNull().first().getOrThrow()
        }
        assertTrue(restartedNotifications.isEmpty())
    }

    @Test
    fun submitReviewPersistsDoneThreadAndFiltersAfterRestart() = runBlocking {
        val pullRequestUrl = "https://api.github.com/repos/test-org/test-repo/pulls/23"
        val notification = testNotification(
            id = "thread-3",
            subjectType = "PullRequest",
            subjectUrl = pullRequestUrl,
        )
        val api = NotificationPersistenceGitHubApi(
            notifications = listOf(notification),
            pullRequestsByUrl = mapOf(
                pullRequestUrl to PullRequest(
                    number = 23,
                    url = pullRequestUrl,
                    head = PullRequestHead(ref = "feature/review"),
                ),
            ),
        )
        val store = RecordingNotificationIgnoreStore()
        val viewModel = createViewModel(api, store)
        val notificationUiState = withTimeout(2_000.milliseconds) {
            viewModel.notifications
                .filterNotNull()
                .map { it.getOrThrow() }
                .first { notifications -> notifications.any { it.notificationThreadId == "thread-3" } }
                .single { it.notificationThreadId == "thread-3" }
        }

        viewModel.submitReview(notificationUiState, ReviewStateValue.COMMENTED, "Looks good")

        assertEquals(
            listOf(SubmittedReview(pullRequestUrl, ReviewStateValue.COMMENTED, "Looks good")),
            api.submittedReviews.awaitValue(),
        )
        assertEquals(listOf("thread-3"), api.markedDoneThreadIds.awaitValue())
        val savedThreads = store.savedThreads.awaitValue()
        assertEquals(
            listOf(
                SavedThread(
                    threadId = "thread-3",
                    repositoryFullName = "test-org/test-repo",
                    subjectType = "PullRequest",
                    reason = NotificationIgnoreReason.DONE,
                ),
            ),
            savedThreads.map { it.withoutTimestamp() },
        )
        assertEquals(notification.updatedAt.toEpochMilliseconds(), savedThreads.single().notificationUpdatedAtEpochMs)

        val restartedViewModel = createViewModel(api, store)
        val restartedNotifications = withTimeout(2_000.milliseconds) {
            restartedViewModel.notifications.filterNotNull().first().getOrThrow()
        }
        assertTrue(restartedNotifications.isEmpty())
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
    pollIntervalMs: Long = 60_000,
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
            pollIntervalMs = pollIntervalMs,
        ),
        notificationIgnoreStore = store,
    )
}

private suspend fun <T> MutableStateFlow<List<T>>.awaitValue(): List<T> =
    withTimeout(2_000.milliseconds) { first { it.isNotEmpty() } }

private suspend fun <T> MutableStateFlow<List<T>>.awaitSize(size: Int): List<T> =
    withTimeout(2_000.milliseconds) { first { it.size >= size } }

private class NoOpGitWorktreeApi : GitWorktreeApi {
    override fun ensureRepository(repoPath: String, cloneUrl: String) = Unit

    override fun ensureWorktree(repoPath: String, branch: String): String = buildWorktreePath(repoPath, branch).value

    override fun createBranchWorktree(
        repoPath: String,
        baseWorktreePath: String,
        baseBranch: String,
        targetBranch: String,
    ): String {
        error("Unexpected call")
    }

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
    val notificationUpdatedAtEpochMs: Long? = null,
) {
    fun withoutTimestamp(): SavedThread = copy(ignoredAtEpochMs = null, notificationUpdatedAtEpochMs = null)
}

private fun ignoredThread(
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

private class RecordingNotificationIgnoreStore(
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

    override fun saveIgnoredThread(
        threadId: String,
        repositoryFullName: String,
        subjectType: String,
        reason: NotificationIgnoreReason,
        ignoredAtEpochMs: Long,
        notificationUpdatedAtEpochMs: Long?,
    ) {
        saveFailure?.let { throw it }
        if (queuedSaveFailures.isNotEmpty()) throw queuedSaveFailures.removeAt(0)
        storedIgnoredThreads[threadId] = IgnoredNotificationThread(
            threadId = threadId,
            repositoryFullName = repositoryFullName,
            subjectType = subjectType,
            reason = reason,
            ignoredAtEpochMs = ignoredAtEpochMs,
            notificationUpdatedAtEpochMs = notificationUpdatedAtEpochMs,
        )
        savedThreads.value += SavedThread(
            threadId = threadId,
            repositoryFullName = repositoryFullName,
            subjectType = subjectType,
            reason = reason,
            ignoredAtEpochMs = ignoredAtEpochMs,
            notificationUpdatedAtEpochMs = notificationUpdatedAtEpochMs,
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

    override suspend fun hasAnyApprovedReview(url: String): Boolean = url in approvedReviewUrls

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
    title: String = "Notification $id",
    updatedAt: Instant = Clock.System.now(),
): Notification {
    return Notification(
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
}

private fun testNotificationUiState(@Suppress("SameParameterValue") threadId: String): NotificationUiState {
    return NotificationUiState(
        notificationThreadId = threadId,
        title = "Notification $threadId",
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
}
