package com.github.karlsabo.devlake.enghub.state

import com.github.karlsabo.devlake.enghub.ALERT_TRIAGE_WHERE_TO_LOOK_TEMPLATE_KEY
import com.github.karlsabo.devlake.enghub.EngHubConfig
import com.github.karlsabo.github.config.GitHubConfig
import com.github.karlsabo.github.config.GitHubSecret
import com.github.karlsabo.gitlab.config.GitLabSecret
import com.github.karlsabo.gitlab.config.LoadedGitLabConfig

internal const val MASKED_TOKEN = "••••••••"

class MaskedTokenUiState internal constructor(
    internal val value: String,
    private val draftValue: String? = null,
) {
    val maskedValue: String = if (value.isEmpty()) "" else MASKED_TOKEN
    internal val fieldValue: String = draftValue ?: value

    internal fun withDraft(token: String): MaskedTokenUiState = MaskedTokenUiState(value, token)

    internal fun withCommittedToken(token: String): MaskedTokenUiState = MaskedTokenUiState(token)

    override fun toString(): String = "MaskedTokenUiState()"
}

internal typealias GitHubTokenUiState = MaskedTokenUiState

data class EngHubSettingsUiState(
    val committedConfig: EngHubConfig,
    val organizationIds: List<String>,
    val persistenceError: String? = null,
    val organizationIdDraft: String = "",
    val organizationIdError: String? = null,
    val pollIntervalSeconds: String,
    val pollIntervalError: String? = null,
    val worktreePollIntervalSeconds: String,
    val worktreePollIntervalError: String? = null,
    val repositoriesBaseDir: String,
    val gitHubAuthor: String,
    val planningMarkdownDir: String,
    val alertTriageWhereToLook: String,
    val localRepositories: List<SettingsLocalRepositoryUiState>,
    val localRepositoryDraft: String = "",
    val localRepositoryError: String? = null,
    val removedLocalRepositoryPath: String? = null,
    /** Path whose undo restoration is still being persisted; null when no restoration is in flight. */
    val restoringLocalRepositoryPath: String? = null,
    val setupShell: String,
    val gitHubTokenPath: String,
    val gitHubTokenPathError: String? = null,
    val gitHubToken: MaskedTokenUiState,
    val gitHubTokenError: String? = null,
    val gitHubAccessReady: Boolean,
    val gitLabInstanceUrl: String,
    val gitLabInstanceUrlError: String? = null,
    val gitLabTokenPath: String,
    val gitLabTokenPathError: String? = null,
    val gitLabToken: MaskedTokenUiState,
    val gitLabTokenError: String? = null,
    val gitLabAccessReady: Boolean,
    val gitLabValidationMessage: String? = null,
)

data class SettingsLocalRepositoryUiState(
    /** Session-local identity, stable across path edits; never persisted. */
    val id: Long,
    val path: String,
    val setupCommands: List<String>,
    val pathError: String? = null,
    val setupCommandDraft: String = "",
    val setupCommandError: String? = null,
    val setupCommandEditErrors: Map<Int, String> = emptyMap(),
)

internal fun createEngHubSettingsUiState(
    engHubConfig: EngHubConfig,
    gitHubConfig: GitHubConfig,
    gitHubSecret: GitHubSecret,
    gitLabConfig: LoadedGitLabConfig = LoadedGitLabConfig("", "", GitLabSecret("")),
): EngHubSettingsUiState = EngHubSettingsUiState(
    committedConfig = engHubConfig,
    organizationIds = engHubConfig.organizationIds,
    pollIntervalSeconds = (engHubConfig.pollIntervalMs / 1_000).toString(),
    worktreePollIntervalSeconds = (engHubConfig.worktreePollIntervalMs / 1_000).toString(),
    repositoriesBaseDir = engHubConfig.repositoriesBaseDir,
    gitHubAuthor = engHubConfig.gitHubAuthor,
    planningMarkdownDir = engHubConfig.planningMarkdownDir,
    alertTriageWhereToLook = engHubConfig.llmTemplateValues[ALERT_TRIAGE_WHERE_TO_LOOK_TEMPLATE_KEY].orEmpty(),
    localRepositories = engHubConfig.localRepositories.mapIndexed { index, repository ->
        SettingsLocalRepositoryUiState(
            id = index + 1L,
            path = repository.path,
            setupCommands = repository.setupCommands,
        )
    },
    setupShell = engHubConfig.setupShell,
    gitHubTokenPath = gitHubConfig.tokenPath,
    gitHubToken = MaskedTokenUiState(gitHubSecret.githubToken),
    gitHubAccessReady = gitHubConfig.tokenPath.isNotBlank() && gitHubSecret.githubToken.isNotBlank(),
    gitLabInstanceUrl = gitLabConfig.instanceUrl,
    gitLabTokenPath = gitLabConfig.tokenPath,
    gitLabToken = MaskedTokenUiState(gitLabConfig.secret.gitLabToken),
    gitLabAccessReady = gitLabConfig.isAccessReady(),
)
