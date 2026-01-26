package com.github.karlsabo.jira.extensions

import com.github.karlsabo.common.adf.extractTextFromAdf
import com.github.karlsabo.jira.model.IssueDescription

/**
 * Converts the Atlassian Document Format within an IssueDescription into a plain text string.
 * It attempts to create a readable text blob by handling common node types like
 * paragraphs, text, mentions, emojis, lists, and headings.
 *
 * @return A String representing the plain text content of the description, or null if the description is null.
 */
fun IssueDescription?.toPlainText(): String? {
    if (this == null) return null
    return extractTextFromAdf(this.content)
}
