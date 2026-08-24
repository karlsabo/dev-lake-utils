package com.github.karlsabo.devlake.enghub.state

import com.github.karlsabo.devlake.enghub.EngHubConfig
import com.github.karlsabo.github.config.GitHubConfig
import com.github.karlsabo.github.config.GitHubSecret

internal const val MASKED_TOKEN = "••••••••"

class GitHubTokenUiState internal constructor(
    internal val value: String,
    private val draftValue: String? = null,
) {
    val maskedValue: String = if (value.isEmpty()) "" else MASKED_TOKEN
    internal val fieldValue: String = draftValue ?: value

    internal fun withDraft(token: String): GitHubTokenUiState = GitHubTokenUiState(value, token)

    internal fun withCommittedToken(token: String): GitHubTokenUiState = GitHubTokenUiState(token)

    override fun toString(): String = "GitHubTokenUiState()"
}

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
    val localRepositories: List<SettingsLocalRepositoryUiState>,
    val localRepositoryDraft: String = "",
    val localRepositoryError: String? = null,
    val removedLocalRepositoryPath: String? = null,
    val setupShell: String,
    val gitHubTokenPath: String,
    val gitHubTokenPathError: String? = null,
    val gitHubToken: GitHubTokenUiState,
    val gitHubTokenError: String? = null,
    val gitHubAccessReady: Boolean,
)

data class SettingsLocalRepositoryUiState(
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
): EngHubSettingsUiState = EngHubSettingsUiState(
    committedConfig = engHubConfig,
    organizationIds = engHubConfig.organizationIds,
    pollIntervalSeconds = (engHubConfig.pollIntervalMs / 1_000).toString(),
    worktreePollIntervalSeconds = (engHubConfig.worktreePollIntervalMs / 1_000).toString(),
    repositoriesBaseDir = engHubConfig.repositoriesBaseDir,
    gitHubAuthor = engHubConfig.gitHubAuthor,
    planningMarkdownDir = engHubConfig.planningMarkdownDir,
    localRepositories = engHubConfig.localRepositories.map { repository ->
        SettingsLocalRepositoryUiState(
            path = repository.path,
            setupCommands = repository.setupCommands,
        )
    },
    setupShell = engHubConfig.setupShell,
    gitHubTokenPath = gitHubConfig.tokenPath,
    gitHubToken = GitHubTokenUiState(gitHubSecret.githubToken),
    gitHubAccessReady = gitHubConfig.tokenPath.isNotBlank() && gitHubSecret.githubToken.isNotBlank(),
)
