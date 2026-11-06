package com.github.karlsabo.devlake.enghub.agent

import com.nimblygames.llm.agent.buildOpenAICompatibleExecutor
import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val arguments = parseArguments(args)
    val workingDirectory = System.getProperty("user.dir")
    val executor =
        buildOpenAICompatibleExecutor(
            LM_STUDIO_API_KEY,
            LM_STUDIO_BASE_URL,
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

private fun loadBrowserMcpTools(configPath: String): BrowserMcpTools {
    val config =
        try {
            Files.readString(Path.of(configPath))
        } catch (failure: IOException) {
            throw IllegalArgumentException("Cannot read MCP config '$configPath': ${failure.message}", failure)
        }
    return BrowserMcpTools.fromConfigJson(config)
}

private class SystemTerminalIO : TerminalIO {
    private val reader: BufferedReader = System.`in`.bufferedReader()

    override fun readLine(): String? = reader.readLine()

    override fun write(text: String) {
        System.out.print(text)
        System.out.flush()
    }
}
