package com.github.karlsabo.jira

import com.github.karlsabo.tools.lenientJson
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

@Serializable
data class Comment(
    val self: String,
    val id: String,
    val author: JiraUser,
    val body: CommentBody,
    val updateAuthor: JiraUser,
    @Serializable(with = CustomInstantSerializer::class)
    val created: Instant,
    @Serializable(with = CustomInstantSerializer::class)
    val updated: Instant,
    val parentId: Long? = null,
    val jsdPublic: Boolean? = null,
)

@Serializable
data class JiraUser(
    val self: String,
    val accountId: String,
    val emailAddress: String? = null,
    val avatarUrls: JiraAvatarUrls,
    val displayName: String,
    val active: Boolean,
    val timeZone: String,
    val accountType: String
)

@Serializable
data class JiraAvatarUrls(
    @SerialName("48x48") val size48x48: String,
    @SerialName("24x24") val size24x24: String,
    @SerialName("16x16") val size16x16: String,
    @SerialName("32x32") val size32x32: String
)


@Serializable
data class CommentBody(
    val type: String,
    val version: Int,
    val content: List<ContentNode>
)

@Serializable
data class ContentNode(
    val type: String,
    val content: List<ContentNode>? = null,
    val attrs: ContentAttrs? = null,
    val text: String? = null
)

@Serializable
data class ContentAttrs(
    val id: String? = null,
    val text: String? = null,
    val accessLevel: String? = null,
    val shortName: String? = null
)

/**
 * Parses a kotlinx.serialization.JsonObject representing a Jira comment
 * into a [Comment] data class.
 *
 * This function assumes the receiver [JsonObject] is the JSON object for a
 * single comment, like the example provided in the prompt.
 *
 * @receiver The [JsonObject] to parse.
 * @return The parsed [Comment].
 * @throws kotlinx.serialization.SerializationException if parsing fails due to mismatched types or structure.
 */
fun JsonObject.toComment(): Comment {
    return lenientJson.decodeFromJsonElement<Comment>(this)
}

/**
 * Converts the Atlassian Document Format within a CommentBody into a plain text string.
 * It attempts to create a readable text blob by handling common node types like
 * paragraphs, text, mentions, emojis, lists, and headings.
 *
 * @return A String representing the plain text content of the comment body.
 */
fun CommentBody.toPlainText(): String {
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
            } else if (sb.isEmpty() && node.content.isNullOrEmpty()){
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
            } else if (sb.isEmpty() && node.content?.isNotEmpty() == true){
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
            } else if (sb.isNotEmpty() && !sb.endsWith("\n")){
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
