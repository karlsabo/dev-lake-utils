package com.github.karlsabo.devlake.enghub.agent

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart

/** Records the prompts a staged flow sends to a scripted model. */
internal abstract class RecordingLlmClient : LLMClient() {
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

internal data class CapturedPrompt(
    val prompt: Prompt,
    val tools: List<ToolDescriptor>,
)

internal fun Prompt.toolResults(): List<MessagePart.Tool.Result> {
    val parts = messages.flatMap { message -> message.parts }
    return parts.filterIsInstance<MessagePart.Tool.Result>()
}
