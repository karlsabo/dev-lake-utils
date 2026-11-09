package com.github.karlsabo.devlake.enghub.agent

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val CLI_IMPLEMENTATION_RESPONSE =
    """{"files":["Greeting.kt"],"what":"Updated greeting","why":"Story asks to greet Ada"}"""
private const val CLI_REVIEW_RESPONSE = """{"verdict":"pass","findings":[]}"""

class ImplementReviewCliTest {
    @Test
    fun `runs one flow per story and displays the completed review`() = runTest {
        val io = RecordingStoryIo("Change Greeting.kt to greet Ada", EXIT_COMMAND, "ignored")
        val flow = ImplementReviewFlow(SuccessfulStageFactory(), RecordingWorkingTreeChanges())

        ImplementReviewCli(flow, io).run()

        assertTrue(io.output.contains("${IMPLEMENTOR_PREFIX}files: Greeting.kt"))
        assertTrue(io.output.contains("${IMPLEMENTOR_PREFIX}what: Updated greeting"))
        assertTrue(io.output.contains("${IMPLEMENTOR_PREFIX}why: Story asks to greet Ada"))
        assertTrue(io.output.contains("${REVIEWER_PREFIX}pass"))
        assertTrue(io.output.contains("${REVIEWER_PREFIX}no findings"))
        assertFalse(io.output.contains("ignored"))
    }

    @Test
    fun `starts a fresh implement-then-review run for every story`() = runTest {
        val io = RecordingStoryIo("First story", "Second story", EXIT_COMMAND)
        val factory = SuccessfulStageFactory()

        ImplementReviewCli(ImplementReviewFlow(factory, RecordingWorkingTreeChanges()), io).run()

        assertEquals(
            listOf(
                StageName.IMPLEMENTOR,
                StageName.REVIEWER,
                StageName.IMPLEMENTOR,
                StageName.REVIEWER,
            ),
            factory.opened,
        )
    }

    @Test
    fun `blank input is skipped and end of input ends the CLI`() = runTest {
        val io = RecordingStoryIo("", "   ", "Only story")
        val factory = SuccessfulStageFactory()

        ImplementReviewCli(ImplementReviewFlow(factory, RecordingWorkingTreeChanges()), io).run()

        assertEquals(listOf(StageName.IMPLEMENTOR, StageName.REVIEWER), factory.opened)
    }
}

private class SuccessfulStageFactory : StageSessionFactory {
    val opened = mutableListOf<StageName>()

    override suspend fun open(stage: StageName): ChatSession {
        opened += stage
        return object : ChatSession {
            override suspend fun ask(prompt: String): String = when (stage) {
                StageName.IMPLEMENTOR -> CLI_IMPLEMENTATION_RESPONSE
                StageName.REVIEWER -> CLI_REVIEW_RESPONSE
            }

            override suspend fun close() = Unit
        }
    }
}

private class RecordingStoryIo(
    vararg lines: String,
) : TerminalIO {
    private val remaining = lines.toMutableList()
    val output = StringBuilder()

    override fun readLine(): String? = remaining.removeFirstOrNull()

    override fun write(text: String) {
        output.append(text)
    }
}
