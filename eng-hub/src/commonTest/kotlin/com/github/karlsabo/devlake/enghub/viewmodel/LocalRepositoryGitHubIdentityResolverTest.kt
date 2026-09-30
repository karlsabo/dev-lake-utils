package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.EngHubConfig
import com.github.karlsabo.devlake.enghub.normalizedRepositoryPath
import com.github.karlsabo.git.GitWorktreeApi
import com.github.karlsabo.git.WorktreeSetupCoordinator
import com.github.karlsabo.github.parseGitHubRepositoryIdentity
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LocalRepositoryGitHubIdentityResolverTest {
    @Test
    fun currentExpansionAndRefreshCanStoreOriginIdentity() {
        val state = identityState()
        val expansion = LocalRepositoryExpansionTracker(state)
        val resolver = identityResolver(state) { CURRENT_ORIGIN }
        val expansionRequest = assertNotNull(expansion.start(DEV_LAKE_ROOT))
        resolver.resolveAndStore(DEV_LAKE_ROOT, DEV_LAKE_ROOT, expansionRequest)
        assertEquals(
            parseGitHubRepositoryIdentity(CURRENT_ORIGIN),
            state.localRepositories.value.single().repositoryIdentity,
        )

        val refreshRequest = assertNotNull(LocalRepositoryRefreshTracker(state).start(DEV_LAKE_ROOT))
        identityResolver(state) { null }.resolveAndStore(DEV_LAKE_ROOT, DEV_LAKE_ROOT, refreshRequest)
        assertNull(state.localRepositories.value.single().repositoryIdentity)
    }

    @Test
    fun oldOriginCannotOverwriteNewRefreshIdentity() {
        val state = identityState()
        val tracker = LocalRepositoryRefreshTracker(state)
        val oldRequest = assertNotNull(tracker.start(DEV_LAKE_ROOT))
        val resolver = identityResolver(state) {
            val newRequest = assertNotNull(tracker.start(DEV_LAKE_ROOT))
            identityResolver(state) { CURRENT_ORIGIN }.resolveAndStore(DEV_LAKE_ROOT, DEV_LAKE_ROOT, newRequest)
            OLD_ORIGIN
        }

        resolver.resolveAndStore(DEV_LAKE_ROOT, DEV_LAKE_ROOT, oldRequest)

        assertEquals(
            parseGitHubRepositoryIdentity(CURRENT_ORIGIN),
            state.localRepositories.value.single().repositoryIdentity,
        )
    }

    @Test
    fun refreshInvalidatesInFlightExpansionOrigin() {
        val state = identityState()
        val request = assertNotNull(LocalRepositoryExpansionTracker(state).start(DEV_LAKE_ROOT))
        val resolver = identityResolver(state) {
            assertNotNull(LocalRepositoryRefreshTracker(state).start(DEV_LAKE_ROOT))
            OLD_ORIGIN
        }

        resolver.resolveAndStore(DEV_LAKE_ROOT, DEV_LAKE_ROOT, request)

        assertNull(state.localRepositories.value.single().repositoryIdentity)
    }

    @Test
    fun collapseInvalidatesInFlightOrigin() {
        val state = identityState()
        val tracker = LocalRepositoryExpansionTracker(state)
        val request = assertNotNull(tracker.start(DEV_LAKE_ROOT))
        val resolver = identityResolver(state) {
            tracker.collapse(DEV_LAKE_ROOT)
            OLD_ORIGIN
        }

        resolver.resolveAndStore(DEV_LAKE_ROOT, DEV_LAKE_ROOT, request)

        assertNull(state.localRepositories.value.single().repositoryIdentity)
    }

    @Test
    fun removeAndReaddAtSamePathInvalidatesInFlightOrigin() {
        val state = identityState()
        val request = assertNotNull(LocalRepositoryRefreshTracker(state).start(DEV_LAKE_ROOT))
        val resolver = identityResolver(state) {
            runBlocking {
                state.updateConfig { it.copy(localRepositories = emptyList()) }
                state.updateConfig { it.copy(localRepositories = localRepositoryConfigs(DEV_LAKE_ROOT)) }
            }
            OLD_ORIGIN
        }

        resolver.resolveAndStore(DEV_LAKE_ROOT, DEV_LAKE_ROOT, request)

        assertNull(state.localRepositories.value.single().repositoryIdentity)
    }
}

private fun identityResolver(
    state: EngHubViewModelState,
    readOrigin: () -> String?,
) = LocalRepositoryGitHubIdentityResolver(
    state = state,
    // Ownership changes inside originUrl place them deterministically between read and commit.
    gitWorktreeApi = object : GitWorktreeApi by RecordingGitWorktreeApi() {
        override fun originUrl(repoPath: String): String? = readOrigin()
    },
    repositoryIdentity = { it.normalizedRepositoryPath() },
)

private fun identityState() = EngHubViewModelState(
    config = EngHubConfig(localRepositories = localRepositoryConfigs(DEV_LAKE_ROOT)),
    configWriter = RecordingEngHubConfigWriter(),
    worktreeSetupCoordinator = WorktreeSetupCoordinator(gitWorktreeApi = RecordingGitWorktreeApi()),
    notificationIgnoreStore = NoOpNotificationIgnoreStore(),
)

private const val OLD_ORIGIN = "git@github.com:old/login.git"
private const val CURRENT_ORIGIN = "git@github.com:current/logout.git"
