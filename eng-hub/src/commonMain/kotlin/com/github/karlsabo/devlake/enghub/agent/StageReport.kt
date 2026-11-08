package com.github.karlsabo.devlake.enghub.agent

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * What the implementor stage claims it changed and why.
 *
 * This is a report, not proof of work: ordinary code checks shape and nonblank contents, not whether
 * the claimed edits or tests actually happened.
 */
@Serializable
data class ImplementationReport(
    val files: List<String>,
    val what: String,
    val why: String,
)

/** The reviewer stage's verdict and concrete findings. */
@Serializable
data class ReviewReport(
    val verdict: ReviewVerdict,
    val findings: List<String>,
)

@Serializable
enum class ReviewVerdict {
    @SerialName("pass")
    PASS,

    @SerialName("needs_changes")
    NEEDS_CHANGES,
}

private val reportJson = Json

internal fun decodeImplementationReport(response: String): ImplementationReport {
    val report = decodeReport("Implementor", response, ImplementationReport.serializer())
    require(report.files.none { file -> file.isBlank() }) {
        "Implementor report file entries must not be blank."
    }
    require(report.what.isNotBlank()) { "Implementor report 'what' must not be blank." }
    require(report.why.isNotBlank()) { "Implementor report 'why' must not be blank." }
    return report
}

internal fun decodeReviewReport(response: String): ReviewReport {
    val report = decodeReport("Reviewer", response, ReviewReport.serializer())
    require(report.findings.none { finding -> finding.isBlank() }) {
        "Reviewer report findings must not be blank."
    }
    return report
}

private fun <T> decodeReport(
    stage: String,
    response: String,
    deserializer: DeserializationStrategy<T>,
): T = try {
    reportJson.decodeFromString(deserializer, response)
} catch (failure: SerializationException) {
    throw IllegalArgumentException("$stage response is not a valid report: ${failure.message}", failure)
}
