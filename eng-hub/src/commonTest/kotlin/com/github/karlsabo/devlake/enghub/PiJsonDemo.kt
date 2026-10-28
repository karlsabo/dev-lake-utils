package com.github.karlsabo.devlake.enghub

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class PiJsonDemoResult(
    val summary: String,
    val tags: List<String>,
)

internal object PiJsonDemo {
    // Pi print mode returns text, not schema-enforced JSON. Decode strictly before trusting it.
    private val json = Json { ignoreUnknownKeys = false }

    val outputSchema = """
        {
          "type": "object",
          "properties": {
            "summary": { "type": "string" },
            "tags": { "type": "array", "items": { "type": "string" } }
          },
          "required": ["summary", "tags"],
          "additionalProperties": false
        }
    """.trimIndent()

    suspend fun run(text: String, promptPi: suspend (String) -> String): PiJsonDemoResult {
        val prompt = """
            Summarize the text below and provide a few relevant tags.
            Return only a JSON object matching this schema, without a markdown fence:
            $outputSchema

            Text:
            $text
        """.trimIndent()
        return json.decodeFromString<PiJsonDemoResult>(promptPi(prompt))
    }
}
