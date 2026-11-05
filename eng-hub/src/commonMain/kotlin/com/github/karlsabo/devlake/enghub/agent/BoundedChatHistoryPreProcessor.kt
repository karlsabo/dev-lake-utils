package com.github.karlsabo.devlake.enghub.agent

import ai.koog.agents.chatMemory.feature.ChatMemoryPreProcessor
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart

/**
 * Caps stored chat history so a run starts with at most [maxMessages] messages, system prompt included.
 *
 * Koog checks its history compression threshold only after a tool call, so text-only conversations
 * would otherwise grow without bound. System messages always survive the cap. The retained tail
 * starts at the oldest user text message inside the budget, so a prompt never begins with a reply or
 * with a tool result whose call was dropped. A window without any user text cannot form a complete
 * turn, so only system messages survive it.
 */
internal class BoundedChatHistoryPreProcessor(
    private val maxMessages: Int,
) : ChatMemoryPreProcessor {
    init {
        require(maxMessages > 0) { "maxMessages must be positive: $maxMessages" }
    }

    override fun preprocess(messages: List<Message>): List<Message> {
        if (messages.size <= maxMessages) {
            return messages
        }
        val systemMessages = messages.filterIsInstance<Message.System>()
        val recentMessages = messages
            .filterNot { message -> message is Message.System }
            .takeLast((maxMessages - systemMessages.size).coerceAtLeast(0))
        return systemMessages + trimIncompleteLeadingTurn(recentMessages)
    }

    private fun trimIncompleteLeadingTurn(messages: List<Message>): List<Message> {
        val firstUserText = messages.indexOfFirst { message -> message.isUserText }
        return if (firstUserText >= 0) messages.drop(firstUserText) else emptyList()
    }
}

private val Message.isUserText: Boolean
    get() = this is Message.User && parts.any { part -> part is MessagePart.Text }
