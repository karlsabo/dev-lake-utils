package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.component.visibleWorktreeRows
import com.github.karlsabo.git.Worktree
import com.github.karlsabo.worktreearchive.WorktreeArchiveLifecycleState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

class EngHubLocalWorktreeArchiveViewModelTest {
    @Test
    fun archiveQueuesPersistedWorktreeWithoutRunningGitRemoval() = runBlocking {
        val store = RecordingWorktreeArchiveStore()
        val api = archiveTestApi()
        val queuedAt = Instant.fromEpochMilliseconds(10_000)
        val viewModel = archiveViewModel(api, store) { queuedAt }
        expandRepository(viewModel)

        viewModel.archiveLocalWorktree(DEV_LAKE_ROOT, DEV_LAKE_SELECTED_WORKTREE)

        val job = withTimeout(2_000.milliseconds) {
            viewModel.queuedWorktreeArchivesStateFlow.first { it.isNotEmpty() }.single()
        }
        assertEquals(DEV_LAKE_ROOT, job.repositoryRootPath)
        assertEquals(DEV_LAKE_SELECTED_WORKTREE, job.worktreePath)
        assertEquals("feature/login", job.branch)
        assertEquals(WorktreeArchiveLifecycleState.QUEUED, job.state)
        assertEquals(10_000, job.queuedAtEpochMs)
        assertEquals(70_000, job.deadlineAtEpochMs)
        assertEquals(listOf(job), store.listJobs())
        assertEquals(emptyList(), api.archiveWorktreeCalls)
    }

    @Test
    fun archivePersistsActualRepositoryRootInsteadOfNormalizedIdentity() = runBlocking {
        val actualRepositoryRoot = "$DEV_LAKE_ROOT/"
        val api = RecordingGitWorktreeApi(
            worktreesByRepoPath = mapOf(
                actualRepositoryRoot to listOf(
                    Worktree(path = actualRepositoryRoot, branch = "main", commitHash = "abc123"),
                    Worktree(path = DEV_LAKE_SELECTED_WORKTREE, branch = "feature/login", commitHash = "def456"),
                ),
            ),
        )
        val viewModel = createLocalRepositoryViewModel(
            gitWorktreeApi = api,
            configWriter = RecordingEngHubConfigWriter(),
            localRepositoryConfigs = localRepositoryConfigs(actualRepositoryRoot),
            services = LocalRepositoryViewModelServices(
                archiveNow = { Instant.fromEpochMilliseconds(15_000) },
            ),
        )
        viewModel.toggleLocalRepositoryExpansion(actualRepositoryRoot)
        withTimeout(2_000.milliseconds) {
            viewModel.localRepositoriesStateFlow.first { it.single().worktrees.size == 2 }
        }

        viewModel.archiveLocalWorktree(DEV_LAKE_ROOT, DEV_LAKE_SELECTED_WORKTREE)

        val job = withTimeout(2_000.milliseconds) {
            viewModel.queuedWorktreeArchivesStateFlow.first { it.isNotEmpty() }.single()
        }
        assertEquals(actualRepositoryRoot, job.repositoryRootPath)
        assertEquals(DEV_LAKE_SELECTED_WORKTREE, job.worktreePath)
    }

    @Test
    fun multipleWorktreesCanBeQueuedIndependently() = runBlocking {
        val secondPath = "/repos/dev-lake-utils-feature-search"
        val store = RecordingWorktreeArchiveStore()
        val api = archiveTestApi(secondPath)
        val viewModel = archiveViewModel(api, store) { Instant.fromEpochMilliseconds(20_000) }
        expandRepository(viewModel, expectedWorktreeCount = 3)

        viewModel.archiveLocalWorktree(DEV_LAKE_ROOT, DEV_LAKE_SELECTED_WORKTREE)
        viewModel.archiveLocalWorktree(DEV_LAKE_ROOT, secondPath)

        val jobs = withTimeout(2_000.milliseconds) {
            viewModel.queuedWorktreeArchivesStateFlow.first { it.size == 2 }
        }
        assertEquals(setOf("feature/login", "feature/search"), jobs.mapTo(mutableSetOf()) { it.branch })
        assertEquals(emptyList(), api.archiveWorktreeCalls)
    }

    @Test
    fun persistenceFailureReportsErrorAndDoesNotHideWorktree() = runBlocking {
        val store = RecordingWorktreeArchiveStore(IllegalStateException("disk full"))
        val api = archiveTestApi()
        val viewModel = archiveViewModel(api, store) { Instant.fromEpochMilliseconds(30_000) }
        expandRepository(viewModel)

        viewModel.archiveLocalWorktree(DEV_LAKE_ROOT, DEV_LAKE_SELECTED_WORKTREE)

        val error = withTimeout(2_000.milliseconds) {
            viewModel.actionErrorStateFlow.first { it != null }
        }
        assertEquals("Failed to queue worktree archive: disk full", error?.message)
        assertEquals(emptyList(), viewModel.queuedWorktreeArchivesStateFlow.value)
        assertEquals(
            listOf(DEV_LAKE_ROOT, DEV_LAKE_SELECTED_WORKTREE),
            viewModel.localRepositoriesStateFlow.value.single().worktrees.map { it.path },
        )
        assertEquals(emptyList(), api.archiveWorktreeCalls)
    }

    @Test
    fun queuedWorktreeKeepsMutationGuard() = runBlocking {
        val api = archiveTestApi()
        val viewModel = archiveViewModel(api, RecordingWorktreeArchiveStore()) {
            Instant.fromEpochMilliseconds(40_000)
        }
        expandRepository(viewModel)
        viewModel.archiveLocalWorktree(DEV_LAKE_ROOT, DEV_LAKE_SELECTED_WORKTREE)
        withTimeout(2_000.milliseconds) {
            viewModel.queuedWorktreeArchivesStateFlow.first { it.isNotEmpty() }
        }

        viewModel.updateLocalWorktreeFromOrigin(DEV_LAKE_ROOT, DEV_LAKE_SELECTED_WORKTREE, "feature/login")

        assertEquals(emptyList(), api.updateWorktreeFromOriginCalls)
        assertEquals(emptyList(), api.archiveWorktreeCalls)
    }

    @Test
    fun undoQueuedArchiveRestoresOnlySelectedWorktreeWithoutRunningGitRemoval() = runBlocking {
        val secondPath = "/repos/dev-lake-utils-feature-search"
        val store = RecordingWorktreeArchiveStore()
        val api = archiveTestApi(secondPath)
        val viewModel = archiveViewModel(api, store) { Instant.fromEpochMilliseconds(50_000) }
        expandRepository(viewModel, expectedWorktreeCount = 3)
        viewModel.archiveLocalWorktree(DEV_LAKE_ROOT, DEV_LAKE_SELECTED_WORKTREE)
        viewModel.archiveLocalWorktree(DEV_LAKE_ROOT, secondPath)
        withTimeout(2_000.milliseconds) {
            viewModel.queuedWorktreeArchivesStateFlow.first { it.size == 2 }
        }

        viewModel.undoQueuedWorktreeArchive(DEV_LAKE_SELECTED_WORKTREE)

        val remainingJobs = withTimeout(2_000.milliseconds) {
            viewModel.queuedWorktreeArchivesStateFlow.first { jobs ->
                jobs.size == 1 && jobs.single().worktreePath == secondPath
            }
        }
        assertEquals(listOf(secondPath), remainingJobs.map { it.worktreePath })
        assertEquals(listOf(secondPath), store.listJobs().map { it.worktreePath })
        assertEquals(
            listOf(DEV_LAKE_ROOT, DEV_LAKE_SELECTED_WORKTREE),
            visibleWorktreeRows(
                worktrees = viewModel.localRepositoriesStateFlow.value.single().worktrees,
                hiddenPaths = remainingJobs.mapTo(mutableSetOf()) { it.worktreePath },
            ).map { it.worktree.path },
        )
        assertEquals(emptyList(), api.archiveWorktreeCalls)

        viewModel.archiveLocalWorktree(DEV_LAKE_ROOT, DEV_LAKE_SELECTED_WORKTREE)
        withTimeout(2_000.milliseconds) {
            viewModel.queuedWorktreeArchivesStateFlow.first { jobs ->
                jobs.size == 2 && jobs.any { it.worktreePath == DEV_LAKE_SELECTED_WORKTREE }
            }
        }
        assertEquals(emptyList(), api.archiveWorktreeCalls)
    }

    @Test
    fun undoDoesNothingWhenPersistedArchiveIsNoLongerQueued() = runBlocking {
        val store = RecordingWorktreeArchiveStore()
        val api = archiveTestApi()
        val viewModel = archiveViewModel(api, store) { Instant.fromEpochMilliseconds(60_000) }
        expandRepository(viewModel)
        viewModel.archiveLocalWorktree(DEV_LAKE_ROOT, DEV_LAKE_SELECTED_WORKTREE)
        val queuedJob = withTimeout(2_000.milliseconds) {
            viewModel.queuedWorktreeArchivesStateFlow.first { it.isNotEmpty() }.single()
        }
        store.jobs.value = listOf(queuedJob.copy(state = WorktreeArchiveLifecycleState.REMOVING))

        viewModel.undoQueuedWorktreeArchive(DEV_LAKE_SELECTED_WORKTREE)
        withTimeout(2_000.milliseconds) {
            store.deleteQueuedJobCalls.first { it == listOf(DEV_LAKE_SELECTED_WORKTREE) }
        }
        viewModel.updateLocalWorktreeFromOrigin(DEV_LAKE_ROOT, DEV_LAKE_SELECTED_WORKTREE, "feature/login")

        assertEquals(listOf(queuedJob), viewModel.queuedWorktreeArchivesStateFlow.value)
        assertEquals(WorktreeArchiveLifecycleState.REMOVING, store.listJobs().single().state)
        assertEquals(emptyList(), api.updateWorktreeFromOriginCalls)
        assertEquals(emptyList(), api.archiveWorktreeCalls)
    }
}

private fun archiveViewModel(
    api: RecordingGitWorktreeApi,
    store: RecordingWorktreeArchiveStore,
    now: () -> Instant,
): EngHubViewModel = createLocalRepositoryViewModel(
    gitWorktreeApi = api,
    configWriter = RecordingEngHubConfigWriter(),
    localRepositoryConfigs = localRepositoryConfigs(DEV_LAKE_ROOT),
    services = LocalRepositoryViewModelServices(
        worktreeArchiveStore = store,
        archiveNow = now,
    ),
)

private fun archiveTestApi(secondPath: String? = null): RecordingGitWorktreeApi {
    val worktrees = buildList {
        add(Worktree(path = DEV_LAKE_ROOT, branch = "main", commitHash = "abc123"))
        add(Worktree(path = DEV_LAKE_SELECTED_WORKTREE, branch = "feature/login", commitHash = "def456"))
        secondPath?.let { path ->
            add(Worktree(path = path, branch = "feature/search", commitHash = "789abc"))
        }
    }
    return RecordingGitWorktreeApi(worktreesByRepoPath = mapOf(DEV_LAKE_ROOT to worktrees))
}

private suspend fun expandRepository(viewModel: EngHubViewModel, expectedWorktreeCount: Int = 2) {
    viewModel.toggleLocalRepositoryExpansion(DEV_LAKE_ROOT)
    withTimeout(2_000.milliseconds) {
        viewModel.localRepositoriesStateFlow.first { it.single().worktrees.size == expectedWorktreeCount }
    }
}
