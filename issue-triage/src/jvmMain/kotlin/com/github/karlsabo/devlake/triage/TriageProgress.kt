package com.github.karlsabo.devlake.triage

internal sealed interface TriageProgressEvent {
    data object ScopeFetchStarted : TriageProgressEvent

    data class ScopeFetched(
        val issueCount: Int,
    ) : TriageProgressEvent

    data object InventoryMergeStarted : TriageProgressEvent

    data class InventoryMerged(
        val activeCount: Int,
        val archivedCount: Int,
        val assessedCount: Int,
    ) : TriageProgressEvent

    data class AssessmentQueueCreated(
        val total: Int,
        val concurrency: Int,
    ) : TriageProgressEvent

    data class AssessmentStarted(
        val identifier: String,
        val attempt: Int,
    ) : TriageProgressEvent

    data class AssessmentRetrying(
        val identifier: String,
        val attempt: Int,
    ) : TriageProgressEvent

    data class AssessmentFinished(
        val identifier: String,
        val attempt: Int,
        val succeeded: Boolean,
        val elapsedMillis: Long,
        val completed: Int,
        val total: Int,
    ) : TriageProgressEvent

    data class DurableSaveStarted(
        val completed: Int,
        val total: Int,
    ) : TriageProgressEvent

    data object DurableSaveFinished : TriageProgressEvent

    data class RunFinished(
        val succeeded: Int,
        val failed: Int,
        val elapsedMillis: Long,
    ) : TriageProgressEvent
}

internal fun interface TriageProgressReporter {
    fun report(event: TriageProgressEvent)
}

internal object NoOpTriageProgressReporter : TriageProgressReporter {
    override fun report(event: TriageProgressEvent) = Unit
}

internal class ConsoleTriageProgressReporter(
    private val output: (String) -> Unit = System.out::println,
) : TriageProgressReporter {
    override fun report(event: TriageProgressEvent) {
        output("[INFO] ${event.message()}")
    }
}

private fun TriageProgressEvent.message(): String = when (this) {
    TriageProgressEvent.ScopeFetchStarted -> "Fetching Linear issue scope"

    is TriageProgressEvent.ScopeFetched -> "Fetched $issueCount issue(s)"

    TriageProgressEvent.InventoryMergeStarted -> "Merging fetched scope with existing workbook"

    is TriageProgressEvent.InventoryMerged ->
        "Inventory ready: $activeCount active, $archivedCount archived, $assessedCount already assessed"

    is TriageProgressEvent.AssessmentQueueCreated ->
        "Assessment queue: $total ticket(s), concurrency $concurrency"

    is TriageProgressEvent.AssessmentStarted -> "$identifier assessment started (attempt $attempt)"

    is TriageProgressEvent.AssessmentRetrying -> "$identifier assessment retrying (attempt $attempt)"

    is TriageProgressEvent.AssessmentFinished -> {
        val outcome = if (succeeded) "assessed" else "failed"
        "$identifier $outcome in ${elapsedMillis}ms (attempt $attempt, $completed/$total complete)"
    }

    is TriageProgressEvent.DurableSaveStarted -> "Saving durable workbook ($completed/$total complete)"

    TriageProgressEvent.DurableSaveFinished -> "Durable workbook save complete"

    is TriageProgressEvent.RunFinished ->
        "Run finished: $succeeded succeeded, $failed failed in ${elapsedMillis}ms"
}
