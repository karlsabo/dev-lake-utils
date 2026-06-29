package com.github.karlsabo.git

import com.github.karlsabo.system.ProcessResult

internal fun formatSetupCommandFailure(
    request: WorktreeSetupRequest,
    result: ProcessResult,
): String = buildString {
    val setupCommands = request.expandedSetupCommands()
    val executions = parseSetupCommandExecutions(setupCommands, result)
    appendLine("Setup failed for ${request.worktreePath.value}")
    appendLine()
    appendLine("Working directory: ${request.worktreePath.value}")
    appendLine("Shell: ${request.setupShell} -l -c <generated setup script>")
    appendLine("Overall exit code: ${result.exitCode}")
    executions.forEach { execution -> appendSetupCommandExecution(execution, setupCommands.size) }
}

private fun StringBuilder.appendSetupCommandExecution(
    execution: SetupCommandExecution,
    commandCount: Int,
) {
    appendLine()
    appendLine("────────────────────────────────────────")
    appendLine("[${execution.commandIndex + 1}/$commandCount] ${execution.statusLabel()}")
    appendCommandLine(execution.command)
    appendLine()
    appendLine("stdout:")
    appendCommandOutput(execution.stdout)
    appendLine()
    appendLine("stderr:")
    appendCommandOutput(execution.stderr)
}

private fun SetupCommandExecution.statusLabel(): String = when {
    !started -> "SKIPPED"
    exitCode == 0 -> "OK exit 0"
    exitCode != null -> "FAILED exit $exitCode"
    else -> "FAILED exit unknown"
}

private fun StringBuilder.appendCommandLine(command: String) {
    if ('\n' !in command) {
        appendLine("$ $command")
        return
    }
    appendLine("$ <<'SETUP_COMMAND'")
    append(command)
    if (!command.endsWith('\n')) appendLine()
    appendLine("SETUP_COMMAND")
}

private fun StringBuilder.appendCommandOutput(output: String) {
    if (output.isEmpty()) {
        appendLine("<empty>")
        return
    }

    append(output)
    if (!output.endsWith('\n')) appendLine()
}
