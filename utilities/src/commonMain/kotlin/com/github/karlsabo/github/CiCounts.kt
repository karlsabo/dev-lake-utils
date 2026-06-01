package com.github.karlsabo.github

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class CiCounts(
    val total: Int,
    val passed: Int,
    val failed: Int,
    val inProgress: Int,
) {
    operator fun plus(other: CiCounts): CiCounts = CiCounts(
        total = total + other.total,
        passed = passed + other.passed,
        failed = failed + other.failed,
        inProgress = inProgress + other.inProgress,
    )

    fun toSummary(): CheckRunSummary {
        val ciStatus = when {
            failed > 0 -> CiStatus.FAILED
            inProgress > 0 -> CiStatus.RUNNING
            total == 0 -> CiStatus.PENDING
            else -> CiStatus.PASSED
        }

        return CheckRunSummary(
            total = total,
            passed = passed,
            failed = failed,
            inProgress = inProgress,
            status = ciStatus,
        )
    }
}

internal fun parseCheckRunCounts(checkRuns: List<JsonElement>): CiCounts {
    var passed = 0
    var failed = 0
    var inProgress = 0

    for (checkRun in checkRuns) {
        val obj = checkRun.jsonObject
        val status = obj["status"]?.jsonPrimitive?.content
        val conclusion = obj["conclusion"]?.jsonPrimitive?.content

        when {
            status == "completed" && conclusion == "success" -> passed++
            status == "completed" && conclusion == "neutral" -> passed++
            status == "completed" && conclusion == "skipped" -> passed++
            status == "completed" -> failed++
            status == "in_progress" || status == "queued" -> inProgress++
        }
    }

    return CiCounts(total = checkRuns.size, passed = passed, failed = failed, inProgress = inProgress)
}

internal fun parseCommitStatusCounts(statuses: List<JsonElement>): CiCounts {
    var passed = 0
    var failed = 0
    var inProgress = 0

    for (status in statuses) {
        when (status.jsonObject["state"]?.jsonPrimitive?.content) {
            "success" -> passed++
            "failure", "error" -> failed++
            "pending" -> inProgress++
        }
    }

    return CiCounts(total = statuses.size, passed = passed, failed = failed, inProgress = inProgress)
}
