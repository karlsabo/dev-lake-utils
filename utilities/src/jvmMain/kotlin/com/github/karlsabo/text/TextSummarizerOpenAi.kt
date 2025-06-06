package com.github.karlsabo.text

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.serialization.gson.gson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.println
import kotlin.use

private val json = Json {
    ignoreUnknownKeys = true
}

class TextSummarizerOpenAi(private val config: TextSummarizerOpenAiConfig) : TextSummarizer {
    override suspend fun summarize(text: String): String {
        val model = "gpt-4o-mini"

        val instructions =
            """
        Summarize the provided work output (tickets, PRs, alerts) into a single-level markdown bullet list (max 5 bullets).
        Focus on outcomes, milestones, and impact. Use past tense for completed actions.
        Combine similar or less critical items to meet the 5-bullet limit, prioritizing relevance and impact.
        Keep summaries terse, avoiding technical jargon, names, and generic phrases (e.g., "improving efficiency," "streamlining").
        Do not use phrases describing the action type (e.g., "closed ticket," "merged PR").
        """.trimIndent()

        HttpClient {
            install(ContentNegotiation) {
                gson()
            }
        }.use { client ->
            val requestBody = mapOf(
                "model" to model,
                "messages" to listOf(
                    mapOf(
                        "role" to "system",
                        "content" to instructions,
                    ),
                    mapOf("role" to "user", "content" to text)
                ),
                "temperature" to 0,
                "max_tokens" to 250
            )

            val response: HttpResponse = client.post("https://api.openai.com/v1/chat/completions") {
                headers {
                    append(HttpHeaders.Authorization, "Bearer ${config.apiKey}")
                    append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                }
                setBody(requestBody)
                println(this)
            }

            println("response: $response")
            val responseText: String = response.body()
            println("responseText: $responseText")


            val openAiResponse: OpenAIResponse = json.decodeFromString(OpenAIResponse.serializer(), responseText)
            println("openAiResponse=$openAiResponse")
            val summary: String = if (openAiResponse.choices != null) {
                println("Choices:")
                openAiResponse.choices.forEach { choice ->
                    println("Choice: ${choice.message?.content}")
                }
                openAiResponse.choices.firstOrNull()?.message?.content ?: "* No choices available"
            } else {
                "* No summary available"
            }

            return summary
        }

    }

}


@Serializable
private data class Message(val role: String? = null, val content: String? = null)

@Serializable
private data class Choice(val message: Message? = null)

@Serializable
private data class Error(
    val message: String? = null,
    val type: String? = null,
    val param: String? = null,
    val code: String? = null,
)

@Serializable
private data class OpenAIResponse(val choices: List<Choice>? = null, val error: Error? = null)
