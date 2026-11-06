package com.github.karlsabo.devlake.enghub.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class TerminalChatArgumentsTest {
    @Test
    fun `omitting the option leaves mcp config unset`() {
        assertNull(TerminalChatArguments.parse(emptyArray()).mcpConfigPath)
    }

    @Test
    fun `accepts a separate config path`() {
        val arguments = TerminalChatArguments.parse(arrayOf("--mcp-config", "/tmp/synthetic-mcp.json"))

        assertEquals("/tmp/synthetic-mcp.json", arguments.mcpConfigPath)
    }

    @Test
    fun `accepts an assigned config path`() {
        val arguments = TerminalChatArguments.parse(arrayOf("--mcp-config=/tmp/synthetic-mcp.json"))

        assertEquals("/tmp/synthetic-mcp.json", arguments.mcpConfigPath)
    }

    @Test
    fun `rejects a missing config path`() {
        assertFailsWith<IllegalArgumentException> {
            TerminalChatArguments.parse(arrayOf("--mcp-config"))
        }
    }

    @Test
    fun `rejects a blank config path`() {
        assertFailsWith<IllegalArgumentException> {
            TerminalChatArguments.parse(arrayOf("--mcp-config="))
        }
    }

    @Test
    fun `rejects a repeated option`() {
        assertFailsWith<IllegalArgumentException> {
            TerminalChatArguments.parse(
                arrayOf("--mcp-config", "/tmp/first.json", "--mcp-config", "/tmp/second.json"),
            )
        }
    }

    @Test
    fun `rejects an unknown argument`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                TerminalChatArguments.parse(arrayOf("--unknown"))
            }

        assertEquals("Unknown argument: --unknown", failure.message)
    }
}
