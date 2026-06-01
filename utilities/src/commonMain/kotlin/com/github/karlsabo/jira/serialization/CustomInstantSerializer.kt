package com.github.karlsabo.jira.serialization

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder

private const val DATE_TIME_WITH_MILLIS_LENGTH = 23
private const val OFFSET_HOURS_END_INDEX = 3
private const val OFFSET_SIGN_END_INDEX = 1
private const val OFFSET_MINUTES_START_INDEX = 3
private const val OFFSET_MINUTES_END_INDEX = 5
private const val MINUTES_PER_HOUR = 60
private const val MILLIS_PER_MINUTE = 60_000

private val dateOnlyPattern = Regex("\\d{4}-\\d{2}-\\d{2}")

fun parseOffsetDateTime(dateString: String): Instant {
    if (dateString.matches(dateOnlyPattern)) {
        val dateParts = dateString.split("-")
        val year = dateParts[0].toInt()
        val month = dateParts[1].toInt()
        val day = dateParts[2].toInt()

        val local = LocalDateTime(year, month, day, 0, 0, 0, 0)
        return local.toInstant(TimeZone.UTC)
    }

    val dateTimePart = dateString.substring(0, DATE_TIME_WITH_MILLIS_LENGTH)
    val offsetPart = dateString.substring(DATE_TIME_WITH_MILLIS_LENGTH)

    val local = LocalDateTime.parse(dateTimePart)

    val offsetHours = offsetPart.substring(0, OFFSET_HOURS_END_INDEX).toInt()
    val offsetMinutes = offsetPart.substring(0, OFFSET_SIGN_END_INDEX) +
        offsetPart.substring(OFFSET_MINUTES_START_INDEX, OFFSET_MINUTES_END_INDEX)
    val totalOffsetMinutes = offsetHours * MINUTES_PER_HOUR + offsetMinutes.toInt()

    val instantEpochMillis = local.toInstant(TimeZone.UTC).toEpochMilliseconds() -
        (totalOffsetMinutes * MILLIS_PER_MINUTE)

    return Instant.fromEpochMilliseconds(instantEpochMillis)
}

object CustomInstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("kotlinx.datetime.Instant", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Instant {
        val rawString = decoder.decodeString()
        return parseOffsetDateTime(rawString)
    }

    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: Instant) {
        encoder.encodeString(value.toString())
    }
}
