package com.github.karlsabo.devlake.enghub.agent

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import com.nimblygames.mcp.api.McpManager
import com.nimblygames.mcp.api.McpTool
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val STAGE_STORY = "Change Greeting.kt to greet Ada"
private const val STAGE_IMPLEMENTATION_RESPONSE =
    """{"files":["Greeting.kt"],"what":"Updated greeting","why":"Story asks to greet Ada"}"""
private const val STAGE_REVIEW_RESPONSE = """{"verdict":"pass","findings":[]}"""

class EngHubStageAgentTest {
    @Test
    fun `implementor edits Greeting kt and a fresh reviewer reads it before passing`() = runTest {
        val directory = Path(SystemTemporaryDirectory, "eng-hub-stage-${Random.nextLong()}")
        SystemFileSystem.createDirectories(directory)
        val greetingPath = Path(directory, "Greeting.kt")
        val greeting = "fun greet() = \"Hello, Ada!\""
        val client =
            ImplementThenReviewLlmClient(
                greeting = greeting,
                implementationResponse = STAGE_IMPLEMENTATION_RESPONSE,
                reviewResponse = STAGE_REVIEW_RESPONSE,
            )
        val executor = MultiLLMPromptExecutor(client)
        try {
            val flow =
                ImplementReviewFlow(
                    StageSessionFactory { stage ->
                        EngHubStageAgent.create(executor, directory.toString(), stage)
                    },
                )

            val result = flow.run(STAGE_STORY)

            assertEquals(greeting, readFile(greetingPath))
            assertEquals(listOf("Greeting.kt"), result.implementation.files)
            assertEquals(ReviewVerdict.PASS, result.review.verdict)
            assertTrue(result.review.findings.isEmpty())
        } finally {
            executor.close()
            SystemFileSystem.delete(greetingPath, mustExist = false)
            SystemFileSystem.delete(directory)
        }
        assertTrue(client.closed)

        assertEquals(4, client.prompts.size)
        val implementorPrompts = client.prompts.take(2)
        val reviewerPrompts = client.prompts.drop(2)
        implementorPrompts.forEach { captured ->
            assertEquals(
                listOf("read", "write", "bash"),
                captured.tools.map { tool -> tool.name },
            )
        }
        reviewerPrompts.forEach { captured ->
            assertEquals(
                listOf("read"),
                captured.tools.map { tool -> tool.name },
                "the reviewer must be inspection-only: no write and no bash",
            )
        }
        assertTrue(implementorPrompts.all { captured -> captured.systemText().contains("implementor") })
        assertTrue(reviewerPrompts.all { captured -> captured.systemText().contains("reviewer") })
        assertTrue(
            reviewerPrompts.last().prompt.toolResults().any { result ->
                result.output.contains("Hello, Ada!")
            },
            "the reviewer must read Greeting.kt before passing",
        )
    }

    @Test
    fun `reviewer never receives tools from an untrusted browser server`() = runTest {
        val directory = Path(SystemTemporaryDirectory, "eng-hub-stage-${Random.nextLong()}")
        SystemFileSystem.createDirectories(directory)
        var discovered = false
        val browserTools = BrowserMcpTools(object : McpManager {
            override suspend fun listTools(): List<McpTool> {
                discovered = true
                return listOf(McpTool(name = "browser_execute_code", description = "Execute code"))
            }

            override suspend fun callTool(name: String, arguments: JsonObject): CallToolResult {
                error("reviewer must not call browser tools")
            }

            override suspend fun close() = Unit
        })
        try {
            val registry = stageToolRegistry(directory.toString(), StageName.REVIEWER, browserTools)

            assertEquals(listOf("read"), registry.tools.map { tool -> tool.name })
            assertTrue(!discovered, "the reviewer must not even start the untrusted server")
        } finally {
            browserTools.close()
            SystemFileSystem.delete(directory)
        }
    }

    @Test
    fun `reviewer starts with its own prompt and no implementor conversation`() = runTest {
        val directory = Path(SystemTemporaryDirectory, "eng-hub-stage-${Random.nextLong()}")
        SystemFileSystem.createDirectories(directory)
        val greetingPath = Path(directory, "Greeting.kt")
        val client =
            ImplementThenReviewLlmClient(
                greeting = "fun greet() = \"Hello, Ada!\"",
                implementationResponse = STAGE_IMPLEMENTATION_RESPONSE,
                reviewResponse = STAGE_REVIEW_RESPONSE,
            )
        val executor = MultiLLMPromptExecutor(client)
        try {
            ImplementReviewFlow(
                StageSessionFactory { stage ->
                    EngHubStageAgent.create(executor, directory.toString(), stage)
                },
            ).run(STAGE_STORY)
        } finally {
            executor.close()
            SystemFileSystem.delete(greetingPath, mustExist = false)
            SystemFileSystem.delete(directory)
        }

        val reviewerPrompt = client.prompts[2].prompt
        assertEquals(
            2,
            reviewerPrompt.messages.size,
            "the reviewer must start with only its own system and user prompt",
        )
        assertTrue(reviewerPrompt.messages.none { message -> message is Message.Assistant })
        assertTrue(reviewerPrompt.toolResults().isEmpty())
        val reviewerUserText = reviewerPrompt.messages.last().textContent()
        assertTrue(reviewerUserText.contains(STAGE_STORY))
        assertTrue(reviewerUserText.contains("what: Updated greeting"))
        assertTrue(reviewerUserText.contains("why: Story asks to greet Ada"))
        assertTrue(
            client.prompts[1].prompt.toolResults().any { result ->
                result.output.contains("Greeting.kt")
            },
            "the implementor stage must actually run the write tool before reporting",
        )
    }
}

private class ImplementThenReviewLlmClient(
    private val greeting: String,
    private val implementationResponse: String,
    private val reviewResponse: String,
) : RecordingLlmClient() {
    override suspend fun execute(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Message.Assistant {
        prompts += CapturedPrompt(prompt, tools)
        return when (prompts.size) {
            1 -> Message.Assistant(
                MessagePart.Tool.Call(
                    id = "write-1",
                    tool = "write",
                    args =
                    buildJsonObject {
                        put("path", "Greeting.kt")
                        put("content", greeting)
                    },
                ),
                ResponseMetaInfo.Empty,
            )

            2 -> Message.Assistant(implementationResponse, ResponseMetaInfo.Empty)

            3 -> Message.Assistant(
                MessagePart.Tool.Call(
                    id = "read-1",
                    tool = "read",
                    args = buildJsonObject { put("path", "Greeting.kt") },
                ),
                ResponseMetaInfo.Empty,
            )

            else -> Message.Assistant(reviewResponse, ResponseMetaInfo.Empty)
        }
    }
}

private fun CapturedPrompt.systemText(): String = prompt.messages
    .filterIsInstance<Message.System>()
    .joinToString("\n") { message -> message.textContent() }

private fun readFile(path: Path): String = SystemFileSystem.source(path).buffered().use { it.readString() }
