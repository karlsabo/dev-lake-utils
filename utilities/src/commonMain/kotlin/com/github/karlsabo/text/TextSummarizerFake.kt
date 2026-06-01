package com.github.karlsabo.text

class TextSummarizerFake : TextSummarizer {
    override suspend fun summarize(text: String): String = "* Fake summary of: `${text.replace(Regex("""\r?\n|\f\n|\r"""), "").substring(0, 16)}`..."
}
