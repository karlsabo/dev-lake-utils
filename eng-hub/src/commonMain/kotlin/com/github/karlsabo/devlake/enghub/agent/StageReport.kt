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

/**
 * A stage reply that is not a valid report.
 *
 * This is the only failure the flow repairs. Transport, executor, tool, and other failures do not use this
 * type, so they keep propagating unchanged instead of receiving a repair turn.
 *
 * @param message the validation failure, which the repair prompt carries as the rejection reason.
 * @param cause the decode failure, when the reply could not be deserialized.
 */
class InvalidStageReportException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

private val reportJson = Json

internal fun decodeImplementationReport(response: String): ImplementationReport {
    val report = decodeReport("Implementor", response, ImplementationReport.serializer())
    if (report.files.any { file -> file.isBlank() }) {
        rejectReport("Implementor report file entries must not be blank.")
    }
    if (report.what.isBlank()) rejectReport("Implementor report 'what' must not be blank.")
    if (report.why.isBlank()) rejectReport("Implementor report 'why' must not be blank.")
    return report
}

internal fun decodeReviewReport(response: String): ReviewReport {
    val report = decodeReport("Reviewer", response, ReviewReport.serializer())
    if (report.findings.any { finding -> finding.isBlank() }) {
        rejectReport("Reviewer report findings must not be blank.")
    }
    when (report.verdict) {
        ReviewVerdict.PASS -> if (report.findings.isNotEmpty()) {
            rejectReport("Reviewer report 'pass' must not include findings.")
        }

        ReviewVerdict.NEEDS_CHANGES -> if (report.findings.isEmpty()) {
            rejectReport("Reviewer report 'needs_changes' must include at least one finding.")
        }
    }
    return report
}

private fun rejectReport(message: String): Nothing = throw InvalidStageReportException(message)

private fun <T> decodeReport(
    stage: String,
    response: String,
    deserializer: DeserializationStrategy<T>,
): T = try {
    reportJson.decodeFromString(deserializer, response)
} catch (failure: SerializationException) {
    throw InvalidStageReportException("$stage response is not a valid report: ${failure.message}", failure)
}
