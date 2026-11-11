package com.github.karlsabo.devlake.enghub.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StageReportTest {
    @Test
    fun `decodes an implementation report with touched files`() {
        val report =
            decodeImplementationReport(
                """{"files":["Greeting.kt"],"what":"Updated greeting","why":"Story asks to greet Ada"}""",
            )

        assertEquals(listOf("Greeting.kt"), report.files)
        assertEquals("Updated greeting", report.what)
        assertEquals("Story asks to greet Ada", report.why)
    }

    @Test
    fun `accepts an empty file list for no-edit work`() {
        val report =
            decodeImplementationReport(
                """{"files":[],"what":"No edit needed","why":"The greeting already matches"}""",
            )

        assertTrue(report.files.isEmpty())
    }

    @Test
    fun `rejects malformed implementor JSON`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                decodeImplementationReport("Updated greeting")
            }

        assertTrue(failure.message.orEmpty().contains("Implementor response is not a valid report"))
    }

    @Test
    fun `rejects an implementor report with missing fields`() {
        assertFailsWith<IllegalArgumentException> {
            decodeImplementationReport("""{"files":["Greeting.kt"],"what":"Updated greeting"}""")
        }
    }

    @Test
    fun `rejects an implementor report with unknown fields`() {
        assertFailsWith<IllegalArgumentException> {
            decodeImplementationReport(
                """{"files":[],"what":"No edit","why":"Already done","extra":true}""",
            )
        }
    }

    @Test
    fun `rejects blank implementor report contents`() {
        val blankWhat =
            assertFailsWith<IllegalArgumentException> {
                decodeImplementationReport("""{"files":[],"what":" ","why":"Already done"}""")
            }
        val blankWhy =
            assertFailsWith<IllegalArgumentException> {
                decodeImplementationReport("""{"files":[],"what":"No edit","why":""}""")
            }
        val blankFile =
            assertFailsWith<IllegalArgumentException> {
                decodeImplementationReport("""{"files":[" "],"what":"No edit","why":"Already done"}""")
            }

        assertTrue(blankWhat.message.orEmpty().contains("'what' must not be blank"))
        assertTrue(blankWhy.message.orEmpty().contains("'why' must not be blank"))
        assertTrue(blankFile.message.orEmpty().contains("file entries must not be blank"))
    }

    @Test
    fun `decodes a passing review with no findings`() {
        val report = decodeReviewReport("""{"verdict":"pass","findings":[]}""")

        assertEquals(ReviewVerdict.PASS, report.verdict)
        assertTrue(report.findings.isEmpty())
    }

    @Test
    fun `decodes a needs-changes review with findings`() {
        val report =
            decodeReviewReport(
                """{"verdict":"needs_changes","findings":["Greeting.kt still says Hello"]}""",
            )

        assertEquals(ReviewVerdict.NEEDS_CHANGES, report.verdict)
        assertEquals(listOf("Greeting.kt still says Hello"), report.findings)
    }

    @Test
    fun `rejects a pass that reports findings`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                decodeReviewReport(
                    """{"verdict":"pass","findings":["Greeting.kt still says Hello"]}""",
                )
            }

        assertTrue(failure.message.orEmpty().contains("'pass' must not include findings"))
    }

    @Test
    fun `rejects needs changes without findings`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                decodeReviewReport("""{"verdict":"needs_changes","findings":[]}""")
            }

        assertTrue(failure.message.orEmpty().contains("'needs_changes' must include at least one finding"))
    }

    @Test
    fun `rejects an unknown verdict`() {
        assertFailsWith<IllegalArgumentException> {
            decodeReviewReport("""{"verdict":"maybe","findings":[]}""")
        }
    }

    @Test
    fun `rejects blank reviewer findings`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                decodeReviewReport("""{"verdict":"needs_changes","findings":[""]}""")
            }

        assertTrue(failure.message.orEmpty().contains("findings must not be blank"))
    }
}
