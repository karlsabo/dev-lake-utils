package com.github.karlsabo.jira.extensions

import com.github.karlsabo.common.adf.extractTextFromAdf
import com.github.karlsabo.jira.model.CommentBody

/**
 * Converts the Atlassian Document Format within a CommentBody into a plain text string.
 * It attempts to create a readable text blob by handling common node types like
 * paragraphs, text, mentions, emojis, lists, and headings.
 *
 * @return A String representing the plain text content of the comment body.
 */
fun CommentBody.toPlainText(): String {
    return extractTextFromAdf(this.content) ?: ""
}
