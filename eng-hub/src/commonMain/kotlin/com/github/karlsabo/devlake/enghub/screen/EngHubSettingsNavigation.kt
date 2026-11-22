package com.github.karlsabo.devlake.enghub.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.github.karlsabo.devlake.enghub.state.EngHubSettingsUiState
import com.github.karlsabo.devlake.enghub.state.SettingsLocalRepositoryUiState

internal enum class EngHubSettingsSection(
    val label: String,
) {
    GitHubAccess("GitHub access"),
    GitLabAccess("GitLab access"),
    GitHubActivity("GitHub activity"),
    Repositories("Repositories"),
    LlmSkillTemplates("LLM skill templates"),
    PlanningAndSetup("Planning and setup"),
}

/** Destination that an empty query restores when the user did not explicitly navigate during the search. */
internal data class SettingsDestination(
    val section: EngHubSettingsSection,
    val repositoryId: Long?,
)

internal data class EngHubSettingsNavigationState(
    val repositoryIds: List<Long> = emptyList(),
    val section: EngHubSettingsSection = EngHubSettingsSection.GitHubAccess,
    val selectedRepositoryId: Long? = null,
    val query: String = "",
    val restoreDestination: SettingsDestination? = null,
    val repositorySelectedDuringSearch: Boolean = false,
    val pendingRepositorySelection: PendingRepositorySelection? = null,
)

/** A selection requested before navigation observes the repository list that contains it. */
internal sealed interface PendingRepositorySelection {
    data class ById(
        val repositoryId: Long,
    ) : PendingRepositorySelection

    data class ByPath(
        val path: String,
    ) : PendingRepositorySelection
}

private fun PendingRepositorySelection.resolvedRepositoryId(
    repositories: List<SettingsLocalRepositoryUiState>,
): Long? = when (this) {
    is PendingRepositorySelection.ById -> repositoryId.takeIf { id ->
        repositories.any { repository -> repository.id == id }
    }

    is PendingRepositorySelection.ByPath -> repositories.firstOrNull { repository -> repository.path == path }?.id
}

internal fun initialSettingsNavigationState(state: EngHubSettingsUiState): EngHubSettingsNavigationState {
    val repositoryIds = state.localRepositories.map { repository -> repository.id }
    return EngHubSettingsNavigationState(
        repositoryIds = repositoryIds,
        section = if (state.gitHubAccessReady) {
            EngHubSettingsSection.Repositories
        } else {
            EngHubSettingsSection.GitHubAccess
        },
        selectedRepositoryId = repositoryIds.firstOrNull(),
    )
}

/**
 * Keeps the selection on a session repository identity. A removed selection falls back to the next remaining
 * repository, then the previous one, then the empty state.
 */
internal fun EngHubSettingsNavigationState.withRepositoryIds(
    repositoryIds: List<Long>,
): EngHubSettingsNavigationState {
    val selectedRepositoryId = selectedRepositoryId
    val resolvedSelection = when {
        repositoryIds.isEmpty() -> null

        selectedRepositoryId == null -> repositoryIds.first()

        selectedRepositoryId in repositoryIds -> selectedRepositoryId

        else -> repositoryIds.getOrNull(this.repositoryIds.indexOf(selectedRepositoryId).coerceAtLeast(0))
            ?: repositoryIds.last()
    }
    return copy(repositoryIds = repositoryIds, selectedRepositoryId = resolvedSelection)
}

internal fun EngHubSettingsNavigationState.withQuery(query: String): EngHubSettingsNavigationState = when {
    query == this.query -> this

    query.isBlank() -> clearedQuery()

    this.query.isBlank() -> copy(
        query = query,
        restoreDestination = SettingsDestination(section, selectedRepositoryId),
        repositorySelectedDuringSearch = false,
    )

    else -> copy(query = query)
}

internal fun EngHubSettingsNavigationState.clearedQuery(): EngHubSettingsNavigationState {
    if (query.isEmpty()) return this
    val restoredRepositoryId = restoreDestination?.repositoryId?.takeIf { id -> id in repositoryIds }
    return copy(
        query = "",
        section = restoreDestination?.section ?: section,
        selectedRepositoryId = restoredRepositoryId ?: selectedRepositoryId,
        restoreDestination = null,
        repositorySelectedDuringSearch = false,
    )
}

/**
 * Choosing a section ends an active search and cancels an unresolved selection request. Both are explicit choices that
 * win over restoring the pre-search view or resolving a pending one.
 */
internal fun EngHubSettingsNavigationState.withSection(
    section: EngHubSettingsSection,
): EngHubSettingsNavigationState = if (query.isBlank()) {
    copy(section = section, pendingRepositorySelection = null)
} else {
    copy(
        section = section,
        query = "",
        restoreDestination = null,
        repositorySelectedDuringSearch = false,
        pendingRepositorySelection = null,
    )
}

/**
 * Choosing a repository during a search makes Repositories and that identity the destination restored when the
 * query clears, so the choice survives clearing even when the search started from another section.
 */
internal fun EngHubSettingsNavigationState.withSelectedRepository(
    repositoryId: Long?,
): EngHubSettingsNavigationState = when {
    repositoryId != null && repositoryId !in repositoryIds -> this

    query.isBlank() -> copy(selectedRepositoryId = repositoryId)

    else -> copy(
        selectedRepositoryId = repositoryId,
        restoreDestination = repositoryId?.let { id -> SettingsDestination(EngHubSettingsSection.Repositories, id) },
        repositorySelectedDuringSearch = true,
    )
}

internal fun EngHubSettingsUiState.sectionsWithValidationErrors(): Set<EngHubSettingsSection> = buildSet {
    if (gitHubTokenPathError != null || gitHubTokenError != null) add(EngHubSettingsSection.GitHubAccess)
    if (gitLabInstanceUrlError != null || gitLabTokenPathError != null || gitLabTokenError != null) {
        add(EngHubSettingsSection.GitLabAccess)
    }
    if (organizationIdError != null || pollIntervalError != null) add(EngHubSettingsSection.GitHubActivity)
    if (worktreePollIntervalError != null || localRepositoryError != null ||
        localRepositories.any { repository -> repository.hasValidationError }
    ) {
        add(EngHubSettingsSection.Repositories)
    }
}

internal fun EngHubSettingsSection.accessibleLabel(hasValidationError: Boolean): String = if (hasValidationError) {
    "$label, $VALIDATION_ERROR_SUFFIX"
} else {
    label
}

@Stable
internal class EngHubSettingsNavigation(
    initialState: EngHubSettingsNavigationState,
) {
    var state: EngHubSettingsNavigationState by mutableStateOf(initialState)
        private set

    fun selectSection(section: EngHubSettingsSection) {
        state = state.withSection(section)
    }

    /**
     * Choosing a repository explicitly supersedes any request that has not resolved yet, so a late restoration
     * cannot override the selection the user just made.
     */
    fun selectRepository(repositoryId: Long?) {
        state = state.withSelectedRepository(repositoryId).copy(pendingRepositorySelection = null)
    }

    fun updateQuery(query: String) {
        state = state.withQuery(query)
    }

    fun clearQuery() {
        state = state.clearedQuery()
    }

    /** Defers the selection until the next [reconcileRepositories] observes the new repository id. */
    fun requestRepositorySelection(repositoryId: Long) {
        state = state.copy(pendingRepositorySelection = PendingRepositorySelection.ById(repositoryId))
    }

    fun requestRepositorySelectionByPath(path: String) {
        state = state.copy(pendingRepositorySelection = PendingRepositorySelection.ByPath(path))
    }

    /**
     * Retains a request whose target is still missing while [restorationInFlight], so a reconciliation that runs
     * before the restored entry arrives cannot discard the selection. A restoration that ends without its target,
     * like an explicit repository choice, cancels the request.
     */
    fun reconcileRepositories(
        repositories: List<SettingsLocalRepositoryUiState>,
        restorationInFlight: Boolean = false,
    ) {
        var reconciled = state.withRepositoryIds(repositories.map { repository -> repository.id })
        val pending = state.pendingRepositorySelection
        val resolvedRepositoryId = pending?.resolvedRepositoryId(repositories)
        if (resolvedRepositoryId != null) {
            reconciled = reconciled.withSelectedRepository(resolvedRepositoryId)
        }
        val retainedPending = pending?.takeIf { resolvedRepositoryId == null && restorationInFlight }
        state = reconciled.copy(pendingRepositorySelection = retainedPending)
    }
}

@Composable
internal fun rememberEngHubSettingsNavigation(state: EngHubSettingsUiState): EngHubSettingsNavigation {
    val navigation = remember { EngHubSettingsNavigation(initialSettingsNavigationState(state)) }
    LaunchedEffect(state.localRepositories, state.restoringLocalRepositoryPath) {
        navigation.reconcileRepositories(
            state.localRepositories,
            restorationInFlight = state.restoringLocalRepositoryPath != null,
        )
    }
    return navigation
}
