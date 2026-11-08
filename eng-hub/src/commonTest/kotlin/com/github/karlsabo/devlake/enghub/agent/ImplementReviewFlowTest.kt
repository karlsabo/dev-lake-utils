package com.github.karlsabo.devlake.enghub.agent

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val STORY = "Change Greeting.kt to greet Ada"
private const val IMPLEMENTATION_RESPONSE =
    """{"files":["Greeting.kt"],"what":"Updated greeting","why":"Story asks to greet Ada"}"""
private const val REVIEW_RESPONSE = """{"verdict":"pass","findings":[]}"""

class ImplementReviewFlowTest {
    @Test
    fun `runs the implementor then a fresh reviewer`() = runTest {
        val events = mutableListOf<String>()
        val prompts = mutableMapOf<StageName, String>()
        val flow = ImplementReviewFlow(RecordingStageFactory(events, prompts))

        val result = flow.run(STORY)

        assertEquals(
            listOf(
                "open:implementor",
                "ask:implementor",
                "close:implementor",
                "open:reviewer",
                "ask:reviewer",
                "close:reviewer",
            ),
            events,
            "the implementor session must close before the reviewer opens",
        )
        assertTrue(prompts.getValue(StageName.IMPLEMENTOR).contains(STORY))
        val reviewerPrompt = prompts.getValue(StageName.REVIEWER)
        assertTrue(reviewerPrompt.contains(STORY))
        assertTrue(reviewerPrompt.contains("what: Updated greeting"))
        assertTrue(reviewerPrompt.contains("why: Story asks to greet Ada"))
        assertEquals(listOf("Greeting.kt"), result.implementation.files)
        assertEquals(ReviewVerdict.PASS, result.review.verdict)
        assertTrue(result.review.findings.isEmpty())
    }

    @Test
    fun `rejects an invalid implementor report without starting the reviewer`() = runTest {
        val opened = mutableListOf<StageName>()
        val flow =
            ImplementReviewFlow(
                StageSessionFactory { stage ->
                    opened += stage
                    FixedStageSession("Updated greeting")
                },
            )

        val failure = assertFailsWith<IllegalArgumentException> { flow.run(STORY) }

        assertTrue(failure.message.orEmpty().contains("Implementor response is not a valid report"))
        assertEquals(listOf(StageName.IMPLEMENTOR), opened)
    }

    @Test
    fun `rejects an invalid reviewer report`() = runTest {
        val flow =
            ImplementReviewFlow(
                StageSessionFactory { stage ->
                    when (stage) {
                        StageName.IMPLEMENTOR -> FixedStageSession(IMPLEMENTATION_RESPONSE)
                        StageName.REVIEWER -> FixedStageSession("looks fine")
                    }
                },
            )

        val failure = assertFailsWith<IllegalArgumentException> { flow.run(STORY) }

        assertTrue(failure.message.orEmpty().contains("Reviewer response is not a valid report"))
    }

    @Test
    fun `rejects a blank story`() = runTest {
        val flow = ImplementReviewFlow(StageSessionFactory { stage -> FixedStageSession(responseFor(stage)) })

        assertFailsWith<IllegalArgumentException> { flow.run("  ") }
    }
}

private fun responseFor(stage: StageName): String = when (stage) {
    StageName.IMPLEMENTOR -> IMPLEMENTATION_RESPONSE
    StageName.REVIEWER -> REVIEW_RESPONSE
}

private class RecordingStageFactory(
    private val events: MutableList<String>,
    private val prompts: MutableMap<StageName, String>,
) : StageSessionFactory {
    override suspend fun open(stage: StageName): ChatSession {
        events += "open:${stage.id}"
        return RecordingStageSession(stage, events, prompts)
    }
}

private class RecordingStageSession(
    private val stage: StageName,
    private val events: MutableList<String>,
    private val prompts: MutableMap<StageName, String>,
) : ChatSession {
    override suspend fun ask(prompt: String): String {
        events += "ask:${stage.id}"
        prompts[stage] = prompt
        return responseFor(stage)
    }

    override suspend fun close() {
        events += "close:${stage.id}"
    }
}

private class FixedStageSession(
    private val response: String,
) : ChatSession {
    override suspend fun ask(prompt: String): String = response

    override suspend fun close() = Unit
}
