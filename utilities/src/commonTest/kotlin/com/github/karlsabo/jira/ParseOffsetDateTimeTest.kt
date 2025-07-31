package com.github.karlsabo.jira

import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class ParseOffsetDateTimeTest {

    @Test
    fun testParseShortDateFormat() {
        val shortDateString = "2025-09-19"
        val result = parseOffsetDateTime(shortDateString)

        val expected = Instant.parse("2025-09-19T00:00:00Z")
        assertEquals(expected, result, "Short date format should be parsed as midnight UTC")
    }

    @Test
    fun testParseFullDateTimeFormat() {
        val fullDateTimeString = "2025-03-29T15:17:30.431-0400"
        val result = parseOffsetDateTime(fullDateTimeString)

        val expected = Instant.parse("2025-03-29T19:17:30.431Z")
        assertEquals(expected, result, "Full date-time format should be correctly adjusted for timezone")
    }
}
