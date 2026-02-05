package com.github.karlsabo.linear.conversion

import com.github.karlsabo.common.datetime.DateTimeFormatting
import com.github.karlsabo.linear.ProjectMilestone
import com.github.karlsabo.projectmanagement.ProjectMilestone as UnifiedProjectMilestone

/**
 * Converts a Linear ProjectMilestone to the unified ProjectMilestone.
 */
fun ProjectMilestone.toUnifiedProjectMilestone(): UnifiedProjectMilestone {
    return UnifiedProjectMilestone(
        id = id,
        name = name,
        description = description,
        targetDate = parseTargetDate(targetDate),
        progress = progress,
        status = status,
        projectId = null, // Linear milestones don't directly expose project ID in this model
    )
}

/**
 * Parses a Linear target date string to an Instant.
 */
internal fun parseTargetDate(targetDate: String?) = targetDate?.let {
    runCatching { DateTimeFormatting.parseFlexibleDateToInstant(it) }.getOrNull()
}
