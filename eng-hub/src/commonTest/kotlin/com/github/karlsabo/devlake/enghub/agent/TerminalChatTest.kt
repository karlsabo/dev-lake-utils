package com.github.karlsabo.devlake.enghub.agent

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalChatTest {
    @Test
    fun `handles prompts and follow-up until exit`() = runTest {
        val io = RecordingTerminalIO("Read the file", "What did it say?", EXIT_COMMAND, "ignored")
        val session = RecordingChatSession(
            mapOf(
                "Read the file" to "Release on Friday",
                "What did it say?" to "It said release on Friday.",
            ),
        )

        TerminalChat(session, io).run()

        assertEquals(listOf("Read the file", "What did it say?"), session.prompts)
        assertTrue(io.output.contains("agent> Release on Friday"))
        assertTrue(io.output.contains("agent> It said release on Friday."))
        assertFalse(io.output.contains("ignored"))
    }

    @Test
    fun `end of input ends the chat`() = runTest {
        val io = RecordingTerminalIO("Only prompt")
        val session = RecordingChatSession(mapOf("Only prompt" to "Only reply"))

        TerminalChat(session, io).run()

        assertEquals(listOf("Only prompt"), session.prompts)
        assertTrue(io.output.contains("agent> Only reply"))
    }

    @Test
    fun `blank input is skipped`() = runTest {
        val io = RecordingTerminalIO("", "   ", "Prompt", EXIT_COMMAND)
        val session = RecordingChatSession(mapOf("Prompt" to "Reply"))

        TerminalChat(session, io).run()

        assertEquals(listOf("Prompt"), session.prompts)
    }
}

private class RecordingTerminalIO(
    vararg lines: String,
) : TerminalIO {
    private val remaining = lines.toMutableList()
    val output = StringBuilder()

    override fun readLine(): String? = remaining.removeFirstOrNull()

    override fun write(text: String) {
        output.append(text)
    }
}

private class RecordingChatSession(
    private val replies: Map<String, String>,
) : ChatSession {
    val prompts = mutableListOf<String>()

    override suspend fun ask(prompt: String): String {
        prompts += prompt
        return replies[prompt] ?: error("Unexpected prompt: $prompt")
    }

    override suspend fun close() = Unit
}
