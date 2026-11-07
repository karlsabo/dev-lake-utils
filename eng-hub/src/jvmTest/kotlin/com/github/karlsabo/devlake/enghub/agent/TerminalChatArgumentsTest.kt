package com.github.karlsabo.devlake.enghub.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class TerminalChatArgumentsTest {
    @Test
    fun `omitting the options uses defaults`() {
        val arguments = TerminalChatArguments.parse(emptyArray())

        assertNull(arguments.mcpConfigPath)
        assertEquals(LM_STUDIO_BASE_URL, arguments.lmStudioBaseUrl)
        assertNull(arguments.lmStudioApiKeyPath)
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
    fun `accepts a separate LM Studio base URL`() {
        val arguments =
            TerminalChatArguments.parse(arrayOf("--lm-studio-base-url", "http://192.168.1.50:1234/v1"))

        assertEquals("http://192.168.1.50:1234/v1", arguments.lmStudioBaseUrl)
    }

    @Test
    fun `accepts an assigned LM Studio base URL`() {
        val arguments =
            TerminalChatArguments.parse(arrayOf("--lm-studio-base-url=http://192.168.1.50:1234/v1"))

        assertEquals("http://192.168.1.50:1234/v1", arguments.lmStudioBaseUrl)
    }

    @Test
    fun `accepts LM Studio base URL and MCP config together`() {
        val arguments =
            TerminalChatArguments.parse(
                arrayOf(
                    "--lm-studio-base-url",
                    "http://192.168.1.50:1234/v1",
                    "--mcp-config",
                    "/tmp/synthetic-mcp.json",
                    "--lm-studio-api-key-file",
                    "/tmp/synthetic-api-key",
                ),
            )

        assertEquals("http://192.168.1.50:1234/v1", arguments.lmStudioBaseUrl)
        assertEquals("/tmp/synthetic-mcp.json", arguments.mcpConfigPath)
        assertEquals("/tmp/synthetic-api-key", arguments.lmStudioApiKeyPath)
    }

    @Test
    fun `rejects a missing LM Studio base URL`() {
        assertFailsWith<IllegalArgumentException> {
            TerminalChatArguments.parse(arrayOf("--lm-studio-base-url"))
        }
    }

    @Test
    fun `rejects a blank LM Studio base URL`() {
        assertFailsWith<IllegalArgumentException> {
            TerminalChatArguments.parse(arrayOf("--lm-studio-base-url="))
        }
    }

    @Test
    fun `rejects a repeated LM Studio base URL option`() {
        assertFailsWith<IllegalArgumentException> {
            TerminalChatArguments.parse(
                arrayOf(
                    "--lm-studio-base-url",
                    "http://first:1234/v1",
                    "--lm-studio-base-url=http://second:1234/v1",
                ),
            )
        }
    }

    @Test
    fun `accepts a separate LM Studio API key file`() {
        val arguments =
            TerminalChatArguments.parse(arrayOf("--lm-studio-api-key-file", "/tmp/synthetic-api-key"))

        assertEquals("/tmp/synthetic-api-key", arguments.lmStudioApiKeyPath)
    }

    @Test
    fun `accepts an assigned LM Studio API key file`() {
        val arguments =
            TerminalChatArguments.parse(arrayOf("--lm-studio-api-key-file=/tmp/synthetic-api-key"))

        assertEquals("/tmp/synthetic-api-key", arguments.lmStudioApiKeyPath)
    }

    @Test
    fun `rejects a missing LM Studio API key file`() {
        assertFailsWith<IllegalArgumentException> {
            TerminalChatArguments.parse(arrayOf("--lm-studio-api-key-file"))
        }
    }

    @Test
    fun `rejects a blank LM Studio API key file`() {
        assertFailsWith<IllegalArgumentException> {
            TerminalChatArguments.parse(arrayOf("--lm-studio-api-key-file="))
        }
    }

    @Test
    fun `rejects a repeated LM Studio API key file option`() {
        assertFailsWith<IllegalArgumentException> {
            TerminalChatArguments.parse(
                arrayOf(
                    "--lm-studio-api-key-file",
                    "/tmp/first-key",
                    "--lm-studio-api-key-file=/tmp/second-key",
                ),
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
