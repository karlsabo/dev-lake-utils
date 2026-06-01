package com.github.karlsabo.tools

import com.github.karlsabo.projectmanagement.ProjectIssue
import com.github.karlsabo.projectmanagement.isIssueOrBug
import kotlinx.datetime.Clock
import kotlin.time.Duration

internal fun List<ProjectIssue>.filterResolvedIssues(duration: Duration): List<ProjectIssue> = filter {
    it.completedAt != null && it.completedAt >= Clock.System.now().minus(duration) && it.isIssueOrBug()
}

internal fun List<ProjectIssue>.filterRecentIssues(duration: Duration): Set<ProjectIssue> = filter {
    it.isIssueOrBug() &&
        (
            (it.completedAt != null && it.completedAt >= Clock.System.now().minus(duration)) ||
                (it.createdAt != null && it.createdAt >= Clock.System.now().minus(duration))
            )
}.toSet()
