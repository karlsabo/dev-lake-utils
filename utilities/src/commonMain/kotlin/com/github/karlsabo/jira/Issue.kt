package com.github.karlsabo.jira

import io.ktor.http.Url
import io.ktor.http.hostWithPort
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder

//@Serializable
//data class Issue(
//    val id: String,
//    val url: String? = null,
//    val iconUrl: String? = null,
//    @SerialName("key")
//    val issueKey: String,
//    @SerialName("self")
//    val selfUrl: String? = null,
//    val title: String? = null,
//    val description: IssueDescription? = null,
//    val epicKey: String? = null,
//    val type: String? = null,
//    val originalType: String? = null,
//    val status: String? = null,
//    val originalStatus: String? = null,
//    @Serializable(with = CustomInstantSerializer::class)
//    val resolutionDate: Instant? = null,
//    @Serializable(with = CustomInstantSerializer::class)
//    val createdDate: Instant? = null,
//    @Serializable(with = CustomInstantSerializer::class)
//    val updatedDate: Instant? = null,
//    val leadTimeMinutes: Long? = null,
//    val parentIssueId: String? = null,
//    val priority: String? = null,
//    val storyPoint: Double? = null,
//    val originalEstimateMinutes: Long? = null,
//    val timeSpentMinutes: Long? = null,
//    val timeRemainingMinutes: Long? = null,
//    val creatorId: String? = null,
//    val creatorName: String? = null,
//    val assigneeId: String? = null,
//    val assigneeName: String? = null,
//    val severity: String? = null,
//    val component: String? = null,
//    val originalProject: String? = null,
//    val urgency: String? = null,
//    val isSubtask: Boolean? = null,
//    val dueDate: Instant? = null,
//)

@Serializable
data class Issue(
    val id: String,
    val key: String,
    val self: String,
    val fields: JiraIssueFields,
) {
    val htmlUrl: String?
        get() {
            val originalUrl = Url(this.self ?: "https://example.local")
            return "${originalUrl.protocol.name}://${originalUrl.hostWithPort}/browse/$key"
        }
}

@Serializable
data class JiraIssueFields(
    val summary: String? = null,
    val description: IssueDescription? = null,
    @SerialName("issuetype")
    val issueType: IssueType? = null,
    val status: IssueStatus? = null,
    @SerialName("resolutiondate")
    @Serializable(with = CustomInstantSerializer::class)
    val resolutionDate: Instant? = null,
    @Serializable(with = CustomInstantSerializer::class)
    val created: Instant? = null,
    @Serializable(with = CustomInstantSerializer::class)
    val updated: Instant? = null,
    val parent: IssueParent? = null,
    val priority: IssuePriority? = null,
    val customfield_10100: Double? = null, // Story points
    val timeoriginalestimate: Long? = null,
    val timespent: Long? = null,
    val timeestimate: Long? = null,
    val creator: JiraUser? = null,
    val assignee: JiraUser? = null,
    val customfield_11203: CustomFieldValue? = null, // Severity
    val components: List<IssueComponent>? = null,
    val project: IssueProject? = null,
    val customfield_11202: CustomFieldValue? = null, // Urgency
    val customfield_10018: EpicLink? = null, // Epic link
    @Serializable(with = CustomInstantSerializer::class)
    @SerialName("duedate")
    val dueDate: Instant? = null,
)

@Serializable
data class IssueType(
    val id: String? = null,
    val name: String? = null,
    val iconUrl: String? = null,
    val subtask: Boolean? = null,
)

@Serializable
data class IssueStatus(
    val id: String? = null,
    val name: String? = null,
    val statusCategory: StatusCategory? = null,
)

@Serializable
data class StatusCategory(
    val id: String? = null,
    val key: String? = null,
    val name: String? = null,
)

@Serializable
data class IssueParent(
    val id: String? = null,
    val key: String? = null,
    val self: String? = null,
)

@Serializable
data class IssuePriority(
    val id: String? = null,
    val name: String? = null,
)

@Serializable
data class IssueComponent(
    val id: String? = null,
    val name: String? = null,
)

@Serializable
data class IssueProject(
    val id: String? = null,
    val key: String? = null,
    val name: String? = null,
)

@Serializable
data class CustomFieldValue(
    val id: String? = null,
    val value: String? = null,
)

@Serializable
data class EpicLink(
    val id: String? = null,
    val key: String? = null,
    val self: String? = null,
)

// Reusing the Atlassian Document Format classes from Comment.kt
@Serializable
data class IssueDescription(
    val type: String? = null,
    val version: Int? = null,
    val content: List<ContentNode>? = null,
)

/**
 * Converts the Atlassian Document Format within an IssueDescription into a plain text string.
 * It attempts to create a readable text blob by handling common node types like
 * paragraphs, text, mentions, emojis, lists, and headings.
 *
 * @return A String representing the plain text content of the description, or null if the description is null.
 */
fun IssueDescription?.toPlainText(): String? {
    if (this == null || this.content == null) return null

    val parts = mutableListOf<String>()
    this.content.forEach { node ->
        parts.add(extractTextFromNode(node))
    }

    val rawText = parts.joinToString(separator = "")

    return rawText
        .replace(Regex("\n{3,}"), "\n\n")
        .replace(Regex(" +\n"), "\n")
        .trim()
}

private fun extractTextFromNode(node: ContentNode): String {
    val sb = StringBuilder()

    when (node.type) {
        "text" -> {
            sb.append(node.text.orEmpty())
        }

        "mention" -> {
            sb.append(node.attrs?.text.orEmpty())
        }

        "emoji" -> {
            sb.append(node.attrs?.text ?: node.attrs?.shortName.orEmpty())
        }

        "hardBreak" -> {
            sb.append('\n')
        }

        "paragraph", "heading", "blockquote" -> {
            node.content?.forEach { childNode ->
                sb.append(extractTextFromNode(childNode))
            }
            if (sb.isNotEmpty() && !sb.endsWith("\n\n")) {
                if (sb.endsWith("\n")) sb.append('\n') else sb.append("\n\n")
            } else if (sb.isEmpty() && node.content?.isNotEmpty() == true) {
                sb.append("\n\n")
            } else if (sb.isEmpty() && node.content.isNullOrEmpty()) {
                sb.append("\n\n")
            }
        }

        "rule" -> {
            if (sb.isNotEmpty() && !sb.endsWith("\n\n") && !sb.endsWith("\n")) sb.append("\n\n")
            else if (sb.isNotEmpty() && sb.endsWith("\n") && !sb.endsWith("\n\n")) sb.append('\n')
            sb.append("---")
            sb.append("\n\n")
        }

        "codeBlock" -> {
            if (sb.isNotEmpty() && !sb.endsWith("\n\n") && !sb.endsWith("\n")) sb.append("\n\n")
            else if (sb.isNotEmpty() && sb.endsWith("\n") && !sb.endsWith("\n\n")) sb.append('\n')

            node.content?.forEach { lineNode ->
                if (lineNode.type == "text") {
                    sb.append(lineNode.text.orEmpty()).append('\n')
                } else {
                    sb.append(extractTextFromNode(lineNode))
                }
            }
            if (sb.isNotEmpty() && !sb.endsWith("\n\n")) {
                if (sb.endsWith("\n")) sb.append('\n') else sb.append("\n\n")
            } else if (sb.isEmpty() && node.content?.isNotEmpty() == true) {
                sb.append("\n\n")
            }
        }

        "bulletList", "orderedList" -> {
            if (sb.isNotEmpty() && !sb.endsWith("\n\n") && !sb.endsWith("\n")) sb.append("\n\n")
            else if (sb.isNotEmpty() && sb.endsWith("\n") && !sb.endsWith("\n\n")) sb.append('\n')

            node.content?.forEach { listItemNode ->
                sb.append(extractTextFromNode(listItemNode))
            }
            if (sb.isNotEmpty() && sb.endsWith("\n") && !sb.endsWith("\n\n")) {
                sb.append('\n')
            } else if (sb.isNotEmpty() && !sb.endsWith("\n")) {
                sb.append("\n\n")
            }
        }

        "listItem" -> {
            sb.append("- ")
            node.content?.forEach { childNode ->
                sb.append(extractTextFromNode(childNode))
            }
            if (sb.isNotEmpty() && !sb.endsWith("\n")) {
                sb.append('\n')
            }
        }

        else -> {
            node.content?.forEach { childNode ->
                sb.append(extractTextFromNode(childNode))
            }
            if (node.content?.isNotEmpty() == true && sb.isNotEmpty()) {
                if (!sb.endsWith("\n\n")) {
                    if (sb.endsWith("\n")) sb.append('\n') else sb.append("\n\n")
                }
            }
        }
    }
    return sb.toString()
}

fun Issue.isCompleted(): Boolean {
    return fields.resolutionDate != null
}

fun Issue.isMilestone(): Boolean {
    return fields.issueType?.name != null && fields.issueType.name.lowercase() == "epic"
}

fun Issue.isIssueOrBug(): Boolean {
    if (fields.issueType?.name == null) return false
    return when (fields.issueType.name.lowercase()) {
        "bug", "issue", "story", "subtask", "artifact", "task", "vulnerability", "request", "design story", "ds story" -> true
        "epic", "theme", "parent artifact", "r&d initiative", "sub-task" -> false
        else -> {
            val message = "Unhandled issue type `${fields.issueType.name}`, Info: $key, ${fields.summary}"
            print(message)
            throw RuntimeException(message)
        }
    }
}

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
