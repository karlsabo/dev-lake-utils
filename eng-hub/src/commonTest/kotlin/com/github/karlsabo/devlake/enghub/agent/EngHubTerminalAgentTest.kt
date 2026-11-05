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
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.writeString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
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
                    captured.tools.any { tool -> tool.name == "read" }
                },
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
}

private class ScriptedLlmClient : LLMClient() {
    val prompts = mutableListOf<CapturedPrompt>()
    var closed = false

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

    override suspend fun moderate(
        prompt: Prompt,
        model: LLModel,
    ): ModerationResult = error("Moderation is not used")

    override fun llmProvider(): LLMProvider = LLMProvider.OpenAI

    override fun close() {
        closed = true
    }
}

private data class CapturedPrompt(
    val prompt: Prompt,
    val tools: List<ToolDescriptor>,
)

private fun Prompt.toolResults(): List<MessagePart.Tool.Result> = messages.flatMap { message ->
    message.parts.filterIsInstance<MessagePart.Tool.Result>()
}
