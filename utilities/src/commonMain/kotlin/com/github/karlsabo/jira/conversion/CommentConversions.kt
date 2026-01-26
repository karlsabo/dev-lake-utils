package com.github.karlsabo.jira.conversion

import com.github.karlsabo.jira.extensions.toPlainText
import com.github.karlsabo.jira.model.Comment
import com.github.karlsabo.projectmanagement.ProjectComment

/**
 * Converts a Jira Comment to a unified ProjectComment.
 */
fun Comment.toProjectComment(): ProjectComment {
    return ProjectComment(
        id = id,
        body = body.toPlainText(),
        authorId = author.accountId,
        authorName = author.displayName,
        createdAt = created,
        updatedAt = updated,
    )
}
