package com.github.karlsabo.linear

internal const val LINEAR_DEFAULT_PAGE_SIZE = 100
internal const val LINEAR_DEFAULT_BATCH_SIZE = 100

internal val LINEAR_ISSUE_FIELDS = """
            id
            identifier
            title
            description
            url
            createdAt
            updatedAt
            completedAt
            archivedAt
            canceledAt
            dueDate
            priority
            estimate
            assignee {
              id
              name
              displayName
              email
            }
            creator {
              id
              name
              displayName
              email
            }
            state {
              id
              name
              type
            }
            parent {
              id
              identifier
              title
            }
            project {
              id
              name
              completedAt
              canceledAt
            }
            projectMilestone {
              id
              name
            }
""".trimIndent()

internal val LINEAR_ISSUE_ID_FIELDS = """
            id
""".trimIndent()

internal val LINEAR_COMMENT_FIELDS = """
            id
            body
            createdAt
            updatedAt
            url
            archivedAt
            user {
              id
              name
              displayName
              email
            }
""".trimIndent()

internal val LINEAR_MILESTONE_FIELDS = """
            id
            name
            description
            targetDate
            progress
""".trimIndent()
