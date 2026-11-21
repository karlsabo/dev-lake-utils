package com.github.karlsabo.devlake.enghub.agent

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val REPAIR_STORY = "Change Greeting.kt to greet Ada"
private const val REPAIR_IMPLEMENTATION =
    """{"files":["Greeting.kt"],"what":"Updated greeting","why":"Story asks to greet Ada"}"""
private const val REPAIR_REVIEW = """{"verdict":"pass","findings":[]}"""

class StageRepairTest {
    @Test
    fun `repairs a malformed implementor report in its own session`() = runTest {
        val implementor = ScriptedStageSession("Updated greeting", REPAIR_IMPLEMENTATION)
        val opened = mutableListOf<StageName>()
        val flow =
            ImplementReviewFlow(
                StageSessionFactory { stage ->
                    opened += stage
                    when (stage) {
                        StageName.IMPLEMENTOR -> implementor
                        StageName.REVIEWER -> FixedStageSession(REPAIR_REVIEW)
                    }
                },
                RecordingWorkingTreeChanges(),
            )

        val result = flow.run(REPAIR_STORY)

        assertEquals(listOf("Greeting.kt"), result.implementation.files)
        assertEquals(listOf(StageName.IMPLEMENTOR, StageName.REVIEWER), opened)
        assertEquals(2, implementor.prompts.size)
        val repairPrompt = implementor.prompts[1]
        assertTrue(repairPrompt.contains("could not be accepted as the implementor report"))
        assertTrue(repairPrompt.contains("Rejection reason: Implementor response is not a valid report"))
        assertTrue(repairPrompt.contains("\"files\":[\"relative/path.kt\"]"))
        assertTrue(repairPrompt.contains("what and why must be nonblank"))
        assertTrue(
            repairPrompt.contains(implementorTaskPrompt(REPAIR_STORY)),
            "the repair turn must carry the stage request",
        )
        assertTrue(repairPrompt.contains("Updated greeting"), "the repair turn must carry the rejected reply")
    }

    @Test
    fun `repairs a domain-invalid implementor report with the precise reason`() = runTest {
        val implementor =
            ScriptedStageSession(
                """{"files":["Greeting.kt"],"what":" ","why":"Story asks to greet Ada"}""",
                REPAIR_IMPLEMENTATION,
            )
        val flow = ImplementReviewFlow(
            reviewerStageFactory(implementor, FixedStageSession(REPAIR_REVIEW)),
            RecordingWorkingTreeChanges(),
        )

        val result = flow.run(REPAIR_STORY)

        assertEquals("Updated greeting", result.implementation.what)
        assertEquals(2, implementor.prompts.size)
        assertTrue(
            implementor.prompts[1]
                .contains("Rejection reason: Implementor report 'what' must not be blank."),
        )
    }

    @Test
    fun `repairs a malformed reviewer report without rerunning the implementor`() = runTest {
        val implementor = ScriptedStageSession(REPAIR_IMPLEMENTATION)
        val reviewer = ScriptedStageSession("looks fine", REPAIR_REVIEW)
        val flow = ImplementReviewFlow(reviewerStageFactory(implementor, reviewer), RecordingWorkingTreeChanges())

        val result = flow.run(REPAIR_STORY)

        assertEquals(ReviewVerdict.PASS, result.review.verdict)
        assertEquals(1, implementor.prompts.size, "a reviewer repair must not re-run the implementor")
        assertEquals(2, reviewer.prompts.size)
        val repairPrompt = reviewer.prompts[1]
        assertTrue(repairPrompt.contains("could not be accepted as the reviewer report"))
        assertTrue(repairPrompt.contains("Rejection reason: Reviewer response is not a valid report"))
        assertTrue(repairPrompt.contains("\"verdict\":\"pass\""))
        assertTrue(repairPrompt.contains("A pass has no findings"))
        val reviewerTask =
            reviewerTaskPrompt(
                REPAIR_STORY,
                decodeImplementationReport(REPAIR_IMPLEMENTATION),
                greetingReport(),
            )
        assertTrue(repairPrompt.contains(reviewerTask), "the repair turn must carry the stage request")
        assertTrue(repairPrompt.contains("looks fine"), "the repair turn must carry the rejected reply")
    }

    @Test
    fun `repairs a domain-invalid reviewer report with the precise reason`() = runTest {
        val reviewer =
            ScriptedStageSession(
                """{"verdict":"pass","findings":["Greeting.kt still says Hello"]}""",
                REPAIR_REVIEW,
            )
        val flow = ImplementReviewFlow(
            reviewerStageFactory(ScriptedStageSession(REPAIR_IMPLEMENTATION), reviewer),
            RecordingWorkingTreeChanges(),
        )

        flow.run(REPAIR_STORY)

        assertTrue(
            reviewer.prompts[1].contains("Rejection reason: Reviewer report 'pass' must not include findings."),
        )
    }

    @Test
    fun `fails after the repair budget is exhausted`() = runTest {
        val implementor = ScriptedStageSession("not a report", "still not a report")
        val opened = mutableListOf<StageName>()
        val flow =
            ImplementReviewFlow(
                StageSessionFactory { stage ->
                    opened += stage
                    implementor
                },
                RecordingWorkingTreeChanges(),
                repairBudget = 1,
            )

        val failure = assertFailsWith<IllegalArgumentException> { flow.run(REPAIR_STORY) }

        assertTrue(failure.message.orEmpty().contains("Implementor response is not a valid report"))
        assertEquals(2, implementor.prompts.size, "one initial reply plus one repair reply")
        assertEquals(listOf(StageName.IMPLEMENTOR), opened, "the reviewer must not open after exhaustion")
    }

    @Test
    fun `does not repair a non-validation failure`() = runTest {
        var asks = 0
        val flow =
            ImplementReviewFlow(
                StageSessionFactory {
                    object : ChatSession {
                        override suspend fun ask(prompt: String): String {
                            asks++
                            error("LM Studio request failed")
                        }

                        override suspend fun close() = Unit
                    }
                },
                RecordingWorkingTreeChanges(),
                repairBudget = 3,
            )

        val failure = assertFailsWith<IllegalStateException> { flow.run(REPAIR_STORY) }

        assertEquals("LM Studio request failed", failure.message)
        assertEquals(1, asks, "a transport or executor failure must not receive a repair turn")
    }

    @Test
    fun `rejects a negative repair budget`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                ImplementReviewFlow(
                    StageSessionFactory { error("the factory must not open for an invalid budget") },
                    RecordingWorkingTreeChanges(),
                    repairBudget = -1,
                )
            }

        assertTrue(failure.message.orEmpty().contains("repairBudget must not be negative"))
    }
}

private fun reviewerStageFactory(
    implementor: ChatSession,
    reviewer: ChatSession,
): StageSessionFactory = StageSessionFactory { stage ->
    when (stage) {
        StageName.IMPLEMENTOR -> implementor
        StageName.REVIEWER -> reviewer
    }
}

private class ScriptedStageSession(
    vararg responses: String,
) : ChatSession {
    private val pending = responses.toMutableList()
    val prompts = mutableListOf<String>()

    override suspend fun ask(prompt: String): String {
        prompts += prompt
        return pending.removeFirstOrNull() ?: error("the stage was asked for more replies than scripted")
    }

    override suspend fun close() = Unit
}
