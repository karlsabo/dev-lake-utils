package com.github.karlsabo.text

import com.github.karlsabo.http.installHttpRetry
import com.github.karlsabo.tools.lenientJson
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.cache.HttpCache
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private val logger = KotlinLogging.logger {}

class TextSummarizerOpenAi(private val config: TextSummarizerOpenAiConfig) : TextSummarizer {
    override suspend fun summarize(text: String): String {
        val model = "gpt-5-mini"

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
                json(lenientJson)
            }
            installHttpRetry()
            install(HttpCache)
            expectSuccess = false
        }.use { client ->
            val request = ChatCompletionRequest(
                model = model,
                messages = listOf(
                    Message("system", instructions),
                    Message("user", text)
                ),
                maxCompletionTokens = 2048,
                reasoningEffort = "minimal"
            )
            val response: HttpResponse = client.post("https://api.openai.com/v1/chat/completions") {
                headers {
                    append(HttpHeaders.Authorization, "Bearer ${config.apiKey}")
                    append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                }
                setBody(request)
                logger.debug { "OpenAI request: $this" }
            }

            logger.debug { "OpenAI response: $response" }
            val responseText: String = response.body()
            logger.debug { "OpenAI responseText: $responseText" }

            if (!response.status.isSuccess()) {
                throw Exception("Failed to summarize text: ${response.status.value}")
            }

            val openAiResponse: OpenAIResponse = lenientJson.decodeFromString(OpenAIResponse.serializer(), responseText)
            logger.debug { "openAiResponse=$openAiResponse" }
            val summary: String = if (openAiResponse.choices != null) {
                logger.debug { "OpenAI Choices: ${openAiResponse.choices.map { it.message?.content }}" }
                openAiResponse.choices.firstOrNull()?.message?.content ?: "* No choices available"
            } else {
                "* No summary available"
            }

            return summary
        }

    }

}

@Serializable
data class Message(
    val role: String,
    val content: String,
)

@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<Message>,
    val temperature: Double = 1.0,
    @SerialName("max_completion_tokens")
    val maxCompletionTokens: Int,
    val verbosity: String? = null, // New param supported by GPT-5 (values: "low", "medium", "high")
    @SerialName("reasoning_effort")
    val reasoningEffort: String? = null, // New param for GPT-5: e.g. "minimal"
)

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
