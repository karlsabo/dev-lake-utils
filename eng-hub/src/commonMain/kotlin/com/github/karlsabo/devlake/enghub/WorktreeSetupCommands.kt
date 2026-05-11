package com.github.karlsabo.devlake.enghub

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
    val localRepository = config.localRepositories
        .firstOrNull { it.path.normalizedRepositoryPath() == normalizedRepoPath }
    val legacyCommands = config.worktreeSetupCommands.entries
        .firstOrNull { it.key.normalizedRepositoryPath() == normalizedRepoPath }
        ?.value
        .orEmpty()
    val commands = when {
        localRepository == null -> legacyCommands
        localRepository.setupCommands.isNotEmpty() -> localRepository.setupCommands
        else -> legacyCommands
    }
    if (commands.isEmpty()) return null

    val script = buildWorktreeSetupScript(commands)
    logger.info { "Executing setup script: $script" }
    return executeCommand(listOf(config.setupShell, "-l", "-c", script), worktreePath)
}

internal fun buildWorktreeSetupScript(commands: List<String>): String =
    buildString {
        appendLine("setup_exit_code=0")
        commands.forEach { command ->
            appendLine(command)
            appendLine("command_exit_code=\$?")
            appendLine("if [ \"\$command_exit_code\" -ne 0 ] && [ \"\$setup_exit_code\" -eq 0 ]; then setup_exit_code=\"\$command_exit_code\"; fi")
        }
        append("exit \"\$setup_exit_code\"")
    }
