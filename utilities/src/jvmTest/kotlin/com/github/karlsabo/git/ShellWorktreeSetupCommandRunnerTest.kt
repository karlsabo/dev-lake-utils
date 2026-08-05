package com.github.karlsabo.git

import com.github.karlsabo.system.OsFamily
import com.github.karlsabo.system.ProcessResult
import com.github.karlsabo.system.osFamily
import kotlinx.coroutines.runBlocking
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShellWorktreeSetupCommandRunnerTest {
    @Test
    fun powerShellScriptIsEncodedForProcessBuilder() {
        val commands = listOf(
            "Write-Output \"quoted output\"",
            "Write-Output 'same shell: 雪 😀'",
        )
        val request = setupRequest(setupShell = "powershell.exe", setupCommands = commands)

        val shellCommand = request.buildSetupShellCommand()

        assertEquals(listOf("powershell.exe", "-NoProfile", "-EncodedCommand"), shellCommand.dropLast(1))
        assertEquals(buildPowerShellWorktreeSetupScript(commands), decodePowerShellCommand(shellCommand.last()))
        assertFalse('"' in shellCommand.last())
        assertFalse('\n' in shellCommand.last())
    }

    @Test
    fun successfulProcessResultIsMappedWithoutStartingShell() = runBlocking {
        val processResult = ProcessResult(
            exitCode = 0,
            stdout = commandOutput(
                SETUP_COMMAND_STDOUT_BEGIN_MARKER,
                SETUP_COMMAND_STDOUT_END_MARKER,
                "standard out\n",
            ),
            stderr = commandMetadata(0, 0) + commandOutput(
                SETUP_COMMAND_STDERR_BEGIN_MARKER,
                SETUP_COMMAND_STDERR_END_MARKER,
                "standard error\n",
            ),
        )
        val executor = RecordingProcessExecutor(processResult)
        val request = setupRequest(setupShell = "/bin/sh", setupCommands = listOf("echo output"))

        val result = ShellWorktreeSetupCommandRunner(executor).runSetup(request)

        assertEquals(request.buildSetupShellCommand(), executor.command)
        assertEquals(request.worktreePath.value, executor.workingDirectory)
        assertEquals(WorktreeSetupCommandResult(0, "standard out\n", "standard error\n"), result)
    }

    @Test
    fun failedProcessResultIsFormattedWithoutStartingShell() = runBlocking {
        val commands = listOf("first command", "command after failure")
        val processResult = ProcessResult(
            exitCode = 23,
            stdout = indexedCommandOutput(
                SETUP_COMMAND_STDOUT_BEGIN_MARKER,
                SETUP_COMMAND_STDOUT_END_MARKER,
                listOf("standard out\n", "after failure\n"),
            ),
            stderr = commandMetadata(0, 23) + commandMetadata(1, 0) + indexedCommandOutput(
                SETUP_COMMAND_STDERR_BEGIN_MARKER,
                SETUP_COMMAND_STDERR_END_MARKER,
                listOf("standard error\n", ""),
            ),
        )
        val request = setupRequest(setupShell = "/bin/sh", setupCommands = commands)

        val error = assertFailsWith<WorktreeSetupException> {
            ShellWorktreeSetupCommandRunner(RecordingProcessExecutor(processResult)).runSetup(request)
        }
        val message = error.message.orEmpty()

        assertTrue("Setup failed for worktree" in message, message)
        assertTrue("Working directory: worktree" in message, message)
        assertTrue("Shell: /bin/sh -l -c <generated setup script>" in message, message)
        assertTrue("Overall exit code: 23" in message, message)
        assertTrue("[1/2] FAILED exit 23" in message, message)
        assertTrue("stdout:\nstandard out\n" in message, message)
        assertTrue("stderr:\nstandard error\n" in message, message)
        assertTrue("[2/2] OK exit 0" in message, message)
        assertTrue("stdout:\nafter failure\n" in message, message)
        assertTrue("__ENG_HUB_SETUP_COMMAND_" !in message, message)
    }

    @Test
    fun nativeShellInvocationSmokeTest() = runBlocking {
        val worktreePath = createArchiveWorktreeTempDir()
        try {
            val windows = osFamily() == OsFamily.WINDOWS
            val request = setupRequest(
                worktreePath = worktreePath,
                setupShell = if (windows) "powershell.exe" else "/bin/sh",
                setupCommands = listOf(if (windows) "Write-Output 'shell smoke'" else "printf 'shell smoke\\n'"),
            )

            val result = ShellWorktreeSetupCommandRunner().runSetup(request)

            assertEquals(0, result.exitCode)
            assertEquals("shell smoke\n", result.stdout.replace("\r\n", "\n"))
            assertEquals("", result.stderr)
        } finally {
            removeTempDir(worktreePath)
        }
    }
}

private class RecordingProcessExecutor(
    private val result: ProcessResult,
) : WorktreeSetupProcessExecutor {
    var command: List<String>? = null
    var workingDirectory: String? = null

    override fun execute(command: List<String>, workingDirectory: String?): ProcessResult {
        this.command = command
        this.workingDirectory = workingDirectory
        return result
    }
}

private fun setupRequest(
    worktreePath: String = "worktree",
    setupShell: String,
    setupCommands: List<String>,
) = WorktreeSetupRequest(
    repoPath = "repo",
    worktreePath = WorktreePath(worktreePath),
    setupShell = setupShell,
    setupCommands = setupCommands,
)

private fun commandMetadata(index: Int, exitCode: Int) = "$SETUP_COMMAND_START_MARKER\t$index\n" +
    "$SETUP_COMMAND_RESULT_MARKER\t$index\t$exitCode\n"

private fun commandOutput(
    beginMarker: String,
    endMarker: String,
    output: String,
) = "$beginMarker\t0\n$output$endMarker\t0\t\n"

private fun indexedCommandOutput(
    beginMarker: String,
    endMarker: String,
    outputs: List<String>,
) = outputs.mapIndexed { index, output ->
    "$beginMarker\t$index\n$output$endMarker\t$index\t\n"
}.joinToString("")

@OptIn(ExperimentalEncodingApi::class)
private fun decodePowerShellCommand(encodedCommand: String): String {
    val bytes = Base64.Default.decode(encodedCommand)
    require(bytes.size % 2 == 0)
    return CharArray(bytes.size / 2) { index ->
        val lowByte = bytes[index * 2].toInt() and 0xff
        val highByte = bytes[index * 2 + 1].toInt() and 0xff
        (lowByte or (highByte shl 8)).toChar()
    }.concatToString()
}
