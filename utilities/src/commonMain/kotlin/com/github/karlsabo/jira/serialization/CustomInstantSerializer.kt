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

fun parseOffsetDateTime(dateString: String): Instant {
    if (dateString.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) {
        val dateParts = dateString.split("-")
        val year = dateParts[0].toInt()
        val month = dateParts[1].toInt()
        val day = dateParts[2].toInt()

        val local = LocalDateTime(year, month, day, 0, 0, 0, 0)
        return local.toInstant(TimeZone.UTC)
    }

    val dateTimePart = dateString.substring(0, 23) // "2025-03-29T15:17:30.431"
    val offsetPart = dateString.substring(23)      // "-0400"

    val local = LocalDateTime.parse(dateTimePart)

    val offsetHours = offsetPart.substring(0, 3).toInt() // e.g. "-04"
    val offsetMinutes = offsetPart.substring(0, 1) + offsetPart.substring(3, 5) // same sign, minutes
    val totalOffsetMinutes = offsetHours * 60 + offsetMinutes.toInt()

    val instantEpochMillis = local.toInstant(TimeZone.UTC).toEpochMilliseconds() -
            (totalOffsetMinutes * 60_000)

    return Instant.fromEpochMilliseconds(instantEpochMillis)
}

object CustomInstantSerializer : KSerializer<Instant> {
    // Describes the shape of the serialized data (a string in this case)
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
