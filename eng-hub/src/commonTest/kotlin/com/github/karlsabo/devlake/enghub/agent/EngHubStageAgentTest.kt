package com.github.karlsabo.devlake.enghub.agent

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readString
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
    fun `implementor edits Greeting kt and a fresh reviewer passes it`() = runTest {
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

        assertEquals(3, client.prompts.size)
        client.prompts.forEach { captured ->
            assertEquals(
                listOf("read", "write", "bash"),
                captured.tools.map { tool -> tool.name },
            )
        }
        assertTrue(client.prompts[0].systemText().contains("implementor"))
        assertTrue(client.prompts[1].systemText().contains("implementor"))
        assertTrue(client.prompts[2].systemText().contains("reviewer"))
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

            else -> Message.Assistant(reviewResponse, ResponseMetaInfo.Empty)
        }
    }
}

private fun CapturedPrompt.systemText(): String = prompt.messages
    .filterIsInstance<Message.System>()
    .joinToString("\n") { message -> message.textContent() }

private fun readFile(path: Path): String = SystemFileSystem.source(path).buffered().use { it.readString() }
