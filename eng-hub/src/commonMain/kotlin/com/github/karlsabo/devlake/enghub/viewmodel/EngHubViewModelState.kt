@file:OptIn(ExperimentalForInheritanceCoroutinesApi::class)

package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.EngHubConfig
import com.github.karlsabo.devlake.enghub.state.ForceArchiveWorktreeUiState
import com.github.karlsabo.devlake.enghub.state.toLocalRepositoryUiStates
import com.github.karlsabo.git.WorktreePath
import com.github.karlsabo.git.WorktreeSetupCoordinator
import com.github.karlsabo.git.WorktreeSetupHandle
import com.github.karlsabo.git.WorktreeSetupStatus
import com.github.karlsabo.notifications.NotificationIgnoreStore
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private const val FIRST_ACTION_ERROR_ID = 1L

data class ActionErrorUiState(
    val id: Long,
    val message: String,
)

internal data class CreateLocalWorktreeFromBaseRequest(
    val repoRootPath: String,
    val baseWorktreePath: String,
    val baseBranch: String,
    val targetBranch: String,
)

internal class EngHubViewModelState(
    config: EngHubConfig,
    worktreeSetupCoordinator: WorktreeSetupCoordinator,
    notificationIgnoreStore: NotificationIgnoreStore,
) {
    var currentConfig = config

    val actionErrors = MutableStateFlow(ActionErrorQueueState())
    val reportedSetupFailureHandlesByPath =
        MutableStateFlow<Map<WorktreePath, List<WorktreeSetupHandle>>>(emptyMap())

    val setupStatusesStateFlow: StateFlow<Map<WorktreePath, WorktreeSetupStatus>> =
        worktreeSetupCoordinator.statuses

    val localRepositories = MutableStateFlow(config.localRepositories.toLocalRepositoryUiStates())
    val lastCreateLocalWorktreeFromBaseRequest =
        MutableStateFlow<CreateLocalWorktreeFromBaseRequest?>(null)
    val localRepositoryExpansionsInFlight = MutableStateFlow<Set<String>>(emptySet())
    val archivingLocalWorktreePaths = MutableStateFlow<Set<String>>(emptySet())
    val forceArchiveWorktreeRequest = MutableStateFlow<ForceArchiveWorktreeUiState?>(null)
    val actingOnThreadIds = MutableStateFlow<Set<String>>(emptySet())
    val ignoredThreads = MutableStateFlow(loadIgnoredThreads(notificationIgnoreStore))
}

internal data class ActionErrorQueueState(
    val current: ActionErrorUiState? = null,
    val queuedMessages: List<String> = emptyList(),
    val nextErrorId: Long = FIRST_ACTION_ERROR_ID,
) {
    fun enqueue(message: String): ActionErrorQueueState = if (current == null) {
        withCurrent(message)
    } else {
        copy(queuedMessages = queuedMessages + message)
    }

    fun clearCurrent(): ActionErrorQueueState = queuedMessages.firstOrNull()?.let { nextMessage ->
        copy(queuedMessages = queuedMessages.drop(1)).withCurrent(nextMessage)
    } ?: copy(current = null)

    private fun withCurrent(message: String): ActionErrorQueueState = copy(
        current = ActionErrorUiState(id = nextErrorId, message = message),
        nextErrorId = nextErrorId + 1,
    )
}

internal fun actionErrorState(
    source: StateFlow<ActionErrorQueueState>,
): StateFlow<ActionErrorUiState?> = MappedStateFlow(source) { it.current }

private class MappedStateFlow<T, R>(
    private val source: StateFlow<T>,
    private val transform: (T) -> R,
) : StateFlow<R> {
    override val replayCache: List<R>
        get() = listOf(value)

    override val value: R
        get() = transform(source.value)

    override suspend fun collect(collector: FlowCollector<R>): Nothing {
        source.map(transform).distinctUntilChanged().collect(collector)
        error("StateFlow collection completed unexpectedly")
    }
}
