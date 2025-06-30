package com.github.karlsabo.text

interface TextSummarizer {
    suspend fun summarize(text: String): String
}
