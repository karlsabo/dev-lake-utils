package com.github.karlsabo.git

import com.github.karlsabo.system.executeCommand

class ShellWorktreeSetupCommandRunner : WorktreeSetupCommandRunner {
    override suspend fun runSetup(request: WorktreeSetupRequest): WorktreeSetupCommandResult {
        if (request.setupCommands.isEmpty()) {
            return WorktreeSetupCommandResult(exitCode = 0, stdout = "", stderr = "")
        }

        val shellCommand = request.buildSetupShellCommand()
        val result = executeCommand(
            command = shellCommand,
            workingDirectory = request.worktreePath.value,
        )
        if (result.exitCode != 0) {
            throw WorktreeSetupException(formatSetupCommandFailure(request, result))
        }
        val stderr = result.stderr.withoutSetupCommandMarkers(
            SETUP_COMMAND_STDERR_BEGIN_MARKER,
            SETUP_COMMAND_STDERR_END_MARKER,
        ).ifEmpty {
            result.stdout.setupCommandStreamOutput(
                SETUP_COMMAND_STDERR_BEGIN_MARKER,
                SETUP_COMMAND_STDERR_END_MARKER,
            )
        }
        return WorktreeSetupCommandResult(
            exitCode = result.exitCode,
            stdout = result.stdout.withoutSetupCommandMarkers(
                SETUP_COMMAND_STDOUT_BEGIN_MARKER,
                SETUP_COMMAND_STDOUT_END_MARKER,
            ),
            stderr = stderr,
        )
    }
}

private fun WorktreeSetupRequest.buildSetupShellCommand(): List<String> =
    if (setupShell.isWindowsPowerShell()) {
        listOf(setupShell, "-NoProfile", "-Command", buildPowerShellWorktreeSetupScript(expandedSetupCommands()))
    } else {
        listOf(setupShell, "-l", "-c", buildWorktreeSetupScript(this))
    }

private fun String.isWindowsPowerShell(): Boolean =
    substringAfterLast('/').substringAfterLast('\\').equals("powershell.exe", ignoreCase = true)

internal fun buildPowerShellWorktreeSetupScript(commands: List<String>): String = commands.joinToString("\n")

fun buildWorktreeSetupScript(request: WorktreeSetupRequest): String = buildWorktreeSetupScript(
    request.expandedSetupCommands(),
)

fun buildWorktreeSetupScript(commands: List<String>): String = buildString {
    appendLine("set +e")
    appendLine("setup_exit_code=0")
    appendLine("setup_tmp_dir=\$(mktemp -d \"\${TMPDIR:-/tmp}/eng-hub-setup.XXXXXX\") || exit 1")
    appendLine("trap 'rm -rf \"\$setup_tmp_dir\"' EXIT HUP INT TERM")
    commands.forEachIndexed { index, command ->
        appendLine("setup_stdout_file=\"\$setup_tmp_dir/stdout_$index\"")
        appendLine("setup_stderr_file=\"\$setup_tmp_dir/stderr_$index\"")
        appendLine("printf '$SETUP_COMMAND_START_MARKER\\t$index\\n' >&2")
        appendLine("{")
        appendLine(command)
        appendLine("} > \"\$setup_stdout_file\" 2> \"\$setup_stderr_file\"")
        appendLine("command_exit_code=$?")
        appendLine("printf '$SETUP_COMMAND_STDOUT_BEGIN_MARKER\\t$index\\n'")
        appendLine("cat \"\$setup_stdout_file\"")
        appendLine("printf '$SETUP_COMMAND_STDOUT_END_MARKER\\t$index\\t\\n'")
        appendLine("printf '$SETUP_COMMAND_STDERR_BEGIN_MARKER\\t$index\\n' >&2")
        appendLine("cat \"\$setup_stderr_file\" >&2")
        appendLine("printf '$SETUP_COMMAND_STDERR_END_MARKER\\t$index\\t\\n' >&2")
        appendLine("printf '$SETUP_COMMAND_RESULT_MARKER\\t$index\\t%s\\n' \"\$command_exit_code\" >&2")
        appendLine(
            "if [ \"\$command_exit_code\" -ne 0 ] && [ \"\$setup_exit_code\" -eq 0 ]; then " +
                "setup_exit_code=\"\$command_exit_code\"; " +
                "fi",
        )
    }
    append("exit \"\$setup_exit_code\"")
}

internal fun WorktreeSetupRequest.expandedSetupCommands(): List<String> = setupCommands.map { expandSetupCommand(it) }

private fun WorktreeSetupRequest.expandSetupCommand(command: String): String = command.expandShellPlaceholders(
    mapOf(
        $$"$root-repo-dir" to repoPath,
        $$"$worktree-dir" to worktreePath.value,
    ),
)
