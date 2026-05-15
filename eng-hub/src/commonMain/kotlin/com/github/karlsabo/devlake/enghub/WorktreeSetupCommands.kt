package com.github.karlsabo.devlake.enghub

internal fun configuredWorktreeSetupCommands(
    repoPath: String,
    config: EngHubConfig,
): List<String> {
    val normalizedRepoPath = repoPath.normalizedRepositoryPath()
    return config.localRepositories
        .firstOrNull { it.path.normalizedRepositoryPath() == normalizedRepoPath }
        ?.setupCommands
        .orEmpty()
}
