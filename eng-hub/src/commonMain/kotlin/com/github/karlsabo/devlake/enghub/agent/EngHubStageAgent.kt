package com.github.karlsabo.devlake.enghub.agent

import ai.koog.agents.chatMemory.feature.ChatMemory
import ai.koog.agents.core.agent.AIAgent
import ai.koog.prompt.executor.model.PromptExecutor
import com.nimblygames.llm.agent.QWEN_3_8_27B
import kotlinx.io.files.Path

private const val STAGE_MAX_AGENT_ITERATIONS = 60

private const val IMPLEMENTOR_SYSTEM_PROMPT = """
You are Eng Hub's implementor stage in an implement-then-review workflow.
Use the read, write, and bash tools to make the requested change in the current working tree.
Relative paths resolve from the terminal's launch working directory.
Do not ask questions and do not describe the change outside the report.
When the work is done, reply with only one JSON object:
{"files":["relative/path.kt"],"what":"what changed","why":"why it changed"}
files lists every file you touched and may be empty when the story needs no edit.
what and why must be nonblank.
"""

private const val REVIEWER_SYSTEM_PROMPT = """
You are Eng Hub's independent reviewer stage in an implement-then-review workflow.
Use the read, write, and bash tools to inspect the current working tree.
Judge the change from the files themselves; an implementor report is not proof of work.
Do not ask questions and do not change files.
When the review is done, reply with only one JSON object:
{"verdict":"pass","findings":[]}
verdict is "pass" or "needs_changes". findings lists concrete problems, one per entry.
"""

/**
 * Builds one isolated Koog conversation per implement-then-review stage.
 *
 * Each call creates a new agent with its own chat memory, so an implementor conversation never
 * reaches the reviewer. The stage owns optional [browserMcpTools] and closes them with its session.
 */
object EngHubStageAgent {
    fun sessionId(stage: StageName): String = "eng-hub-stage-${stage.id}"

    suspend fun create(
        promptExecutor: PromptExecutor,
        workingDirectory: String,
        stage: StageName,
        browserMcpTools: BrowserMcpTools? = null,
    ): ChatSession {
        require(Path(workingDirectory).isAbsolute) {
            "workingDirectory must be absolute: $workingDirectory"
        }

        return runCatching {
            val agent =
                AIAgent(
                    promptExecutor = promptExecutor,
                    llmModel = QWEN_3_8_27B,
                    strategy = engHubChatStrategy(),
                    toolRegistry = engHubToolRegistry(workingDirectory, browserMcpTools),
                    systemPrompt = stageSystemPrompt(stage, browserMcpTools),
                    maxIterations = STAGE_MAX_AGENT_ITERATIONS,
                    installFeatures = {
                        install(ChatMemory.Feature) {
                            addPreProcessor(BoundedChatHistoryPreProcessor(HISTORY_MESSAGE_LIMIT))
                        }
                    },
                )
            TerminalChatSession(agent, sessionId(stage), browserMcpTools)
        }.getOrElse { failure ->
            browserMcpTools?.closeAfterFailure(failure)
            throw failure
        }
    }

    private fun stageSystemPrompt(
        stage: StageName,
        browserMcpTools: BrowserMcpTools?,
    ): String {
        val role = when (stage) {
            StageName.IMPLEMENTOR -> IMPLEMENTOR_SYSTEM_PROMPT
            StageName.REVIEWER -> REVIEWER_SYSTEM_PROMPT
        }
        return if (browserMcpTools == null) role else role + BROWSER_MCP_SYSTEM_PROMPT
    }
}
