package com.github.karlsabo.devlake.enghub.screen

import com.github.karlsabo.devlake.enghub.state.EngHubSettingsUiState
import com.github.karlsabo.devlake.enghub.state.SettingsLocalRepositoryUiState

internal const val SEARCH_SETTINGS_LABEL = "Search settings"
internal const val CLEAR_SEARCH_LABEL = "Clear search"
internal const val NO_MATCHING_SETTINGS_LABEL = "No settings found"
internal const val VALIDATION_ERROR_SUFFIX = "has validation error"

internal const val SECRET_FILE_PATH_LABEL = "Secret file path"
internal const val GITHUB_TOKEN_LABEL = "GitHub token"
internal const val ORGANIZATION_IDS_LABEL = "Organization IDs"
internal const val NEW_ORGANIZATION_ID_LABEL = "New organization ID"
internal const val GITHUB_AUTHOR_LABEL = "GitHub author"
internal const val ACTIVITY_POLL_INTERVAL_LABEL = "Activity polling interval (seconds)"
internal const val REPOSITORIES_LABEL = "Repositories"
internal const val REPOSITORIES_BASE_DIR_LABEL = "Repositories base directory"
internal const val WORKTREE_POLL_INTERVAL_LABEL = "Worktree polling interval (seconds)"
internal const val REPOSITORY_PATH_LABEL = "Repository path"
internal const val SETUP_COMMANDS_LABEL = "Setup commands"
internal const val ALERT_TRIAGE_WHERE_TO_LOOK_LABEL = "Alert triage: Where to look"
internal const val PLANNING_MARKDOWN_DIR_LABEL = "Planning markdown directory"
internal const val SETUP_SHELL_LABEL = "Setup shell"

/**
 * Discoverable settings metadata. Labels are shared with the rendered form fields so search cannot silently
 * diverge from the form; descriptions and aliases only add search vocabulary.
 */
internal enum class EngHubSettingGroupId(
    val section: EngHubSettingsSection,
    val label: String,
    val description: String,
    val aliases: List<String> = emptyList(),
) {
    SecretFilePath(
        section = EngHubSettingsSection.GitHubAccess,
        label = SECRET_FILE_PATH_LABEL,
        description = "File that stores the GitHub secret",
        aliases = listOf("credential", "secret"),
    ),
    GitHubToken(
        section = EngHubSettingsSection.GitHubAccess,
        label = GITHUB_TOKEN_LABEL,
        description = "GitHub personal access token",
        aliases = listOf("credential", "pat"),
    ),
    OrganizationIds(
        section = EngHubSettingsSection.GitHubActivity,
        label = ORGANIZATION_IDS_LABEL,
        description = "Organizations to read activity from",
        aliases = listOf("org", "organizations"),
    ),
    GitHubAuthor(
        section = EngHubSettingsSection.GitHubActivity,
        label = GITHUB_AUTHOR_LABEL,
        description = "Login used for authored pull requests",
        aliases = listOf("user", "login"),
    ),
    ActivityPollInterval(
        section = EngHubSettingsSection.GitHubActivity,
        label = ACTIVITY_POLL_INTERVAL_LABEL,
        description = "How often pull requests and notifications refresh",
        aliases = listOf("refresh", "poll", "frequency"),
    ),
    RepositoriesBaseDirectory(
        section = EngHubSettingsSection.Repositories,
        label = REPOSITORIES_BASE_DIR_LABEL,
        description = "Directory that holds repositories",
        aliases = listOf("workspace", "folder"),
    ),
    WorktreePollInterval(
        section = EngHubSettingsSection.Repositories,
        label = WORKTREE_POLL_INTERVAL_LABEL,
        description = "How often worktrees refresh",
        aliases = listOf("refresh", "poll", "frequency"),
    ),
    Repositories(
        section = EngHubSettingsSection.Repositories,
        label = REPOSITORIES_LABEL,
        description = "Choose and configure a repository",
        aliases = listOf("repo", "repository", "repository settings"),
    ),
    RepositorySetupCommands(
        section = EngHubSettingsSection.Repositories,
        label = SETUP_COMMANDS_LABEL,
        description = "Commands that prepare a worktree",
        aliases = listOf("bootstrap", "prepare", "command"),
    ),
    AlertTriageWhereToLook(
        section = EngHubSettingsSection.LlmSkillTemplates,
        label = ALERT_TRIAGE_WHERE_TO_LOOK_LABEL,
        description = "Markdown guidance for the alert triage skill",
        aliases = listOf("markdown", "template", "llm", "skill"),
    ),
    PlanningMarkdownDirectory(
        section = EngHubSettingsSection.PlanningAndSetup,
        label = PLANNING_MARKDOWN_DIR_LABEL,
        description = "Directory that holds plan markdown",
        aliases = listOf("plans", "docs"),
    ),
    SetupShell(
        section = EngHubSettingsSection.PlanningAndSetup,
        label = SETUP_SHELL_LABEL,
        description = "Shell used to run setup commands",
        aliases = listOf("bash", "terminal"),
    ),
}

internal fun EngHubSettingGroupId.matchesQuery(query: String): Boolean {
    val trimmedQuery = query.trim()
    if (trimmedQuery.isEmpty()) return false
    return (listOf(label, description) + aliases).any { candidate ->
        candidate.contains(trimmedQuery, ignoreCase = true)
    }
}

internal data class EngHubRepositoryChoice(
    val id: Long,
    val path: String,
    val name: String,
    val hasValidationError: Boolean,
)

internal data class EngHubRepositoryChooser(
    val choices: List<EngHubRepositoryChoice>,
    val selectedRepositoryId: Long?,
    val selectedRepository: SettingsLocalRepositoryUiState?,
    val isVisible: Boolean,
)

internal data class SettingsSectionView(
    val section: EngHubSettingsSection,
    val groups: List<EngHubSettingGroupId>,
    val repositoryChooser: EngHubRepositoryChooser? = null,
    val repositoryEditor: RepositoryEditorView? = null,
)

/** The single mounted repository editor. Unselected repositories never mount editable controls. */
internal data class RepositoryEditorView(
    val repositoryIndex: Int,
    val repository: SettingsLocalRepositoryUiState,
    val showPath: Boolean,
    val showSetupCommands: Boolean,
)

internal data class SettingsRenderPlan(
    val sections: List<SettingsSectionView>,
) {
    /** True only for a nonempty query that matches no setting group and no repository. */
    val noMatches: Boolean
        get() = sections.isEmpty()
}

internal fun settingsRenderPlan(
    state: EngHubSettingsUiState,
    navigation: EngHubSettingsNavigationState,
): SettingsRenderPlan {
    val query = navigation.query
    if (query.isBlank()) {
        val section = navigation.section
        val chooser = if (section == EngHubSettingsSection.Repositories) {
            repositoryChooser(state, navigation, query = "")
        } else {
            null
        }
        return SettingsRenderPlan(
            listOf(
                SettingsSectionView(
                    section = section,
                    groups = settingGroupsOf(section),
                    repositoryChooser = chooser,
                    repositoryEditor = if (section == EngHubSettingsSection.Repositories) {
                        repositoryEditor(state, navigation, chooser, settingGroupsOf(section), query = "")
                    } else {
                        null
                    },
                ),
            ),
        )
    }

    val sections = EngHubSettingsSection.entries.mapNotNull { section ->
        val groups = settingGroupsOf(section).filter { group -> group.matchesQuery(query) }
        val chooser = if (section == EngHubSettingsSection.Repositories) {
            repositoryChooser(state, navigation, query)
        } else {
            null
        }
        val visibleChooser = chooser?.takeIf { it.isVisible }
        val editor = if (section == EngHubSettingsSection.Repositories) {
            repositoryEditor(state, navigation, visibleChooser, groups, query)
        } else {
            null
        }
        if (groups.isEmpty() && visibleChooser == null && editor == null) {
            null
        } else {
            SettingsSectionView(section, groups, visibleChooser, editor)
        }
    }
    return SettingsRenderPlan(sections)
}

internal fun settingGroupsOf(section: EngHubSettingsSection) = EngHubSettingGroupId.entries.filter { group ->
    group.section == section
}

/**
 * Repository choices follow the global query. The selected editor stays mounted only while it is intentionally
 * chosen during a search or when the query still describes it, so unrelated filtering cannot surface an editor.
 */
private fun repositoryChooser(
    state: EngHubSettingsUiState,
    navigation: EngHubSettingsNavigationState,
    query: String,
): EngHubRepositoryChooser {
    val allChoices = state.repositoryChoices()
    val trimmedQuery = query.trim()
    val matchingChoices = if (trimmedQuery.isEmpty()) {
        allChoices
    } else {
        allChoices.filter { choice -> choice.matchesQuery(trimmedQuery) }
    }
    val groupMatches = trimmedQuery.isEmpty() || EngHubSettingGroupId.Repositories.matchesQuery(trimmedQuery)
    val choices = if (groupMatches) allChoices else matchingChoices
    val selectedId = navigation.selectedRepositoryId?.takeIf { id -> allChoices.any { it.id == id } }
    val selectedRepository = state.localRepositories.firstOrNull { repository -> repository.id == selectedId }
    val editorIsRelevant = trimmedQuery.isEmpty() || groupMatches || navigation.repositorySelectedDuringSearch ||
        matchingChoices.any { choice -> choice.id == selectedId }
    return EngHubRepositoryChooser(
        choices = choices,
        selectedRepositoryId = selectedId,
        selectedRepository = selectedRepository.takeIf { editorIsRelevant },
        isVisible = groupMatches || matchingChoices.isNotEmpty(),
    )
}

internal fun EngHubSettingsUiState.repositoryChoices() = localRepositories.map { repository ->
    EngHubRepositoryChoice(
        id = repository.id,
        path = repository.path,
        name = repositoryBasename(repository.path),
        hasValidationError = repository.hasValidationError,
    )
}

internal fun EngHubRepositoryChoice.matchesQuery(query: String): Boolean {
    val trimmedQuery = query.trim()
    if (trimmedQuery.isEmpty()) return false
    return name.contains(trimmedQuery, ignoreCase = true) || path.contains(trimmedQuery, ignoreCase = true)
}

internal fun repositoryBasename(path: String): String {
    val trimmedPath = path.trimEnd('/', '\\')
    val separatorIndex = trimmedPath.indexOfLast { character -> character == '/' || character == '\\' }
    val basename = if (separatorIndex >= 0) trimmedPath.substring(separatorIndex + 1) else trimmedPath
    return basename.ifEmpty { path }
}

internal val SettingsLocalRepositoryUiState.hasValidationError: Boolean
    get() = pathError != null || setupCommandError != null || setupCommandEditErrors.isNotEmpty()

internal fun EngHubRepositoryChoice.accessibleLabel(): String = if (hasValidationError) {
    "$name, $path, $VALIDATION_ERROR_SUFFIX"
} else {
    "$name, $path"
}

private fun repositoryEditor(
    state: EngHubSettingsUiState,
    navigation: EngHubSettingsNavigationState,
    chooser: EngHubRepositoryChooser?,
    groups: List<EngHubSettingGroupId>,
    query: String,
): RepositoryEditorView? {
    val selectedRepository = state.localRepositories.firstOrNull { repository ->
        repository.id == navigation.selectedRepositoryId
    } ?: return null
    val repositoryIndex = state.localRepositories.indexOf(selectedRepository)
    val showPath = chooser?.selectedRepository != null
    val showSetupCommands = query.isBlank() || EngHubSettingGroupId.RepositorySetupCommands in groups
    return if (showPath || showSetupCommands) {
        RepositoryEditorView(repositoryIndex, selectedRepository, showPath, showSetupCommands)
    } else {
        null
    }
}
