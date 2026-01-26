package com.github.karlsabo.common.adf

/**
 * Extracts plain text from a list of ADF ContentNodes.
 *
 * @param content The list of ContentNodes to extract text from.
 * @return A plain text string with normalized whitespace.
 */
fun extractTextFromAdf(content: List<ContentNode>?): String? {
    if (content == null) return null

    val parts = mutableListOf<String>()
    content.forEach { node ->
        parts.add(extractTextFromNode(node))
    }

    val rawText = parts.joinToString(separator = "")

    return rawText
        .replace(Regex("\n{3,}"), "\n\n")
        .replace(Regex(" +\n"), "\n")
        .trim()
        .ifEmpty { null }
}

/**
 * Recursively extracts text from a single ADF ContentNode.
 */
fun extractTextFromNode(node: ContentNode): String {
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
            } else if (sb.isEmpty() && node.content.isNullOrEmpty()) {
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
            } else if (sb.isEmpty() && node.content?.isNotEmpty() == true) {
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
            } else if (sb.isNotEmpty() && !sb.endsWith("\n")) {
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
