package com.github.karlsabo.devlake.metrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant

class UserLinearProjectsDemoTest {
    @Test
    fun parsesExplicitDateRangeWithFullInclusiveEndDate() {
        val args = parseUserLinearProjectsDemoArguments(
            arrayOf("--user=usr_123", "--start=2026-06-01", "--end=2026-06-30"),
        )

        assertEquals("usr_123", args.userId)
        assertEquals(Instant.parse("2026-06-01T00:00:00Z"), args.startDate)
        assertEquals(Instant.parse("2026-06-30T23:59:59.999999999Z"), args.endDate)
    }

    @Test
    fun rejectsMissingRequiredArguments() {
        val exception = assertFailsWith<IllegalArgumentException> {
            parseUserLinearProjectsDemoArguments(arrayOf("--user=usr_123", "--end=2026-06-30"))
        }

        assertEquals("No --start=YYYY-MM-DD provided", exception.message)
    }
}
