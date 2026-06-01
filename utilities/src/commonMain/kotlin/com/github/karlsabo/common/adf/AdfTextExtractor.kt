package com.github.karlsabo.common.adf

private const val DOUBLE_NEWLINE = "\n\n"

/**
 * Extracts plain text from a list of ADF ContentNodes.
 *
 * @param content The list of ContentNodes to extract text from.
 * @return A plain text string with normalized whitespace.
 */
fun extractTextFromAdf(content: List<ContentNode>?): String? {
    if (content == null) return null

    val rawText = content.joinToString(separator = "") { node ->
        AdfNodeTextExtractor.extractTextFromNode(node)
    }

    return rawText
        .replace(Regex("\n{3,}"), DOUBLE_NEWLINE)
        .replace(Regex(" +\n"), "\n")
        .trim()
        .ifEmpty { null }
}

private object AdfNodeTextExtractor {
    /**
     * Recursively extracts text from a single ADF ContentNode.
     */
    fun extractTextFromNode(node: ContentNode): String = when (node.type) {
        "text" -> node.text.orEmpty()
        "mention" -> node.attrs?.text.orEmpty()
        "emoji" -> node.attrs?.text ?: node.attrs?.shortName.orEmpty()
        "hardBreak" -> "\n"
        "paragraph", "heading", "blockquote" -> node.extractChildren().withParagraphBreak(node.hasChildren())
        "rule" -> "---".withBlockSpacing()
        "codeBlock" -> node.extractCodeBlock().withParagraphBreak(node.hasChildren())
        "bulletList", "orderedList" -> node.extractChildren().withListBreak()
        "listItem" -> "- ${node.extractChildren()}".ensureTrailingNewline()
        else -> node.extractChildren().withUnknownNodeBreak(node.hasChildren())
    }

    private fun ContentNode.extractChildren(): String = content.orEmpty().joinToString(separator = "") { childNode ->
        extractTextFromNode(childNode)
    }

    private fun ContentNode.extractCodeBlock(): String = content.orEmpty().joinToString(separator = "") { lineNode ->
        if (lineNode.type == "text") {
            lineNode.text.orEmpty() + "\n"
        } else {
            extractTextFromNode(lineNode)
        }
    }

    private fun ContentNode.hasChildren(): Boolean = !content.isNullOrEmpty()

    private fun String.withBlockSpacing(): String = trimTrailingSingleNewline() + DOUBLE_NEWLINE

    private fun String.withParagraphBreak(hasChildren: Boolean): String = when {
        isNotEmpty() && !endsWith(DOUBLE_NEWLINE) -> trimTrailingSingleNewline() + DOUBLE_NEWLINE
        isEmpty() && hasChildren -> DOUBLE_NEWLINE
        isEmpty() -> DOUBLE_NEWLINE
        else -> this
    }

    private fun String.withListBreak(): String = when {
        endsWith("\n") && !endsWith(DOUBLE_NEWLINE) -> this + "\n"
        isNotEmpty() && !endsWith("\n") -> this + DOUBLE_NEWLINE
        else -> this
    }

    private fun String.withUnknownNodeBreak(hasChildren: Boolean): String = if (hasChildren && isNotEmpty()) {
        withParagraphBreak(hasChildren = true)
    } else {
        this
    }

    private fun String.ensureTrailingNewline(): String = if (endsWith("\n")) this else this + "\n"

    private fun String.trimTrailingSingleNewline(): String = if (endsWith("\n") && !endsWith(DOUBLE_NEWLINE)) {
        this + "\n"
    } else {
        this
    }
}
