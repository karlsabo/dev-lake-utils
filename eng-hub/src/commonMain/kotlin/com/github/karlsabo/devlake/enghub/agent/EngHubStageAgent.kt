package com.github.karlsabo.devlake.enghub.agent

import ai.koog.agents.chatMemory.feature.ChatMemory
import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.executor.model.PromptExecutor
import com.nimblygames.llm.agent.QWEN_3_8_27B
import com.nimblygames.skills.readToolRegistry
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
The task lists the verified working-tree changes that ordinary code read from git after the implementor
finished, including deleted paths, added paths, and paths the implementor left out of its report.
Treat that list and the files themselves as the work under review; the implementor report is not proof.
Use the read tool to inspect the files you need. You cannot change files; this stage has no write or bash tools.
Never pass a change you have not seen in the verified list or in the files you read.
Do not ask questions.
When the review is done, reply with only one JSON object:
{"verdict":"pass","findings":[]}
verdict is "pass" or "needs_changes". findings lists concrete problems, one per entry.
A pass has no findings; needs_changes has at least one finding.
"""

/**
 * Builds one isolated Koog conversation per stage, each with its own chat memory, so an implementor
 * conversation never reaches the reviewer.
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
                    toolRegistry = stageToolRegistry(workingDirectory, stage, browserMcpTools),
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
        return if (stage == StageName.IMPLEMENTOR && browserMcpTools != null) {
            role + BROWSER_MCP_SYSTEM_PROMPT
        } else {
            role
        }
    }
}

/**
 * Tools for one stage: the implementor may change the working tree, the reviewer may only inspect it.
 *
 * The reviewer gets `read` only, because `bash` can write even without `write`. MCP servers are not trusted
 * to be read-only, so only the implementor receives their tools.
 */
internal suspend fun stageToolRegistry(
    workingDirectory: String,
    stage: StageName,
    browserMcpTools: BrowserMcpTools?,
): ToolRegistry = when (stage) {
    StageName.IMPLEMENTOR -> engHubToolRegistry(workingDirectory, browserMcpTools)
    StageName.REVIEWER -> readToolRegistry(workingDirectory)
}
