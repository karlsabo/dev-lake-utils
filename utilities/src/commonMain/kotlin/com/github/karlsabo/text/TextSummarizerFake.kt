package com.github.karlsabo.text

private const val SUMMARY_PREVIEW_LENGTH = 16

private val lineBreakPattern = Regex("""\r?\n|\f\n|\r""")

class TextSummarizerFake : TextSummarizer {
    override suspend fun summarize(text: String): String {
        val preview = text.replace(lineBreakPattern, "").take(SUMMARY_PREVIEW_LENGTH)
        return "* Fake summary of: `$preview`..."
    }
}
