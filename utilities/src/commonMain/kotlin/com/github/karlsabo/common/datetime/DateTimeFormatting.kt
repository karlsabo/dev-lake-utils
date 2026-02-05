package com.github.karlsabo.common.datetime

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Centralized date/time formatting utilities for consistent formatting across the codebase.
 */
object DateTimeFormatting {
    /**
     * Formats an Instant as a compact UTC datetime string: "YYYY-MM-DD HH:MM"
     */
    fun Instant.toCompactUtcDateTime(): String {
        val localDateTime = toLocalDateTime(TimeZone.UTC)
        return "${localDateTime.date} ${localDateTime.hour}:${localDateTime.minute}"
    }

    /**
     * Formats an Instant as an ISO 8601 UTC datetime string: "YYYY-MM-DDTHH:MM:SS"
     */
    fun Instant.toIsoUtcDateTime(): String {
        return toLocalDateTime(TimeZone.UTC).toString()
    }

    /**
     * Converts an Instant to a LocalDate in UTC timezone.
     */
    fun Instant.toUtcDate(): LocalDate {
        return toLocalDateTime(TimeZone.UTC).date
    }

    /**
     * Converts an Instant to a LocalDate in a specified timezone.
     */
    fun Instant.toLocalDate(timeZone: TimeZone): LocalDate {
        return toLocalDateTime(timeZone).date
    }

    /**
     * Parses a date-only string (YYYY-MM-DD) to an Instant at midnight UTC.
     * Useful for Linear's date-only format.
     */
    fun parseDateOnlyToInstant(dateString: String): Instant {
        return Instant.parse("${dateString}T00:00:00Z")
    }

    /**
     * Parses a date string that may or may not include time component.
     * If time is missing, assumes midnight UTC.
     */
    fun parseFlexibleDateToInstant(dateString: String): Instant {
        return try {
            Instant.parse(dateString)
        } catch (_: Exception) {
            parseDateOnlyToInstant(dateString)
        }
    }
}
