package com.github.karlsabo.text

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.gson.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.println
import kotlin.use

class TextSummarizerOpenAi(private val config: TextSummarizerOpenAiConfig) : TextSummarizer {
    override suspend fun summarize(inputText: String): String {
        val model = "gpt-4o-mini"

        val instructions =
            """
        You are a concise summarizer, categorize and summarize the work output. 
        Summarize the input data as a markdown list with bullet points. 
        Avoid overly technical details but maintain relevance. Focus on outcomes, milestones, and any significant impact. 
        Keep it concise with a single level of nested bullet points. Only use bullet points, no other markdown styling. 
        Don't use nested bullets. 
        Don't use peoples names. 
        Do not embellish or add additional information such as 'ensuring system reliability', 'streamlining management and development'.
        All these events have occurred, these are closed Jira tickets, merged GitHub Pull requests, and resolved pager duty alerts, ensure you use the correct tense. 
        Make the bullet summary terse, and short. Don't say something like 'merged a pull request'.
        Reduce the number of bullet points you create by combining similar items. 
        Do everything you can to reduce down to 5 bullets, keeping the most important, impactful, and relevant items.
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
                    mapOf("role" to "user", "content" to inputText)
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


            val openAiResponse: OpenAIResponse = Json {
                ignoreUnknownKeys = true
            }.decodeFromString(OpenAIResponse.serializer(), responseText)
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
