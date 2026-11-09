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
        val flow =
            ImplementReviewFlow(
                RecordingStageFactory(events, prompts),
                RecordingWorkingTreeChanges(events = events),
            )

        val result = flow.run(STORY)

        assertEquals(
            listOf(
                "baseline",
                "open:implementor",
                "ask:implementor",
                "close:implementor",
                "changes:4f0c1a2",
                "open:reviewer",
                "ask:reviewer",
                "close:reviewer",
            ),
            events,
            "the baseline must precede the implementor and the reviewer must open after the working tree is read",
        )
        assertTrue(prompts.getValue(StageName.IMPLEMENTOR).contains(STORY))
        val reviewerPrompt = prompts.getValue(StageName.REVIEWER)
        assertTrue(reviewerPrompt.contains(STORY))
        assertTrue(reviewerPrompt.contains("what: Updated greeting"))
        assertTrue(reviewerPrompt.contains("why: Story asks to greet Ada"))
        assertTrue(reviewerPrompt.contains("M Greeting.kt"), "the reviewer must see the verified change")
        assertEquals(listOf("Greeting.kt"), result.implementation.files)
        assertEquals(ReviewVerdict.PASS, result.review.verdict)
        assertTrue(result.review.findings.isEmpty())
    }

    @Test
    fun `reviewer sees changes the implementor omitted and files it deleted`() = runTest {
        val events = mutableListOf<String>()
        val prompts = mutableMapOf<StageName, String>()
        val changes =
            RecordingWorkingTreeChanges(
                report =
                greetingReport(
                    changes =
                    listOf(
                        WorkingTreeChange("Greeting.kt", WorkingTreeChangeKind.MODIFIED),
                        WorkingTreeChange("Omitted.kt", WorkingTreeChangeKind.MODIFIED),
                        WorkingTreeChange("Removed.kt", WorkingTreeChangeKind.DELETED),
                    ),
                    patch = "--- a/Removed.kt\n+++ /dev/null\n@@ -1 +0,0 @@\n-gone\n",
                ),
                events = events,
            )
        val flow = ImplementReviewFlow(RecordingStageFactory(events, prompts), changes)

        flow.run(STORY)

        val reviewerPrompt = prompts.getValue(StageName.REVIEWER)
        assertTrue(reviewerPrompt.contains("M Omitted.kt"), "an unreported edit must still reach the reviewer")
        assertTrue(reviewerPrompt.contains("D Removed.kt"), "a deletion must reach the reviewer")
        assertTrue(reviewerPrompt.contains("-gone"), "the deleted content must reach the reviewer")
        assertTrue(reviewerPrompt.contains("baseline 4f0c1a2"))
        assertTrue(reviewerPrompt.contains("unverified claim"), "the implementor report must stay a claim")
        assertEquals(1, changes.baselineCalls)
        assertEquals(1, changes.readCalls)
    }

    @Test
    fun `a failed change read does not start the reviewer`() = runTest {
        val opened = mutableListOf<StageName>()
        val changes =
            RecordingWorkingTreeChanges(
                failure = IllegalStateException("Eng Hub could not read the working-tree changes"),
            )
        val flow =
            ImplementReviewFlow(
                StageSessionFactory { stage ->
                    opened += stage
                    FixedStageSession(responseFor(stage))
                },
                changes,
            )

        val failure = assertFailsWith<IllegalStateException> { flow.run(STORY) }

        assertTrue(failure.message.orEmpty().contains("read the working-tree changes"))
        assertEquals(listOf(StageName.IMPLEMENTOR), opened)
        assertEquals(1, changes.readCalls)
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
                RecordingWorkingTreeChanges(),
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
                RecordingWorkingTreeChanges(),
            )

        val failure = assertFailsWith<IllegalArgumentException> { flow.run(STORY) }

        assertTrue(failure.message.orEmpty().contains("Reviewer response is not a valid report"))
    }

    @Test
    fun `rejects a blank story`() = runTest {
        val changes = RecordingWorkingTreeChanges()
        val flow =
            ImplementReviewFlow(
                StageSessionFactory { stage -> FixedStageSession(responseFor(stage)) },
                changes,
            )

        assertFailsWith<IllegalArgumentException> { flow.run("  ") }
        assertEquals(0, changes.baselineCalls, "a blank story must not touch the working tree")
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
