package com.github.karlsabo.devlake.enghub

import com.github.karlsabo.git.buildWorktreeSetupScript
import com.github.karlsabo.system.ProcessResult
import com.github.karlsabo.system.executeCommand
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

internal fun runConfiguredWorktreeSetup(
    repoPath: String,
    worktreePath: String,
    config: EngHubConfig,
): ProcessResult? {
    val normalizedRepoPath = repoPath.normalizedRepositoryPath()
    val commands = config.localRepositories
        .firstOrNull { it.path.normalizedRepositoryPath() == normalizedRepoPath }
        ?.setupCommands
        .orEmpty()
    if (commands.isEmpty()) return null

    val script = buildWorktreeSetupScript(commands)
    logger.info { "Executing setup script: $script" }
    return executeCommand(listOf(config.setupShell, "-l", "-c", script), worktreePath)
}

