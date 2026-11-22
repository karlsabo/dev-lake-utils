package com.github.karlsabo.devlake.enghub

import kotlinx.io.files.Path

internal fun transactionPaths(configPath: Path): List<Path> = listOf(
    configPath,
    Path("$configPath.new"),
    Path("$configPath.bak"),
)

/**
 * Tracks the committed secret paths of both providers so each provider's secret path validation also protects the
 * other provider's secret file and its transaction files.
 */
internal class CrossProviderSecretPaths(
    gitHubSecretPath: String,
    gitLabSecretPath: String,
) {
    private var gitHubSecretPath = gitHubSecretPath
    private var gitLabSecretPath = gitLabSecretPath

    fun updateGitHubSecretPath(path: String) {
        gitHubSecretPath = path
    }

    fun updateGitLabSecretPath(path: String) {
        gitLabSecretPath = path
    }

    fun protectedPathsForGitHubSecret(): List<Path> = secretTransactionPaths(gitLabSecretPath)

    fun protectedPathsForGitLabSecret(): List<Path> = secretTransactionPaths(gitHubSecretPath)
}

private fun secretTransactionPaths(secretPath: String): List<Path> = if (secretPath.isBlank()) {
    emptyList()
} else {
    transactionPaths(Path(secretPath))
}
