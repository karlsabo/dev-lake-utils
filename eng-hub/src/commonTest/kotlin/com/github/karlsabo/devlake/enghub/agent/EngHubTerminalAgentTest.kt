package com.github.karlsabo.devlake.enghub.agent

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import com.nimblygames.mcp.api.McpManager
import com.nimblygames.mcp.api.McpTool
import com.nimblygames.mcp.client.DefaultMcpManager
import com.nimblygames.mcp.client.McpServerSession
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EngHubTerminalAgentTest {
    @Test
    fun `reads local file and recalls the fact in the same session`() = runTest {
        val directory = Path(SystemTemporaryDirectory, "eng-hub-terminal-${Random.nextLong()}")
        SystemFileSystem.createDirectories(directory)
        val notes = Path(directory, "notes.txt")
        val client = ScriptedLlmClient()
        val executor = MultiLLMPromptExecutor(client)
        val chat = EngHubTerminalAgent.create(executor, directory.toString(), "test-session")
        try {
            SystemFileSystem.sink(notes).buffered().use { it.writeString("Release on Friday") }

            assertEquals(
                "Release on Friday",
                chat.ask("Read notes.txt and report the fact."),
            )
            assertEquals(
                "The release is on Friday.",
                chat.ask("What did the follow-up say about the release day?"),
            )

            assertEquals(3, client.prompts.size)
            assertTrue(
                client.prompts.all { captured ->
                    captured.tools.map { tool -> tool.name } == listOf("read", "write")
                },
                "omitting MCP config must leave the Skills read and write tools",
            )
            assertEquals(
                listOf("Release on Friday"),
                client.prompts[1].prompt.toolResults().map { result -> result.output },
            )
            val followUp = client.prompts[2].prompt
            assertTrue(
                followUp.messages.any { message ->
                    message is Message.User && message.textContent().contains("Read notes.txt")
                },
            )
            assertTrue(
                followUp.messages.any { message ->
                    message is Message.Assistant && message.textContent() == "Release on Friday"
                },
            )
        } finally {
            chat.close()
            executor.close()
            SystemFileSystem.delete(notes, mustExist = false)
            SystemFileSystem.delete(directory)
        }
        assertTrue(client.closed)
    }

    @Test
    fun `writes summary file from an empty launch directory through the write tool`() = runTest {
        val directory = Path(SystemTemporaryDirectory, "eng-hub-terminal-${Random.nextLong()}")
        SystemFileSystem.createDirectories(directory)
        val summary = Path(directory, "summary.txt")
        val client = WriteScriptedLlmClient("Release on Friday", "Created summary.txt.")
        val executor = MultiLLMPromptExecutor(client)
        val chat = EngHubTerminalAgent.create(executor, directory.toString(), "write-session")
        try {
            assertEquals("Created summary.txt.", chat.ask("Write summary.txt containing Release on Friday"))

            assertEquals(2, client.prompts.size)
            assertTrue(
                client.prompts.all { captured ->
                    captured.tools.map { tool -> tool.name } == listOf("read", "write")
                },
            )
            assertEquals("write", client.calledTool)
            assertEquals(
                listOf("Wrote $summary"),
                client.prompts.last().prompt.toolResults().map { result -> result.output },
            )
            assertEquals("Release on Friday", readFile(summary))
        } finally {
            chat.close()
            executor.close()
            SystemFileSystem.delete(summary, mustExist = false)
            SystemFileSystem.delete(directory)
        }
        assertTrue(client.closed)
    }

    @Test
    fun `write tool replaces an existing file's entire contents`() = runTest {
        val directory = Path(SystemTemporaryDirectory, "eng-hub-terminal-${Random.nextLong()}")
        SystemFileSystem.createDirectories(directory)
        val summary = Path(directory, "summary.txt")
        SystemFileSystem.sink(summary).buffered().use { it.writeString("Old contents") }
        val client = WriteScriptedLlmClient("Release on Friday", "Updated summary.txt.")
        val executor = MultiLLMPromptExecutor(client)
        val chat = EngHubTerminalAgent.create(executor, directory.toString(), "overwrite-session")
        try {
            assertEquals("Updated summary.txt.", chat.ask("Replace summary.txt with Release on Friday"))

            assertEquals("write", client.calledTool)
            assertEquals("Release on Friday", readFile(summary))
        } finally {
            chat.close()
            executor.close()
            SystemFileSystem.delete(summary, mustExist = false)
            SystemFileSystem.delete(directory)
        }
        assertTrue(client.closed)
    }

    @Test
    fun `browser mcp tool reports the page title and closes its session`() = runTest {
        val directory = Path(SystemTemporaryDirectory, "eng-hub-terminal-${Random.nextLong()}")
        SystemFileSystem.createDirectories(directory)
        val browserSession = FakeBrowserSession()
        val mcpTools =
            BrowserMcpTools(
                DefaultMcpManager(
                    parseBrowserMcpConfig(BROWSER_MCP_CONFIG),
                    sessionFactory = { browserSession },
                ),
            )
        val client = BrowserScriptedLlmClient()
        val executor = MultiLLMPromptExecutor(client)
        val chat =
            EngHubTerminalAgent.create(
                executor,
                directory.toString(),
                "browser-session",
                mcpTools,
            )
        try {
            assertEquals(
                "Example Domain",
                chat.ask("Open https://example.com and report its title."),
            )

            assertEquals(2, client.prompts.size)
            assertEquals(
                listOf("read", "write", "browser_navigate"),
                client.prompts.first().tools.map { tool -> tool.name },
            )
            assertEquals(
                buildJsonObject { put("url", "https://example.com") },
                browserSession.calledArguments,
            )
            assertEquals("browser_navigate", browserSession.calledName)
            assertTrue(
                client.prompts.last().prompt.toolResults().single().output.contains("Example Domain"),
            )
        } finally {
            chat.close()
            executor.close()
            SystemFileSystem.delete(directory)
        }
        assertTrue(browserSession.closed, "the MCP manager must close its browser session")
        assertTrue(client.closed)
    }

    @Test
    fun `closes mcp tools when agent construction fails after tool discovery`() = runTest {
        val directory = Path(SystemTemporaryDirectory, "eng-hub-terminal-${Random.nextLong()}")
        SystemFileSystem.createDirectories(directory)
        var closed = false
        val manager =
            object : McpManager {
                override suspend fun listTools(): List<McpTool> = error("browser server failed to start")

                override suspend fun callTool(
                    name: String,
                    arguments: JsonObject,
                ): CallToolResult = error("not used")

                override suspend fun close() {
                    closed = true
                }
            }
        val client = TextOnlyLlmClient()
        val executor = MultiLLMPromptExecutor(client)
        try {
            val failure =
                assertFailsWith<IllegalStateException> {
                    EngHubTerminalAgent.create(
                        executor,
                        directory.toString(),
                        "startup-failure",
                        BrowserMcpTools(manager),
                    )
                }

            assertEquals("browser server failed to start", failure.message)
            assertTrue(closed, "a failed startup must close the MCP manager")
        } finally {
            executor.close()
            SystemFileSystem.delete(directory)
        }
        assertTrue(client.closed)
    }

    @Test
    fun `bounds text-only history that never runs a tool`() = runTest {
        val directory = Path(SystemTemporaryDirectory, "eng-hub-terminal-${Random.nextLong()}")
        SystemFileSystem.createDirectories(directory)
        val client = TextOnlyLlmClient()
        val executor = MultiLLMPromptExecutor(client)
        val chat = EngHubTerminalAgent.create(executor, directory.toString(), "bounded-session")
        try {
            // Thirty text-only turns save 61 messages: the system prompt plus 30 user/assistant pairs.
            repeat(30) { turn -> chat.ask("turn-$turn") }
            chat.ask("turn-30")

            val prompt = client.prompts.last().prompt
            assertTrue(prompt.messages.size <= HISTORY_MESSAGE_LIMIT + 1)
            assertTrue(prompt.messages.first() is Message.System)
            assertTrue(prompt.messages.none { message -> message.textContent() == "turn-0" })
            assertTrue(prompt.messages.any { message -> message.textContent() == "turn-29" })
        } finally {
            chat.close()
            executor.close()
            SystemFileSystem.delete(directory)
        }
        assertTrue(client.closed)
    }
}

private abstract class RecordingLlmClient : LLMClient() {
    val prompts = mutableListOf<CapturedPrompt>()
    var closed = false

    override suspend fun moderate(
        prompt: Prompt,
        model: LLModel,
    ): ModerationResult = error("Moderation is not used")

    override fun llmProvider(): LLMProvider = LLMProvider.OpenAI

    override fun close() {
        closed = true
    }
}

private class TextOnlyLlmClient : RecordingLlmClient() {
    override suspend fun execute(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Message.Assistant {
        prompts += CapturedPrompt(prompt, tools)
        return Message.Assistant("acknowledged", ResponseMetaInfo.Empty)
    }
}

private class ScriptedLlmClient : RecordingLlmClient() {
    override suspend fun execute(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Message.Assistant {
        prompts += CapturedPrompt(prompt, tools)
        val lastUser = prompt.messages.lastOrNull { message -> message is Message.User } as? Message.User
        val toolResult = lastUser?.parts?.filterIsInstance<MessagePart.Tool.Result>()?.singleOrNull()
        return when {
            toolResult != null -> Message.Assistant("Release on Friday", ResponseMetaInfo.Empty)

            lastUser?.textContent()?.contains("follow-up", ignoreCase = true) == true ->
                Message.Assistant("The release is on Friday.", ResponseMetaInfo.Empty)

            else -> Message.Assistant(
                MessagePart.Tool.Call(
                    id = "read-1",
                    tool = "read",
                    args = JsonObject(mapOf("path" to JsonPrimitive("notes.txt"))),
                ),
                ResponseMetaInfo.Empty,
            )
        }
    }
}

private class WriteScriptedLlmClient(
    private val content: String,
    private val reply: String,
) : RecordingLlmClient() {
    var calledTool: String? = null

    override suspend fun execute(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Message.Assistant {
        prompts += CapturedPrompt(prompt, tools)
        return if (prompt.toolResults().isEmpty()) {
            calledTool = "write"
            Message.Assistant(
                MessagePart.Tool.Call(
                    id = "write-1",
                    tool = "write",
                    args = buildJsonObject {
                        put("path", "summary.txt")
                        put("content", content)
                    },
                ),
                ResponseMetaInfo.Empty,
            )
        } else {
            Message.Assistant(reply, ResponseMetaInfo.Empty)
        }
    }
}

private class BrowserScriptedLlmClient : RecordingLlmClient() {
    override suspend fun execute(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Message.Assistant {
        prompts += CapturedPrompt(prompt, tools)
        return if (prompt.toolResults().isEmpty()) {
            Message.Assistant(
                MessagePart.Tool.Call(
                    id = "browser-1",
                    tool = "browser_navigate",
                    args = buildJsonObject { put("url", "https://example.com") },
                ),
                ResponseMetaInfo.Empty,
            )
        } else {
            Message.Assistant("Example Domain", ResponseMetaInfo.Empty)
        }
    }
}

private class FakeBrowserSession : McpServerSession {
    var calledName: String? = null
    var calledArguments: JsonObject? = null
    var closed = false

    override suspend fun listTools(): List<McpTool> = listOf(
        McpTool(
            name = "browser_navigate",
            description = "Open a URL in the browser.",
        ),
    )

    override suspend fun callTool(
        name: String,
        arguments: JsonObject,
    ): CallToolResult {
        calledName = name
        calledArguments = arguments
        return CallToolResult(content = listOf(TextContent("Example Domain")))
    }

    override suspend fun close() {
        closed = true
    }
}

private const val BROWSER_MCP_CONFIG = """
{
  "mcpServers": {
    "browser": {
      "command": "synthetic-browser"
    }
  }
}
"""

private data class CapturedPrompt(
    val prompt: Prompt,
    val tools: List<ToolDescriptor>,
)

private fun Prompt.toolResults(): List<MessagePart.Tool.Result> = messages.flatMap { message ->
    message.parts.filterIsInstance<MessagePart.Tool.Result>()
}

private fun readFile(path: Path): String = SystemFileSystem.source(path).buffered().use { it.readString() }
