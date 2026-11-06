package com.github.karlsabo.devlake.enghub.agent

import ai.koog.agents.core.agent.AIAgent

const val EXIT_COMMAND = "/exit"

private const val INPUT_PREFIX = "you> "
private const val ASSISTANT_PREFIX = "agent> "

/** Synchronous terminal input and output used by the portable chat loop. */
interface TerminalIO {
    fun readLine(): String?

    fun write(text: String)
}

/** One conversation that retains model and tool context between prompts. */
interface ChatSession {
    suspend fun ask(prompt: String): String

    suspend fun close()
}

/** Koog-backed chat session; [sessionId] selects the history kept by the installed chat memory feature. */
class TerminalChatSession(
    private val agent: AIAgent<String, String>,
    private val sessionId: String,
) : ChatSession {
    override suspend fun ask(prompt: String): String {
        require(prompt.isNotBlank()) { "prompt must not be blank" }
        return agent.run(prompt, sessionId)
    }

    override suspend fun close() {
        agent.close()
    }
}

/**
 * Reads prompts until [EXIT_COMMAND] or end of input, writing each agent response to [io].
 *
 * A prompt failure propagates to the caller; the launcher lets the process fail rather than hiding
 * an unrecoverable model or tool error.
 */
class TerminalChat(
    private val session: ChatSession,
    private val io: TerminalIO,
) {
    suspend fun run() {
        var prompt = readPrompt()
        while (prompt != null) {
            when {
                prompt == EXIT_COMMAND -> {
                    io.write("\n")
                    return
                }

                prompt.isNotEmpty() -> respond(prompt)
            }
            prompt = readPrompt()
        }
        io.write("\n")
    }

    private fun readPrompt(): String? {
        io.write(INPUT_PREFIX)
        return io.readLine()?.trim()
    }

    private suspend fun respond(prompt: String) {
        val response = session.ask(prompt)
        io.write("\n$ASSISTANT_PREFIX${response.trim()}\n")
    }
}
