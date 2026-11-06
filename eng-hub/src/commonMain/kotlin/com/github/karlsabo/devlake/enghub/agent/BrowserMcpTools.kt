package com.github.karlsabo.devlake.enghub.agent

import ai.koog.agents.core.tools.ToolRegistry
import com.nimblygames.llm.agent.toToolRegistry
import com.nimblygames.mcp.api.McpManager
import com.nimblygames.mcp.client.DefaultMcpManager
import com.nimblygames.mcp.config.McpConfig
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

internal const val BROWSER_MCP_SERVER_NAME = "browser"

private val mcpConfigJson = Json { ignoreUnknownKeys = true }

/**
 * Owns the configured `browser` MCP server and exposes its tools to the terminal agent.
 *
 * Construction parses and validates the config only; the server process starts on the first
 * [toolRegistry] call. [close] shuts down the manager and every session it started.
 */
class BrowserMcpTools internal constructor(
    private val manager: McpManager,
) {
    suspend fun toolRegistry(): ToolRegistry = manager.toToolRegistry()

    suspend fun close() = manager.close()

    companion object {
        /**
         * Selects the enabled `browser` server from an explicit framework `McpConfig` document.
         * Other configured servers are ignored so the terminal starts only what it exposes.
         */
        internal fun fromConfigJson(configJson: String): BrowserMcpTools {
            val config = parseBrowserMcpConfig(configJson)
            return BrowserMcpTools(DefaultMcpManager(config))
        }
    }
}

internal fun parseBrowserMcpConfig(configJson: String): McpConfig {
    require(configJson.isNotBlank()) { "MCP config file must not be empty." }
    val config =
        try {
            mcpConfigJson.decodeFromString(McpConfig.serializer(), configJson)
        } catch (failure: SerializationException) {
            throw IllegalArgumentException("Invalid MCP config JSON: ${failure.message}", failure)
        }
    val browser =
        requireNotNull(config.mcpServers[BROWSER_MCP_SERVER_NAME]) {
            "MCP config must define a '$BROWSER_MCP_SERVER_NAME' server."
        }
    require(browser.enabled) { "MCP server '$BROWSER_MCP_SERVER_NAME' must be enabled." }
    return McpConfig(mapOf(BROWSER_MCP_SERVER_NAME to browser))
}
