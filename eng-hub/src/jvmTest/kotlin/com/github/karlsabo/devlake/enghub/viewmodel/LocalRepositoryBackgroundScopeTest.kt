package com.github.karlsabo.devlake.enghub.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.github.karlsabo.devlake.enghub.state.LocalRepositoryWorktreeRequest
import com.github.karlsabo.devlake.enghub.state.LocalWorktreeUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalRepositoryBackgroundScopeTest {
    @Test
    fun disposalCancelsBackgroundImmediatelyDespiteSynchronousDiscoveryChild() = runBlocking {
        val viewModel = object : ViewModel() {}
        val store = ViewModelStore().also { it.put("repository", viewModel) }
        val background = viewModel.localRepositoryBackgroundScope()
        val discoveryStarted = CountDownLatch(1)
        val releaseDiscovery = CountDownLatch(1)
        val lookupStarted = CountDownLatch(1)
        val releaseLookup = CountDownLatch(1)
        val applied = mutableListOf<String>()
        val api = RecordingGitWorktreeApi(
            callbacks = RecordingGitWorktreeApiCallbacks(
                onInferOriginDefaultBranch = {
                    lookupStarted.countDown()
                    check(releaseLookup.await(2, TimeUnit.SECONDS))
                },
            ),
        )
        val scheduler = LocalWorktreeEnrichmentScheduler(background, api)
        val discovery = viewModel.viewModelScope.launch(Dispatchers.IO) {
            discoveryStarted.countDown()
            check(releaseDiscovery.await(2, TimeUnit.SECONDS))
        }
        try {
            assertTrue(discoveryStarted.await(2, TimeUnit.SECONDS))
            scheduler.schedule(DEV_LAKE_ROOT, DEV_LAKE_ROOT, LocalRepositoryWorktreeRequest(), emptyList()) {
                applied += "running"
            }
            assertTrue(lookupStarted.await(2, TimeUnit.SECONDS))
            scheduler.schedule(
                DEV_LAKE_ROOT,
                DEV_LAKE_ROOT,
                LocalRepositoryWorktreeRequest(),
                listOf(LocalWorktreeUiState("main", DEV_LAKE_ROOT)),
            ) { applied += "queued" }

            store.clear()

            assertFalse(discovery.isCompleted, "synchronous discovery still blocks parent completion")
            assertTrue(background.coroutineContext[Job]!!.isCancelled, "disposal must cancel before child completion")
            releaseLookup.countDown()
            withTimeout(2_000) { background.coroutineContext[Job]!!.join() }
            assertEquals(emptyList(), applied)
            assertEquals(1, api.inferOriginDefaultBranchRepoPaths.size)
        } finally {
            store.clear()
            releaseDiscovery.countDown()
            releaseLookup.countDown()
            withTimeout(2_000) { discovery.join() }
        }
    }
}
