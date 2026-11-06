package com.github.karlsabo.devlake.enghub.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BrowserMcpToolsTest {
    @Test
    fun `selects only the enabled browser server`() {
        val config =
            parseBrowserMcpConfig(
                """
                {
                  "mcpServers": {
                    "browser": {
                      "command": "synthetic-browser",
                      "args": ["--port", "0"],
                      "env": {"SYNTHETIC_VALUE": "not-a-secret"}
                    },
                    "other": {
                      "command": "synthetic-other"
                    }
                  }
                }
                """.trimIndent(),
            )

        assertEquals(setOf(BROWSER_MCP_SERVER_NAME), config.mcpServers.keys)
        val browser = config.mcpServers.getValue(BROWSER_MCP_SERVER_NAME)
        assertEquals("synthetic-browser", browser.command)
        assertEquals(listOf("--port", "0"), browser.args)
        assertEquals(mapOf("SYNTHETIC_VALUE" to "not-a-secret"), browser.env)
    }

    @Test
    fun `rejects a config without a browser server`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                parseBrowserMcpConfig("""{"mcpServers":{"other":{"command":"synthetic"}}}""")
            }

        assertEquals("MCP config must define a 'browser' server.", failure.message)
    }

    @Test
    fun `rejects a disabled browser server`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                parseBrowserMcpConfig("""{"mcpServers":{"browser":{"command":"synthetic","enabled":false}}}""")
            }

        assertEquals("MCP server 'browser' must be enabled.", failure.message)
    }

    @Test
    fun `rejects malformed json`() {
        assertFailsWith<IllegalArgumentException> {
            parseBrowserMcpConfig("""{"mcpServers":""")
        }
    }

    @Test
    fun `rejects a blank config`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                parseBrowserMcpConfig("  ")
            }

        assertEquals("MCP config file must not be empty.", failure.message)
    }
}
