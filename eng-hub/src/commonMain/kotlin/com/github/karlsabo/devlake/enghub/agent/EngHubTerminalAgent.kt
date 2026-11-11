package com.github.karlsabo.devlake.enghub.agent

import ai.koog.agents.chatMemory.feature.ChatMemory
import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.dsl.extension.HistoryCompressionStrategy
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.ext.agent.HistoryCompressionConfig
import ai.koog.agents.ext.agent.singleRunStrategyWithHistoryCompression
import ai.koog.prompt.executor.model.PromptExecutor
import com.nimblygames.llm.agent.QWEN_3_8_27B
import com.nimblygames.skills.readToolRegistry
import com.nimblygames.skills.shellToolRegistry
import com.nimblygames.skills.writeToolRegistry
import kotlinx.io.files.Path

const val DEFAULT_TERMINAL_SESSION_ID = "eng-hub-terminal"
const val LM_STUDIO_BASE_URL = "http://localhost:1234/v1"
const val LM_STUDIO_API_KEY = "lm-studio"
const val LM_STUDIO_HTTP_MAX_RETRIES = 2U

private const val MAX_AGENT_ITERATIONS = 30

/**
 * Message count at which conversation history is bounded, whether or not a tool has run.
 */
internal const val HISTORY_MESSAGE_LIMIT = 60
private const val HISTORY_MESSAGES_TO_KEEP = 20

internal const val AGENT_SYSTEM_PROMPT = """
You are Eng Hub's terminal assistant.
You can read UTF-8 text files with the read tool. Use it whenever the user asks about a file; never invent file contents.
You can create or replace UTF-8 text files with the write tool. Use it when the user asks you to write a file.
You can run non-interactive Bash commands with the bash tool. Use it when the user asks you to run a command or inspect files with shell tools.
Relative paths resolve from the terminal's launch working directory.
Keep responses concise and mention the path when reporting file contents.
"""

internal const val BROWSER_MCP_SYSTEM_PROMPT = """
Browser MCP tools are available. Use them when the user asks you to open, navigate, or inspect a web page.
"""

/**
 * Builds the Eng Hub terminal agent with the framework Skills `read`, `write`, and `bash` tools plus Koog chat memory.
 *
 * The agent keeps history for [sessionId] in Koog's in-memory chat history provider. Chat memory is
 * capped at [HISTORY_MESSAGE_LIMIT] messages so text-only turns stay bounded, and Koog's history
 * compression strategy summarizes the retained tail once a tool call grows a prompt beyond that
 * limit.
 *
 * When [browserMcpTools] is provided, its tools are added to the Skills tools for the same session.
 * The session owns the MCP manager and closes it on normal exit, input failure, or a construction
 * failure after tool discovery has started.
 */
object EngHubTerminalAgent {
    suspend fun create(
        promptExecutor: PromptExecutor,
        workingDirectory: String,
        sessionId: String = DEFAULT_TERMINAL_SESSION_ID,
        browserMcpTools: BrowserMcpTools? = null,
    ): TerminalChatSession {
        require(Path(workingDirectory).isAbsolute) {
            "workingDirectory must be absolute: $workingDirectory"
        }
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }

        return runCatching {
            val agent =
                AIAgent(
                    promptExecutor = promptExecutor,
                    llmModel = QWEN_3_8_27B,
                    strategy = engHubChatStrategy(),
                    toolRegistry = engHubToolRegistry(workingDirectory, browserMcpTools),
                    systemPrompt = terminalSystemPrompt(browserMcpTools),
                    maxIterations = MAX_AGENT_ITERATIONS,
                    installFeatures = {
                        install(ChatMemory.Feature) {
                            addPreProcessor(BoundedChatHistoryPreProcessor(HISTORY_MESSAGE_LIMIT))
                        }
                    },
                )
            TerminalChatSession(agent, sessionId, browserMcpTools)
        }.getOrElse { failure ->
            browserMcpTools?.closeAfterFailure(failure)
            throw failure
        }
    }

    private fun terminalSystemPrompt(browserMcpTools: BrowserMcpTools?): String = if (browserMcpTools == null) {
        AGENT_SYSTEM_PROMPT
    } else {
        AGENT_SYSTEM_PROMPT + BROWSER_MCP_SYSTEM_PROMPT
    }
}

/** Terminal chat and staged flows share this `read`/`write`/`bash` tool surface plus any MCP tools. */
internal suspend fun engHubToolRegistry(
    workingDirectory: String,
    browserMcpTools: BrowserMcpTools?,
): ToolRegistry {
    val mcpTools = browserMcpTools?.toolRegistry() ?: ToolRegistry.EMPTY
    return readToolRegistry(workingDirectory) +
        writeToolRegistry(workingDirectory) +
        shellToolRegistry(workingDirectory) +
        mcpTools
}

internal suspend fun BrowserMcpTools.closeAfterFailure(failure: Throwable) {
    runCatching { close() }.exceptionOrNull()?.let(failure::addSuppressed)
}

/** Koog strategy with history compression, shared by terminal chat and staged flows. */
internal fun engHubChatStrategy(): AIAgentGraphStrategy<String, String> = singleRunStrategyWithHistoryCompression(
    config = HistoryCompressionConfig(
        isHistoryTooBig = { prompt -> prompt.messages.size > HISTORY_MESSAGE_LIMIT },
        compressionStrategy = HistoryCompressionStrategy.FromLastNMessages(HISTORY_MESSAGES_TO_KEEP),
    ),
)
