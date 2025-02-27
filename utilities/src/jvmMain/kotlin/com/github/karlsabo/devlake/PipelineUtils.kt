package com.github.karlsabo.devlake

import com.github.karlsabo.devlake.accessor.Pipeline
import com.github.karlsabo.devlake.accessor.Status
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

fun Pipeline.isFinishedToday(): Boolean {
    if (finishedAt == null) return false

    val todayDayOfYear = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).dayOfYear
    val pipelineDayOfYear = finishedAt.toLocalDateTime(TimeZone.currentSystemDefault()).dayOfYear

    return status == Status.TASK_COMPLETED && todayDayOfYear == pipelineDayOfYear
}
