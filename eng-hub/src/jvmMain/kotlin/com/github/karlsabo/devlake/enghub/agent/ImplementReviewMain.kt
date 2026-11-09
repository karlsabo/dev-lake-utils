package com.github.karlsabo.devlake.enghub.agent

import ai.koog.prompt.executor.model.PromptExecutor
import com.nimblygames.llm.agent.buildOpenAICompatibleExecutor
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val arguments = parseArguments(args)
    val workingDirectory = System.getProperty("user.dir")
    val apiKey = arguments.lmStudioApiKeyPath?.let(::loadLmStudioApiKey) ?: LM_STUDIO_API_KEY
    val executor =
        buildOpenAICompatibleExecutor(
            apiKey,
            arguments.lmStudioBaseUrl,
            LM_STUDIO_HTTP_MAX_RETRIES,
        )

    try {
        runBlocking {
            val mcpConfigJson = arguments.mcpConfigPath?.let(::readMcpConfigFile)
            val flow =
                ImplementReviewFlow(
                    stageSessionFactory(executor, workingDirectory, mcpConfigJson),
                    GitWorkingTreeChanges(workingDirectory),
                )
            println("Eng Hub implement-then-review. Type $EXIT_COMMAND or press Ctrl-D to quit.")
            println("Working directory: $workingDirectory")
            arguments.mcpConfigPath?.let { path -> println("Browser MCP config: $path") }
            ImplementReviewCli(flow, SystemTerminalIO()).run()
        }
    } finally {
        executor.close()
    }
}

private fun parseArguments(args: Array<String>): TerminalChatArguments = try {
    TerminalChatArguments.parse(args)
} catch (failure: IllegalArgumentException) {
    System.err.println("Eng Hub implement-then-review: ${failure.message}")
    System.err.println(TerminalChatArguments.usage(IMPLEMENT_REVIEW_TASK_NAME))
    exitProcess(2)
}

internal const val IMPLEMENT_REVIEW_TASK_NAME = "implementReviewChat"

/**
 * Opens one isolated stage session per flow stage.
 *
 * When [mcpConfigJson] is present, each stage parses it into its own browser MCP manager so a
 * closed stage cannot take the next stage's tools with it.
 */
internal fun stageSessionFactory(
    promptExecutor: PromptExecutor,
    workingDirectory: String,
    mcpConfigJson: String?,
): StageSessionFactory = StageSessionFactory { stage ->
    val browserMcpTools = mcpConfigJson?.let { config -> BrowserMcpTools.fromConfigJson(config) }
    EngHubStageAgent.create(promptExecutor, workingDirectory, stage, browserMcpTools)
}
