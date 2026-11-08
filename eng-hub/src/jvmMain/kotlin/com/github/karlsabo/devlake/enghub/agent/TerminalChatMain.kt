package com.github.karlsabo.devlake.enghub.agent

import com.nimblygames.llm.agent.buildOpenAICompatibleExecutor
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
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
            val browserMcpTools = arguments.mcpConfigPath?.let(::loadBrowserMcpTools)
            val session = EngHubTerminalAgent.create(executor, workingDirectory, browserMcpTools = browserMcpTools)
            try {
                println("Eng Hub terminal chat. Type $EXIT_COMMAND or press Ctrl-D to quit.")
                println("Working directory: $workingDirectory")
                arguments.mcpConfigPath?.let { path -> println("Browser MCP config: $path") }
                TerminalChat(session, SystemTerminalIO()).run()
            } finally {
                session.close()
            }
        }
    } finally {
        executor.close()
    }
}

private fun parseArguments(args: Array<String>): TerminalChatArguments = try {
    TerminalChatArguments.parse(args)
} catch (failure: IllegalArgumentException) {
    System.err.println("Eng Hub terminal chat: ${failure.message}")
    System.err.println(TerminalChatArguments.USAGE)
    exitProcess(2)
}

internal fun loadLmStudioApiKey(path: String): String {
    val apiKey =
        try {
            Files.readString(Path.of(path)).trim()
        } catch (failure: IOException) {
            throw IllegalArgumentException("Cannot read LM Studio API key file '$path': ${failure.message}", failure)
        }
    require(apiKey.isNotEmpty()) { "LM Studio API key file '$path' must not be empty." }
    return apiKey
}

private fun loadBrowserMcpTools(configPath: String): BrowserMcpTools {
    val config = readMcpConfigFile(configPath)
    return BrowserMcpTools.fromConfigJson(config)
}
