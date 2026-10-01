package com.github.karlsabo.devlake.enghub.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import com.github.karlsabo.git.Worktree
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

class EngHubRepositoryDiscoverySchedulingTest {
    @Test
    fun blockedLocalDiscoveryDoesNotBlockAnotherConfiguredRepository() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val api = discoveryApi { path ->
            if (path == DEV_LAKE_ROOT) {
                started.complete(Unit)
                runBlocking { release.await() }
            }
        }
        val viewModel = pollingViewModel(api)
        try {
            withTimeout(2_000.milliseconds) {
                started.await()
                viewModel.localRepositoriesStateFlow.first { repositories ->
                    repositories.single { it.path == DOCS_ROOT }.worktrees.isNotEmpty()
                }
            }
            assertEquals(emptyList(), viewModel.localRepositoriesStateFlow.value.first().worktrees)
        } finally {
            dispose(viewModel)
            release.complete(Unit)
        }
    }

    @Test
    fun configChangeDiscardsBlockedDiscoveryAndItsQueuedPolls() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val returned = CompletableDeferred<Unit>()
        val api = discoveryApi { path ->
            if (path == DEV_LAKE_ROOT) {
                started.complete(Unit)
                runBlocking { release.await() }
                returned.complete(Unit)
            }
        }
        val viewModel = pollingViewModel(api)
        try {
            withTimeout(2_000.milliseconds) { started.await() }
            viewModel.updateConfig { it.copy(worktreePollIntervalMs = 10) }
            delay(100.milliseconds)
            assertEquals(1, api.listWorktreeRepoPaths.count { it == DEV_LAKE_ROOT })
            viewModel.updateConfig { it.copy(localRepositories = localRepositoryConfigs(DOCS_ROOT)) }
            withTimeout(2_000.milliseconds) {
                viewModel.localRepositoriesStateFlow.first { it.size == 1 && it.single().path == DOCS_ROOT }
            }
            release.complete(Unit)
            withTimeout(2_000.milliseconds) { returned.await() }
            delay(100.milliseconds)
            assertEquals(1, api.listWorktreeRepoPaths.count { it == DEV_LAKE_ROOT })
            assertEquals(listOf(DOCS_ROOT), viewModel.localRepositoriesStateFlow.value.map { it.path })
        } finally {
            dispose(viewModel)
            release.complete(Unit)
        }
    }

    @Test
    fun changedConfigRejectsOldDiscoveryForTheSameRepository() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val releaseSecond = CompletableDeferred<Unit>()
        val calls = Channel<() -> Unit>(2).apply {
            trySend {
                firstStarted.complete(Unit)
                runBlocking { releaseFirst.await() }
            }
            trySend {
                secondStarted.complete(Unit)
                runBlocking { releaseSecond.await() }
            }
        }
        val api = discoveryApi { path ->
            if (path == DEV_LAKE_ROOT) calls.tryReceive().getOrThrow().invoke()
        }
        val viewModel = pollingViewModel(api)
        try {
            withTimeout(2_000.milliseconds) { firstStarted.await() }
            viewModel.updateConfig { it.copy(worktreePollIntervalMs = 60_000) }
            delay(100.milliseconds)
            releaseFirst.complete(Unit)
            withTimeout(2_000.milliseconds) { secondStarted.await() }
            assertEquals(emptyList(), viewModel.localRepositoriesStateFlow.value.first().worktrees)
            releaseSecond.complete(Unit)
            withTimeout(2_000.milliseconds) {
                viewModel.localRepositoriesStateFlow.first { it.first().worktrees.isNotEmpty() }
            }
            assertEquals(2, api.listWorktreeRepoPaths.count { it == DEV_LAKE_ROOT })
        } finally {
            dispose(viewModel)
            releaseFirst.complete(Unit)
            releaseSecond.complete(Unit)
        }
    }

    @Test
    fun disposalDiscardsBlockedDiscoveryAndPendingPolls() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val returned = CompletableDeferred<Unit>()
        val api = discoveryApi { path ->
            if (path == DEV_LAKE_ROOT) {
                started.complete(Unit)
                runBlocking { release.await() }
                returned.complete(Unit)
            }
        }
        val viewModel = pollingViewModel(api)
        try {
            withTimeout(2_000.milliseconds) { started.await() }
            viewModel.updateConfig { it.copy(worktreePollIntervalMs = 10) }
            delay(100.milliseconds)
            val beforeDisposal = viewModel.localRepositoriesStateFlow.value
            dispose(viewModel)
            release.complete(Unit)
            withTimeout(2_000.milliseconds) { returned.await() }
            delay(100.milliseconds)
            assertEquals(beforeDisposal, viewModel.localRepositoriesStateFlow.value)
            assertEquals(1, api.listWorktreeRepoPaths.count { it == DEV_LAKE_ROOT })
        } finally {
            dispose(viewModel)
            release.complete(Unit)
        }
    }

    private fun dispose(viewModel: ViewModel) {
        ViewModelStore().also { it.put("repository", viewModel) }.clear()
    }

    private fun discoveryApi(onList: (String) -> Unit) = RecordingGitWorktreeApi(
        responses = RecordingGitWorktreeApiResponses(
            worktreesByRepoPath = mapOf(
                DEV_LAKE_ROOT to listOf(Worktree(DEV_LAKE_ROOT, "main", "abc")),
                DOCS_ROOT to listOf(Worktree(DOCS_ROOT, "feature/nav", "def")),
            ),
        ),
        callbacks = RecordingGitWorktreeApiCallbacks(onListWorktreeEntries = onList),
    )

    private fun pollingViewModel(api: RecordingGitWorktreeApi) = createLocalRepositoryViewModel(
        gitWorktreeApi = api,
        configWriter = RecordingEngHubConfigWriter(),
        localRepositoryConfigs = localRepositoryConfigs(DEV_LAKE_ROOT, DOCS_ROOT),
        testConfig = LocalRepositoryViewModelTestConfig(
            startConfiguredRepositoryPolling = true,
            startConfiguredRepositoriesExpanded = true,
            pollConfiguredRepositoriesImmediately = true,
        ),
    )
}
