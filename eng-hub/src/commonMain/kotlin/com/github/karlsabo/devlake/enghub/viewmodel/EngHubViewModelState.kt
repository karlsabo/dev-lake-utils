@file:OptIn(ExperimentalForInheritanceCoroutinesApi::class)

package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.EngHubConfig
import com.github.karlsabo.devlake.enghub.EngHubConfigWriter
import com.github.karlsabo.devlake.enghub.normalizedRepositoryPath
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    val baseCommitIsh: String? = null,
    val allowUnrelatedExistingBranch: Boolean = false,
)

internal data class UseUnrelatedExistingBranchConfirmationRequest(
    val repoRootPath: String,
    val baseWorktreePath: String,
    val baseBranch: String,
    val targetBranch: String,
)

internal data class RebaseConflictResolutionRequest(
    val repoRootPath: String,
    val worktreePath: String,
    val parentBranch: String,
)

internal data class ExistingPullRequestWorktreeCandidate(
    val branch: String,
    val repositoryFullName: String,
    val number: Int,
)

internal data class ExistingBranchDiscoveryState(
    val repoRootPath: String = "",
    val branches: List<String> = emptyList(),
    val originBranches: List<String> = emptyList(),
    val originBranchRefreshSucceeded: Boolean? = null,
    val worktreePathsByBranch: Map<String, String> = emptyMap(),
    val isLoading: Boolean = false,
    val requestId: Long = 0,
    val pullRequestQuery: String = "",
    val pullRequest: ExistingPullRequestWorktreeCandidate? = null,
    val isPullRequestLoading: Boolean = false,
    val pullRequestRequestId: Long = 0,
    val unsupportedPullRequestMessage: String? = null,
)

internal data class GlobalExistingBranchDiscoveryState(
    val repoRootPaths: List<String> = emptyList(),
    val repositories: Map<String, ExistingBranchDiscoveryState> = emptyMap(),
    val isLoading: Boolean = false,
    val requestId: Long = 0,
    val pullRequestRequestId: Long = 0,
)

internal data class CreateLocalWorktreeFromRepositoryRequest(
    val repoRootPath: String,
    val baseWorktreePath: String,
    val baseBranch: String,
)

internal class EngHubViewModelState(
    config: EngHubConfig,
    configWriter: EngHubConfigWriter,
    worktreeSetupCoordinator: WorktreeSetupCoordinator,
    notificationIgnoreStore: NotificationIgnoreStore,
) {
    private val configState = EngHubConfigState(config, configWriter)
    private val configUpdateMutex = Mutex()

    val currentConfig: EngHubConfig
        get() = configState.current
    val config: StateFlow<EngHubConfig> = configState.config

    suspend fun updateConfig(transform: (EngHubConfig) -> EngHubConfig): EngHubConfig = configUpdateMutex.withLock {
        configState.update(transform).also(::refreshLocalRepositories)
    }

    val actionErrors = MutableStateFlow(ActionErrorQueueState())
    val reportedSetupFailureHandlesByPath =
        MutableStateFlow<Map<WorktreePath, List<WorktreeSetupHandle>>>(emptyMap())

    val setupStatusesStateFlow: StateFlow<Map<WorktreePath, WorktreeSetupStatus>> =
        worktreeSetupCoordinator.statuses

    val localRepositories = MutableStateFlow(config.localRepositories.toLocalRepositoryUiStates())
    val lastCreateLocalWorktreeFromBaseRequest =
        MutableStateFlow<CreateLocalWorktreeFromBaseRequest?>(null)
    val lastCreateLocalWorktreeFromRepositoryRequest =
        MutableStateFlow<CreateLocalWorktreeFromRepositoryRequest?>(null)
    val existingBranchDiscovery = MutableStateFlow(ExistingBranchDiscoveryState())
    val globalExistingBranchDiscovery = MutableStateFlow(GlobalExistingBranchDiscoveryState())
    val useUnrelatedExistingBranchConfirmationRequest =
        MutableStateFlow<UseUnrelatedExistingBranchConfirmationRequest?>(null)
    val rebaseConflictResolutionRequests =
        MutableStateFlow<List<RebaseConflictResolutionRequest>>(emptyList())
    val archivingLocalWorktreePaths = MutableStateFlow<Set<String>>(emptySet())
    val rebasingLocalWorktreePaths = MutableStateFlow<Set<String>>(emptySet())
    val forceArchiveWorktreeRequest = MutableStateFlow<ForceArchiveWorktreeUiState?>(null)
    val actingOnThreadIds = MutableStateFlow<Set<String>>(emptySet())
    val ignoredThreads = MutableStateFlow(loadIgnoredThreads(notificationIgnoreStore))

    private fun refreshLocalRepositories(config: EngHubConfig) {
        val previousByPath = localRepositories.value.associateBy { it.path.normalizedRepositoryPath() }
        localRepositories.value = config.localRepositories.toLocalRepositoryUiStates().map { configuredRepository ->
            previousByPath[configuredRepository.path.normalizedRepositoryPath()]?.copy(
                name = configuredRepository.name,
                path = configuredRepository.path,
            ) ?: configuredRepository
        }
    }
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

internal class MappedStateFlow<T, R>(
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
