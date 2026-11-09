package com.github.karlsabo.devlake.enghub.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val PROMPT_STORY = "Change Greeting.kt to greet Ada"
private const val PROMPT_IMPLEMENTATION =
    """{"files":["Greeting.kt"],"what":"Updated greeting","why":"Story asks to greet Ada"}"""

class StagePromptsTest {
    @Test
    fun `reviewer prompt carries verified changes the implementor did not report`() {
        val changes =
            greetingReport(
                changes =
                listOf(
                    WorkingTreeChange("Greeting.kt", WorkingTreeChangeKind.MODIFIED),
                    WorkingTreeChange("Hidden.kt", WorkingTreeChangeKind.MODIFIED),
                    WorkingTreeChange("Removed.kt", WorkingTreeChangeKind.DELETED),
                    WorkingTreeChange("New.kt", WorkingTreeChangeKind.ADDED),
                ),
                patch = "--- a/Removed.kt\n+++ /dev/null\n@@ -1 +0,0 @@\n-gone\n",
            )

        val prompt = reviewerTaskPrompt(PROMPT_STORY, decodeImplementationReport(PROMPT_IMPLEMENTATION), changes)

        assertTrue(prompt.contains("M Greeting.kt"))
        assertTrue(prompt.contains("M Hidden.kt"), "an omitted edit must be discoverable")
        assertTrue(prompt.contains("D Removed.kt"), "a deletion must be discoverable")
        assertTrue(prompt.contains("A New.kt"), "an added path must be discoverable")
        assertTrue(prompt.contains("-gone"), "deleted content must be inspectable")
        assertTrue(prompt.contains("baseline 4f0c1a2"))
        assertTrue(prompt.contains("unverified claim"))
        assertTrue(prompt.contains("read tool"))
    }

    @Test
    fun `reviewer prompt lists an empty change set explicitly`() {
        val prompt =
            reviewerTaskPrompt(
                PROMPT_STORY,
                decodeImplementationReport(PROMPT_IMPLEMENTATION),
                greetingReport(changes = emptyList(), patch = ""),
            )

        assertTrue(prompt.contains("(no working-tree changes)"))
        assertFalse(prompt.contains("Unified diff of tracked changes"))
    }

    @Test
    fun `says when the diff was truncated instead of dropping it silently`() {
        val prompt =
            reviewerTaskPrompt(
                PROMPT_STORY,
                decodeImplementationReport(PROMPT_IMPLEMENTATION),
                greetingReport(patch = "--- a/Greeting.kt\n++++ b/Greeting.kt\n", patchTruncated = true),
            )

        assertTrue(prompt.contains("Diff truncated"))
        assertTrue(
            prompt.contains("deleted file cannot be read"),
            "the notice must say a deleted file's removed content is still in the patch",
        )
    }

    @Test
    fun `says how many changed paths the prompt did not list`() {
        val changes =
            (1..MAX_PROMPT_CHANGED_PATHS + 1).map { index ->
                WorkingTreeChange("File$index.kt", WorkingTreeChangeKind.MODIFIED)
            }
        val prompt =
            reviewerTaskPrompt(
                PROMPT_STORY,
                decodeImplementationReport(PROMPT_IMPLEMENTATION),
                greetingReport(changes = changes),
            )

        assertTrue(prompt.contains("and 1 more changed paths"))
        assertFalse(prompt.contains("File${MAX_PROMPT_CHANGED_PATHS + 1}.kt"))
    }

    @Test
    fun `truncation keeps whole lines and reports that it cut the text`() {
        val text = "line one\nline two\nline three\n"

        val bounded = truncateAtLineBoundary(text, maxChars = 10)

        assertEquals("line one\n", bounded.text)
        assertTrue(bounded.truncated)
    }

    @Test
    fun `truncation leaves text that already fits untouched`() {
        val text = "line one\nline two\n"

        val bounded = truncateAtLineBoundary(text, maxChars = text.length)

        assertEquals(text, bounded.text)
        assertFalse(bounded.truncated)
    }

    @Test
    fun `truncation drops everything when no line boundary fits`() {
        val bounded = truncateAtLineBoundary("one very long line", maxChars = 4)

        assertEquals("", bounded.text)
        assertTrue(bounded.truncated)
    }
}
