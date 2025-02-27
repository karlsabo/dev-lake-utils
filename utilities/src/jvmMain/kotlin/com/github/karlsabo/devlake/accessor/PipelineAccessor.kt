package com.github.karlsabo.devlake.accessor

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

interface PipelineAccessor {
    fun getPipelines(limit: Int, offset: Int): List<Pipeline>
}

@Serializable
enum class Status {
    UNKNOWN,
    TASK_COMPLETED,
    TASK_FAILED,
    TASK_PARTIAL,
    TASK_RUNNING,
}

@Serializable
data class Pipeline(
    val id: Long,
    val createdAt: Instant?,
    val updatedAt: Instant?,
    val name: String?,
    val blueprintId: Long?,
    val totalTasks: Long?,
    val finishedTasks: Long?,
    val beganAt: Instant?,
    val finishedAt: Instant?,
    val status: Status,
    val message: String?,
    val spentSeconds: Long?,
    val stage: Long?,
    val plan: String?,
    val skipOnFail: Boolean?,
    val errorName: String?,
    val fullSync: Boolean?,
    val skipCollectors: Boolean?,
    val timeAfter: Instant?
)
