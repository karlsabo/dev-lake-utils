package com.github.karlsabo.git

import com.github.karlsabo.system.OsFamily
import com.github.karlsabo.system.osFamily
import kotlinx.coroutines.runBlocking
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShellWorktreeSetupCommandRunnerTest {
    @Test
    fun setupFailureReportsPerCommandOutputAndRunsCommandsAfterFailure() = runBlocking {
        val repoPath = createArchiveWorktreeTempDir()
        val worktreePath = createArchiveWorktreeTempDir()
        try {
            val request = WorktreeSetupRequest(
                repoPath = repoPath,
                worktreePath = WorktreePath(worktreePath),
                setupShell = "/bin/sh",
                setupCommands = listOf(
                    "printf 'standard out\\n'",
                    "printf 'standard error\\n' >&2",
                    "sh -c 'exit 23'",
                    "printf 'after failure\\n'",
                ),
            )

            val error = assertFailsWith<WorktreeSetupException> {
                ShellWorktreeSetupCommandRunner().runSetup(request)
            }
            val message = error.message.orEmpty()

            assertTrue("Setup failed for $worktreePath" in message, message)
            assertTrue("Working directory: $worktreePath" in message, message)
            assertTrue("Shell: /bin/sh -l -c <generated setup script>" in message, message)
            assertTrue("Overall exit code: 23" in message, message)
            assertTrue("[1/4] OK exit 0" in message, message)
            assertTrue("$ printf 'standard out\\n'" in message, message)
            assertTrue("stdout:\nstandard out\n" in message, message)
            assertTrue("[2/4] OK exit 0" in message, message)
            assertTrue("stderr:\nstandard error\n" in message, message)
            assertTrue("[3/4] FAILED exit 23" in message, message)
            assertTrue("$ sh -c 'exit 23'" in message, message)
            assertTrue("[4/4] OK exit 0" in message, message)
            assertTrue("stdout:\nafter failure\n" in message, message)
            assertTrue("SKIPPED" !in message, message)
            assertTrue("__ENG_HUB_SETUP_COMMAND_" !in message, message)
        } finally {
            removeTempDir(repoPath)
            removeTempDir(worktreePath)
        }
    }

    @Test
    fun setupCommandsShareShellState() = runBlocking {
        val repoPath = createArchiveWorktreeTempDir()
        val worktreePath = createArchiveWorktreeTempDir()
        try {
            SystemFileSystem.createDirectories(Path(worktreePath, "nested"))
            val windows = osFamily() == OsFamily.WINDOWS
            val request = WorktreeSetupRequest(
                repoPath = repoPath,
                worktreePath = WorktreePath(worktreePath),
                setupShell = if (windows) "powershell.exe" else "/bin/sh",
                setupCommands = if (windows) {
                    listOf(
                        "Set-Location nested",
                        "${'$'}SETUP_STATE = 'kept'",
                        "Set-Content -NoNewline -Path state.txt -Value ${'$'}SETUP_STATE",
                    )
                } else {
                    listOf(
                        "cd nested",
                        "export SETUP_STATE=kept",
                        "printf '%s' \"${'$'}SETUP_STATE\" > state.txt",
                    )
                },
            )

            val result = ShellWorktreeSetupCommandRunner().runSetup(request)

            assertEquals(0, result.exitCode)
            assertEquals("", result.stderr)
            assertFalse(SystemFileSystem.exists(Path(worktreePath, "state.txt")))
            val state = SystemFileSystem.source(Path(worktreePath, "nested", "state.txt")).buffered().use {
                it.readString()
            }
            assertEquals("kept", state)
        } finally {
            removeTempDir(repoPath)
            removeTempDir(worktreePath)
        }
    }
}
