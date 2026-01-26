package com.github.karlsabo.jira.extensions

import com.github.karlsabo.jira.model.Comment
import com.github.karlsabo.tools.lenientJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

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
