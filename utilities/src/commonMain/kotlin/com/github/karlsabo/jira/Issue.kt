package com.github.karlsabo.jira

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.json.*

@Serializable
data class Issue(
    val id: String,
    val url: String? = null,
    val iconUrl: String? = null,
    val issueKey: String,
    val title: String? = null,
    val description: String? = null,
    val epicKey: String? = null,
    val type: String? = null,
    val originalType: String? = null,
    val status: String? = null,
    val originalStatus: String? = null,
    @Serializable(with = CustomInstantSerializer::class)
    val resolutionDate: Instant? = null,
    @Serializable(with = CustomInstantSerializer::class)
    val createdDate: Instant? = null,
    @Serializable(with = CustomInstantSerializer::class)
    val updatedDate: Instant? = null,
    val leadTimeMinutes: Long? = null,
    val parentIssueId: String? = null,
    val priority: String? = null,
    val storyPoint: Double? = null,
    val originalEstimateMinutes: Long? = null,
    val timeSpentMinutes: Long? = null,
    val timeRemainingMinutes: Long? = null,
    val creatorId: String? = null,
    val creatorName: String? = null,
    val assigneeId: String? = null,
    val assigneeName: String? = null,
    val severity: String? = null,
    val component: String? = null,
    val originalProject: String? = null,
    val urgency: String? = null,
    val isSubtask: Boolean? = null,
)

fun JsonObject.toIssue(): Issue {
    @Suppress("UNREACHABLE_CODE") val fields = this["fields"]?.jsonObject ?: return error("Missing fields")
    val parent = fields["parent"]?.jsonObject

    return Issue(
        id = this["id"]?.jsonPrimitive?.content ?: error("Missing id"),
        url = this["self"]?.jsonPrimitive?.content,
        iconUrl = fields["issuetype"]?.jsonObject?.get("iconUrl")?.jsonPrimitive?.content,
        issueKey = this["key"]?.jsonPrimitive?.content ?: error("Missing key"),
        title = fields["summary"]?.jsonPrimitive?.content,
        description = fields["description"]?.jsonObject?.toString(),
        epicKey = fields["customfield_10018"]?.jsonObject?.get("key")?.jsonPrimitive?.content,
        type = fields["issuetype"]?.jsonObject?.get("name")?.jsonPrimitive?.content,
        originalType = fields["issuetype"]?.jsonObject?.get("name")?.jsonPrimitive?.content,
        status = fields["status"]?.jsonObject?.get("statusCategory")?.jsonObject?.get("name")?.jsonPrimitive?.content,
        originalStatus = fields["status"]?.jsonObject?.get("name")?.jsonPrimitive?.content,
        resolutionDate = fields["resolutiondate"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.let {
            parseOffsetDateTime(it)
        },
        createdDate = fields["created"]?.jsonPrimitive?.content?.let { parseOffsetDateTime(it) },
        updatedDate = fields["updated"]?.jsonPrimitive?.content?.let { parseOffsetDateTime(it) },
        leadTimeMinutes = null, // Calculated elsewhere
        parentIssueId = parent?.get("id")?.jsonPrimitive?.content,
        priority = fields["priority"]?.jsonObject?.get("name")?.jsonPrimitive?.content,
        storyPoint = fields["customfield_10100"]?.jsonPrimitive?.doubleOrNull,
        originalEstimateMinutes = fields["timeoriginalestimate"]?.jsonPrimitive?.longOrNull,
        timeSpentMinutes = fields["timespent"]?.jsonPrimitive?.longOrNull,
        timeRemainingMinutes = fields["timeestimate"]?.jsonPrimitive?.longOrNull,
        creatorId = fields["creator"]?.jsonObject?.get("accountId")?.jsonPrimitive?.content,
        creatorName = fields["creator"]?.jsonObject?.get("displayName")?.jsonPrimitive?.content,
        assigneeId = fields["assignee"]?.takeIf { it !is JsonNull }?.jsonObject?.get("accountId")
            ?.takeIf { it !is JsonNull }?.jsonPrimitive?.content,
        assigneeName = fields["assignee"]?.takeIf { it !is JsonNull }?.jsonObject?.get("displayName")?.jsonPrimitive?.content,
        severity = fields["customfield_11203"]?.takeIf { it !is JsonNull }?.jsonObject?.get("value")?.jsonPrimitive?.content,
        component = fields["components"]?.jsonArray?.firstOrNull()?.jsonObject?.get("name")?.jsonPrimitive?.content,
        originalProject = fields["project"]?.takeIf { it !is JsonNull }?.jsonObject?.get("key")?.jsonPrimitive?.content,
        urgency = fields["customfield_11202"]?.takeIf { it !is JsonNull }?.jsonObject?.get("value")?.jsonPrimitive?.content,
        isSubtask = fields["issuetype"]?.takeIf { it !is JsonNull }?.jsonObject?.get("subtask")?.jsonPrimitive?.booleanOrNull,
    )
}
fun parseOffsetDateTime(raw: String): Instant {
    val dateTimePart = raw.substring(0, 23) // "2025-03-29T15:17:30.431"
    val offsetPart = raw.substring(23)      // "-0400"

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