package com.github.karlsabo.devlake.triage

import com.github.karlsabo.devlake.triage.assessment.AssessmentConfiguration
import com.github.karlsabo.devlake.triage.assessment.IssueAssessor
import com.github.karlsabo.devlake.triage.assessment.MAX_ASSESSMENT_COMMENTS
import com.github.karlsabo.devlake.triage.assessment.PiIssueAssessor
import com.github.karlsabo.devlake.triage.spreadsheet.OdsTriageWorkbook
import com.github.karlsabo.devlake.triage.spreadsheet.TriageWorkbook
import com.github.karlsabo.linear.LinearTriageIssue
import com.github.karlsabo.linear.LinearTriageReader
import com.github.karlsabo.linear.config.loadLinearConfig
import com.github.karlsabo.projectmanagement.ProjectComment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.Clock
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess
import kotlinx.io.files.Path as KotlinPath

internal fun interface LinearInventorySource {
    suspend fun getIssues(
        team: String,
        project: String?,
        label: String?,
    ): List<LinearTriageIssue>
}

internal fun interface LinearCommentSource {
    suspend fun getRecentComments(issueKey: String, maxResults: Int): List<ProjectComment>
}

internal data class AssessmentFailure(
    val identifier: String,
    val message: String,
)

internal data class TriageRunResult(
    val failures: List<AssessmentFailure>,
) {
    val exitCode: Int = if (failures.isEmpty()) 0 else 1
}

internal data class AssessmentExecution(
    val concurrency: Int = DEFAULT_ASSESSMENT_CONCURRENCY,
    val failureReporter: (String) -> Unit = System.err::println,
    val progressReporter: TriageProgressReporter = NoOpTriageProgressReporter,
    val nanoTime: () -> Long = System::nanoTime,
) {
    init {
        require(concurrency > 0) { "Assessment concurrency must be positive" }
    }
}

internal class IssueTriageCommand(
    private val source: LinearInventorySource,
    private val commentSource: LinearCommentSource,
    private val assessor: IssueAssessor,
    private val workbook: TriageWorkbook,
    private val clock: Clock = Clock.systemUTC(),
    private val execution: AssessmentExecution = AssessmentExecution(),
) {

    suspend fun run(arguments: TriageArguments): TriageRunResult = executeRun(arguments, execution.nanoTime())

    private suspend fun executeRun(arguments: TriageArguments, startedAt: Long): TriageRunResult {
        execution.progressReporter.report(TriageProgressEvent.ScopeFetchStarted)
        val issues = source.getIssues(arguments.team, arguments.project, arguments.label)
        execution.progressReporter.report(TriageProgressEvent.ScopeFetched(issues.size))
        val refresh = buildRefresh(issues, arguments, clock.instant().toString())
        validateRetriageIdentifiers(arguments.retriage, refresh.inventory.rows)

        execution.progressReporter.report(TriageProgressEvent.InventoryMergeStarted)
        val inventory = workbook.mergeExisting(refresh, arguments.output)
        val preparedRows = inventory.rows.map { row -> prepareExistingAssessment(row, inventory, arguments) }
        execution.progressReporter.report(
            TriageProgressEvent.InventoryMerged(
                activeCount = preparedRows.size,
                archivedCount = inventory.archivedRows.size,
                assessedCount = preparedRows.count { it.assessment != null },
            ),
        )
        val rowsToAssess = preparedRows.filter { row ->
            row.assessment == null || row.assessment.isError() || arguments.retriage.requestsRetriage(row.identifier)
        }
        execution.progressReporter.report(
            TriageProgressEvent.AssessmentQueueCreated(rowsToAssess.size, execution.concurrency),
        )
        val configuration = AssessmentConfiguration(
            arguments.model,
            arguments.thinking,
            arguments.repositoryRoots,
            execution.progressReporter,
        )
        val outcomes = assessRows(rowsToAssess, configuration)
        val outcomesById = outcomes.associateBy { it.row.linearId }
        val finalRows = preparedRows.map { row -> outcomesById[row.linearId]?.row ?: row }
        val failures = outcomes.mapNotNull(AssessmentOutcome::failure)

        execution.progressReporter.report(TriageProgressEvent.DurableSaveStarted(outcomes.size, rowsToAssess.size))
        workbook.write(inventory.copy(rows = finalRows), arguments.output)
        execution.progressReporter.report(TriageProgressEvent.DurableSaveFinished)
        if (failures.isNotEmpty()) execution.failureReporter(failureSummary(failures))
        execution.progressReporter.report(
            TriageProgressEvent.RunFinished(
                succeeded = outcomes.size - failures.size,
                failed = failures.size,
                elapsedMillis = elapsedMillisSince(startedAt),
            ),
        )
        return TriageRunResult(failures)
    }

    private fun prepareExistingAssessment(
        row: TriageRow,
        inventory: TriageInventory,
        arguments: TriageArguments,
    ): TriageRow {
        val existing = row.assessment
        return when {
            existing == null -> row

            existing.isError() || arguments.retriage.requestsRetriage(row.identifier) -> row

            else -> row.copy(
                assessment = existing.markStaleIfChanged(row.updatedAt, inventory.assessmentPromptVersion),
            )
        }
    }

    private suspend fun assessRows(
        rows: List<TriageRow>,
        configuration: AssessmentConfiguration,
    ): List<AssessmentOutcome> = coroutineScope {
        val permits = Semaphore(execution.concurrency)
        val completed = AtomicInteger()
        rows.map { row ->
            async(Dispatchers.IO) {
                permits.withPermit { assessRowWithProgress(row, configuration, completed, rows.size) }
            }
        }.awaitAll()
    }

    private suspend fun assessRowWithProgress(
        row: TriageRow,
        configuration: AssessmentConfiguration,
        completed: AtomicInteger,
        total: Int,
    ): AssessmentOutcome {
        execution.progressReporter.report(TriageProgressEvent.AssessmentStarted(row.identifier, attempt = 1))
        val attempt = AtomicInteger(1)
        val rowConfiguration = configuration.copy(
            progressReporter = TriageProgressReporter { event ->
                if (event is TriageProgressEvent.AssessmentRetrying) attempt.set(event.attempt)
                execution.progressReporter.report(event)
            },
        )
        val startedAt = execution.nanoTime()
        val outcome = assessRow(row, rowConfiguration)
        execution.progressReporter.report(
            TriageProgressEvent.AssessmentFinished(
                identifier = row.identifier,
                attempt = attempt.get(),
                succeeded = outcome.failure == null,
                elapsedMillis = elapsedMillisSince(startedAt),
                completed = completed.incrementAndGet(),
                total = total,
            ),
        )
        return outcome
    }

    private suspend fun assessRow(
        row: TriageRow,
        configuration: AssessmentConfiguration,
    ): AssessmentOutcome {
        val comments = fetchComments(row).getOrElse { failure ->
            return recoverableFailure(row, failure)
        }
        return runCatching {
            runInterruptible { assessor.assess(row, comments, configuration) }
        }.fold(
            onSuccess = { assessment -> AssessmentOutcome(row.copy(assessment = assessment.toCells())) },
            onFailure = { failure -> recoverableFailure(row, failure) },
        )
    }

    private suspend fun fetchComments(row: TriageRow): Result<List<ProjectComment>> = runCatching {
        commentSource.getRecentComments(row.identifier, MAX_ASSESSMENT_COMMENTS)
    }

    private fun recoverableFailure(row: TriageRow, failure: Throwable): AssessmentOutcome = when (failure) {
        is CancellationException -> throw failure
        is Error -> throw failure
        else -> failedOutcome(row, failure)
    }

    private fun failedOutcome(row: TriageRow, failure: Throwable) = AssessmentOutcome(
        row = row.copy(
            assessment = row.assessment?.takeUnless(TriageAssessmentCells::isError) ?: TriageAssessmentCells.error(),
        ),
        failure = AssessmentFailure(row.identifier, failure.message.orEmpty()),
    )

    private fun elapsedMillisSince(startedAt: Long): Long {
        val elapsedNanos = (execution.nanoTime() - startedAt).coerceAtLeast(0)
        return elapsedNanos / NANOS_PER_MILLISECOND
    }
}

private data class AssessmentOutcome(
    val row: TriageRow,
    val failure: AssessmentFailure? = null,
)

private fun failureSummary(failures: List<AssessmentFailure>): String {
    val identifiers = failures.joinToString { it.identifier }
    return "${failures.size} assessment(s) failed after retry: $identifiers"
}

private fun validateRetriageIdentifiers(requested: Set<String>, rows: List<TriageRow>) {
    if (requested.isEmpty() || requested.requestsAllRetriage()) return
    val activeIdentifiers = rows.mapTo(mutableSetOf(), TriageRow::identifier)
    val unknownIdentifiers = requested - activeIdentifiers
    require(unknownIdentifiers.isEmpty()) {
        "Requested --retriage tickets are not in the active inventory: ${unknownIdentifiers.sorted().joinToString()}"
    }
}

fun main(rawArguments: Array<String>) {
    val arguments = TriageArguments.parse(rawArguments)
    val reader = LinearTriageReader(loadLinearConfig(KotlinPath(arguments.linearConfig.toString())))
    val progressReporter = ConsoleTriageProgressReporter()
    val command = IssueTriageCommand(
        source = LinearInventorySource(reader::getIssues),
        commentSource = LinearCommentSource(reader::getRecentComments),
        assessor = PiIssueAssessor(),
        workbook = OdsTriageWorkbook(),
        execution = AssessmentExecution(
            concurrency = arguments.assessmentConcurrency,
            progressReporter = progressReporter,
        ),
    )
    val result = runBlocking { command.run(arguments) }
    println("Exported Linear inventory to ${arguments.output}")
    if (result.exitCode != 0) exitProcess(result.exitCode)
}

private const val NANOS_PER_MILLISECOND = 1_000_000L
