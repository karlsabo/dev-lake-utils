package com.github.karlsabo.git

import com.github.karlsabo.system.ProcessResult
import kotlin.test.Test
import kotlin.test.assertEquals

class WorktreeSetupCommandOutputParserTest {
    @Test
    fun parsesPowerShellCommandStreamsWithCrLfMarkers() {
        val stdout = listOf(
            "$SETUP_COMMAND_STDOUT_BEGIN_MARKER\t0",
            "standard out",
            "$SETUP_COMMAND_STDOUT_END_MARKER\t0\t",
            "",
        ).joinToString("\r\n")
        val stderr = listOf(
            "#< CLIXML startup metadata",
            "$SETUP_COMMAND_START_MARKER\t0",
            "$SETUP_COMMAND_STDERR_BEGIN_MARKER\t0",
            "standard error",
            "$SETUP_COMMAND_STDERR_END_MARKER\t0\t",
            "$SETUP_COMMAND_RESULT_MARKER\t0\t23",
            "",
        ).joinToString("\r\n")
        val result = ProcessResult(exitCode = 23, stdout = stdout, stderr = stderr)

        val execution = parseSetupCommandExecutions(listOf("failing command"), result).single()

        assertEquals("standard out\r\n", execution.stdout)
        assertEquals("standard error\r\n", execution.stderr)
        assertEquals(23, execution.exitCode)
        assertEquals(
            "standard out\r\n",
            stdout.withoutSetupCommandMarkers(
                SETUP_COMMAND_STDOUT_BEGIN_MARKER,
                SETUP_COMMAND_STDOUT_END_MARKER,
            ),
        )
        assertEquals(
            "standard error\r\n",
            stderr.withoutSetupCommandMarkers(
                SETUP_COMMAND_STDERR_BEGIN_MARKER,
                SETUP_COMMAND_STDERR_END_MARKER,
            ),
        )
    }
}
