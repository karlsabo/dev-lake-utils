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

internal fun WorktreeSetupRequest.buildSetupShellCommand(): List<String> = buildList {
    add(setupShell)
    addAll(setupShellArguments())
    add(
        when (setupShellDialect()) {
            ShellDialect.POWERSHELL -> encodePowerShellCommand(generatedSetupScript())
            ShellDialect.POSIX -> generatedSetupScript()
        },
    )
}

internal fun WorktreeSetupRequest.setupShellArguments(): List<String> = when {
    setupShell.isWindowsPowerShell() -> listOf("-NoProfile", "-EncodedCommand")
    else -> listOf("-l", "-c")
}

private fun WorktreeSetupRequest.generatedSetupScript(): String = when (setupShellDialect()) {
    ShellDialect.POWERSHELL -> buildPowerShellWorktreeSetupScript(expandedSetupCommands())
    ShellDialect.POSIX -> buildWorktreeSetupScript(this)
}

internal fun WorktreeSetupRequest.setupShellDialect(): ShellDialect = when {
    setupShell.isWindowsPowerShell() -> ShellDialect.POWERSHELL
    else -> ShellDialect.POSIX
}

private fun String.isWindowsPowerShell(): Boolean = substringAfterLast('/')
    .substringAfterLast('\\')
    .equals("powershell.exe", ignoreCase = true)

internal fun buildPowerShellWorktreeSetupScript(commands: List<String>): String = buildString {
    appendLine("${'$'}setupExitCode = 0")
    appendLine(
        "${'$'}setupTmpDir = [IO.Path]::Combine(" +
            "[IO.Path]::GetTempPath(), 'eng-hub-setup-' + [Guid]::NewGuid().ToString('N'))",
    )
    appendLine("[IO.Directory]::CreateDirectory(${'$'}setupTmpDir) | Out-Null")
    appendLine("try {")
    commands.forEachIndexed { index, command ->
        appendLine("    ${'$'}setupStdoutFile = [IO.Path]::Combine(${'$'}setupTmpDir, 'stdout_$index')")
        appendLine("    ${'$'}setupStderrFile = [IO.Path]::Combine(${'$'}setupTmpDir, 'stderr_$index')")
        appendLine("    [Console]::Error.WriteLine(\"$SETUP_COMMAND_START_MARKER`t$index\")")
        appendLine("    ${'$'}LASTEXITCODE = 0")
        appendLine("    ${'$'}commandHadPowerShellError = ${'$'}false")
        appendLine("    . {")
        appendLine("        try {")
        appendLine("            . {")
        appendLine(command.prependIndent("                "))
        appendLine("            }")
        appendLine("        } catch {")
        appendLine("            ${'$'}commandHadPowerShellError = ${'$'}true")
        appendLine("            Write-Error -ErrorRecord ${'$'}_ -ErrorAction Continue")
        appendLine("        }")
        appendLine("    } 1> ${'$'}setupStdoutFile 2> ${'$'}setupStderrFile")
        appendLine(
            "    ${'$'}commandExitCode = if (${'$'}commandHadPowerShellError) { 1 } else { ${'$'}LASTEXITCODE }",
        )
        appendLine("    [Console]::Out.WriteLine(\"$SETUP_COMMAND_STDOUT_BEGIN_MARKER`t$index\")")
        appendLine("    [Console]::Out.Write([IO.File]::ReadAllText(${'$'}setupStdoutFile))")
        appendLine("    [Console]::Out.WriteLine(\"$SETUP_COMMAND_STDOUT_END_MARKER`t$index`t\")")
        appendLine("    [Console]::Error.WriteLine(\"$SETUP_COMMAND_STDERR_BEGIN_MARKER`t$index\")")
        appendLine("    [Console]::Error.Write([IO.File]::ReadAllText(${'$'}setupStderrFile))")
        appendLine("    [Console]::Error.WriteLine(\"$SETUP_COMMAND_STDERR_END_MARKER`t$index`t\")")
        appendLine(
            "    [Console]::Error.WriteLine(" +
                "\"$SETUP_COMMAND_RESULT_MARKER`t$index`t\" + ${'$'}commandExitCode)",
        )
        appendLine("    if ((${'$'}commandExitCode -ne 0) -and (${'$'}setupExitCode -eq 0)) {")
        appendLine("        ${'$'}setupExitCode = ${'$'}commandExitCode")
        appendLine("    }")
    }
    appendLine("} finally {")
    appendLine("    Remove-Item -LiteralPath ${'$'}setupTmpDir -Recurse -Force -ErrorAction SilentlyContinue")
    appendLine("}")
    append("exit ${'$'}setupExitCode")
}

fun buildWorktreeSetupScript(request: WorktreeSetupRequest): String = buildWorktreeSetupScript(
    request.expandedSetupCommands(ShellDialect.POSIX),
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

internal fun WorktreeSetupRequest.expandedSetupCommands(): List<String> = expandedSetupCommands(setupShellDialect())

internal fun WorktreeSetupRequest.expandedSetupCommands(shellDialect: ShellDialect): List<String> = setupCommands.map {
    it.expandShellPlaceholders(
        replacements = mapOf(
            $$"$root-repo-dir" to repoPath,
            $$"$worktree-dir" to worktreePath.value,
        ),
        shellDialect = shellDialect,
    )
}
