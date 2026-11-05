package com.github.karlsabo.devlake.enghub.agent

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BoundedChatHistoryPreProcessorTest {
    private val system = Message.System("system prompt", RequestMetaInfo.Empty)
    private val firstQuestion = Message.User("question 1", RequestMetaInfo.Empty)
    private val firstAnswer = Message.Assistant("answer 1", ResponseMetaInfo.Empty)
    private val secondQuestion = Message.User("question 2", RequestMetaInfo.Empty)
    private val secondAnswer = Message.Assistant("answer 2", ResponseMetaInfo.Empty)

    @Test
    fun `keeps history unchanged while it fits the limit`() {
        val messages = listOf(system, firstQuestion, firstAnswer)
        assertEquals(messages, BoundedChatHistoryPreProcessor(3).preprocess(messages))
    }

    @Test
    fun `keeps system prompt and newest complete turns`() {
        val messages = listOf(system, firstQuestion, firstAnswer, secondQuestion, secondAnswer)
        assertEquals(
            listOf(system, secondQuestion, secondAnswer),
            BoundedChatHistoryPreProcessor(3).preprocess(messages),
        )
    }

    @Test
    fun `keeps only the system prompt when the limit leaves no room for turns`() {
        val messages = listOf(system, firstQuestion, firstAnswer)
        assertEquals(listOf(system), BoundedChatHistoryPreProcessor(1).preprocess(messages))
    }

    @Test
    fun `drops a leading tool result whose call was cut off`() {
        val toolCall = Message.Assistant(
            MessagePart.Tool.Call(id = "call-1", tool = "read", args = "{}"),
            ResponseMetaInfo.Empty,
        )
        val toolResult = Message.User(
            MessagePart.Tool.Result(id = "call-1", tool = "read", parts = listOf(MessagePart.Text("contents"))),
            RequestMetaInfo.Empty,
        )
        val messages = listOf(system, firstQuestion, firstAnswer, toolCall, toolResult, secondAnswer)
        assertEquals(
            listOf(system, secondAnswer),
            BoundedChatHistoryPreProcessor(3).preprocess(messages),
        )
    }

    @Test
    fun `rejects a non-positive limit`() {
        assertFailsWith<IllegalArgumentException> { BoundedChatHistoryPreProcessor(0) }
    }
}
