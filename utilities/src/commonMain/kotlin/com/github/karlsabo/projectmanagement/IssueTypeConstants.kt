package com.github.karlsabo.projectmanagement

/**
 * Constants for issue type classification across project management systems.
 */
object IssueTypeConstants {
    /** Issue types that represent milestones (epics, initiatives) */
    val MILESTONE_TYPES = setOf("epic", "milestone")

    /** Issue types that represent regular work items */
    val WORK_ITEM_TYPES = setOf(
        "bug", "issue", "story", "subtask", "artifact", "task", "vulnerability",
        "request", "design story", "ds story", "change request"
    )

    /** Issue types that represent container/parent items (not countable work) */
    val CONTAINER_TYPES = setOf(
        "epic", "theme", "parent artifact", "r&d initiative", "sub-task",
        "company initiative", "milestone"
    )
}
