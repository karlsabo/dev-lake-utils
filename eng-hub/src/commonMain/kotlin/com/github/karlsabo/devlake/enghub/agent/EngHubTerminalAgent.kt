package com.github.karlsabo.devlake.enghub.agent

import ai.koog.agents.chatMemory.feature.ChatMemory
import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.dsl.extension.HistoryCompressionStrategy
import ai.koog.agents.ext.agent.HistoryCompressionConfig
import ai.koog.agents.ext.agent.singleRunStrategyWithHistoryCompression
import ai.koog.prompt.executor.model.PromptExecutor
import com.nimblygames.llm.agent.QWEN_3_8_27B
import com.nimblygames.skills.readToolRegistry
import kotlinx.io.files.Path

const val DEFAULT_TERMINAL_SESSION_ID = "eng-hub-terminal"
const val LM_STUDIO_BASE_URL = "http://localhost:1234/v1"
const val LM_STUDIO_API_KEY = "lm-studio"
const val LM_STUDIO_HTTP_MAX_RETRIES = 2U

private const val MAX_AGENT_ITERATIONS = 30
private const val HISTORY_MESSAGE_LIMIT = 60
private const val HISTORY_MESSAGES_TO_KEEP = 20

private const val SYSTEM_PROMPT = """
You are Eng Hub's terminal assistant.
You can read UTF-8 text files with the read tool. Use it whenever the user asks about a file; never invent file contents.
Relative paths resolve from the terminal's launch working directory.
Keep responses concise and mention the source path when reporting file contents.
"""

/**
 * Builds the Eng Hub terminal agent with the framework Skills `read` tool and Koog chat memory.
 *
 * The agent keeps history for [sessionId] in Koog's in-memory chat history provider. It also installs
 * Koog's history compression strategy, which summarizes the retained tail once a conversation grows
 * beyond [HISTORY_MESSAGE_LIMIT] messages.
 */
object EngHubTerminalAgent {
    fun create(
        promptExecutor: PromptExecutor,
        workingDirectory: String,
        sessionId: String = DEFAULT_TERMINAL_SESSION_ID,
    ): TerminalChatSession {
        require(Path(workingDirectory).isAbsolute) {
            "workingDirectory must be absolute: $workingDirectory"
        }
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }

        val agent = AIAgent(
            promptExecutor = promptExecutor,
            llmModel = QWEN_3_8_27B,
            strategy = chatStrategy(),
            toolRegistry = readToolRegistry(workingDirectory),
            systemPrompt = SYSTEM_PROMPT,
            maxIterations = MAX_AGENT_ITERATIONS,
            installFeatures = {
                install(ChatMemory.Feature)
            },
        )
        return TerminalChatSession(agent, sessionId)
    }
}

private fun chatStrategy(): AIAgentGraphStrategy<String, String> = singleRunStrategyWithHistoryCompression(
    config = HistoryCompressionConfig(
        isHistoryTooBig = { prompt -> prompt.messages.size > HISTORY_MESSAGE_LIMIT },
        compressionStrategy = HistoryCompressionStrategy.FromLastNMessages(HISTORY_MESSAGES_TO_KEEP),
    ),
)
