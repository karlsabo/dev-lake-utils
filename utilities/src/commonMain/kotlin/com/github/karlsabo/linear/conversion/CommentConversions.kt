package com.github.karlsabo.linear.conversion

import com.github.karlsabo.linear.Comment
import com.github.karlsabo.projectmanagement.ProjectComment

/**
 * Converts a Linear Comment to a unified ProjectComment.
 */
fun Comment.toProjectComment(): ProjectComment {
    return ProjectComment(
        id = id,
        body = body,
        authorId = user?.id,
        authorName = user?.displayName ?: user?.name,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
