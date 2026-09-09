package com.github.karlsabo.devlake.triage

import com.github.karlsabo.devlake.triage.assessment.ASSESSMENT_PROMPT_VERSION
import com.github.karlsabo.devlake.triage.assessment.IssueAssessment
import com.github.karlsabo.devlake.triage.assessment.PiIssueAssessor
import com.github.karlsabo.devlake.triage.assessment.PiProcessRunner
import com.github.karlsabo.devlake.triage.assessment.ProcessResult
import com.github.karlsabo.devlake.triage.spreadsheet.OdsTriageWorkbook
import com.github.karlsabo.devlake.triage.spreadsheet.TriageWorkbook
import com.github.karlsabo.linear.LinearTriageIssue
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.odftoolkit.odfdom.doc.OdfSpreadsheetDocument
import java.io.IOException
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

class PartialAssessmentFailureTest {
    @Test
    fun `checkpoints successful and error rows before returning a nonzero result`() = runTest {
        val directory = Files.createTempDirectory("partial-assessment-failure")
        val output = directory.resolve("inventory.ods")
        val concurrency = 2
        val process = BoundedFakePiProcess(concurrency)
        val workbook = RecordingWorkbook()
        val reports = mutableListOf<String>()
        val command = IssueTriageCommand(
            source = { _, _, _ -> sixIssues() },
            commentSource = { _, _ -> emptyList() },
            assessor = PiIssueAssessor(processRunner = process),
            workbook = workbook,
            execution = AssessmentExecution(concurrency = concurrency, failureReporter = reports::add),
        )

        val result = command.run(arguments(directory, output))

        assertEquals(1, result.exitCode)
        assertEquals(listOf("TST-2"), result.failures.map(AssessmentFailure::identifier))
        assertEquals(2, process.attempts.getValue("TST-2"))
        assertEquals(concurrency, process.maximumActive.get())
        assertEquals(7, workbook.writeCount.get())
        assertEquals(listOf("1 assessment(s) failed after retry: TST-2"), reports)
        assertWorkbookRows(output)
    }

    @Test
    fun `comment fetch failure saves an error row alongside successful assessments`() = runTest {
        val directory = Files.createTempDirectory("partial-comment-failure")
        val output = directory.resolve("inventory.ods")
        val process = BoundedFakePiProcess(concurrency = 2)
        val workbook = RecordingWorkbook()
        val reports = mutableListOf<String>()
        val command = IssueTriageCommand(
            source = { _, _, _ -> sixIssues().filterNot { it.identifier == "TST-2" } },
            commentSource = { identifier, _ ->
                check(identifier != "TST-3") { "comments unavailable" }
                emptyList()
            },
            assessor = PiIssueAssessor(processRunner = process),
            workbook = workbook,
            execution = AssessmentExecution(concurrency = 2, failureReporter = reports::add),
        )

        val result = command.run(arguments(directory, output))

        assertEquals(1, result.exitCode)
        assertEquals(listOf(AssessmentFailure("TST-3", "comments unavailable")), result.failures)
        assertTrue(!process.attempts.containsKey("TST-3"))
        assertEquals(6, workbook.writeCount.get())
        assertEquals(listOf("1 assessment(s) failed after retry: TST-3"), reports)
        assertWorkbookRows(output, expectedTickets = setOf(1, 3, 4, 5, 6), errorTickets = setOf(3))
    }

    @Test
    fun `unexpected assessor exception saves an error row alongside successful assessments`() = runTest {
        val directory = Files.createTempDirectory("unexpected-assessor-failure")
        val output = directory.resolve("inventory.ods")
        val command = IssueTriageCommand(
            source = { _, _, _ -> sixIssues().take(2) },
            commentSource = { _, _ -> emptyList() },
            assessor = { row, _, _ ->
                if (row.identifier == "TST-2") throw IOException("repository unavailable")
                IssueAssessment.parse(assessmentJson())
            },
            workbook = OdsTriageWorkbook(),
        )

        val result = command.run(arguments(directory, output))

        assertEquals(listOf(AssessmentFailure("TST-2", "repository unavailable")), result.failures)
        assertWorkbookRows(output, expectedTickets = setOf(1, 2), errorTickets = setOf(2))
    }

    @Test
    fun `restart assesses only unfinished rows from the last durable checkpoint`() = runBlocking {
        val directory = Files.createTempDirectory("resume-assessments")
        val output = directory.resolve("inventory.ods")
        val allWorkersStarted = CountDownLatch(3)
        val releaseThird = CountDownLatch(1)
        val twoRowsPersisted = CountDownLatch(1)
        val firstRunAssessments = ConcurrentHashMap.newKeySet<String>()
        val checkpointWorkbook = CheckpointWorkbook(twoRowsPersisted)
        val firstCommand = IssueTriageCommand(
            source = { _, _, _ -> sixIssues().take(3) },
            commentSource = { _, _ -> emptyList() },
            assessor = { row, _, _ ->
                firstRunAssessments += row.identifier
                allWorkersStarted.countDown()
                assertTrue(allWorkersStarted.await(5, TimeUnit.SECONDS))
                if (row.identifier == "TST-3") releaseThird.await()
                IssueAssessment.parse(assessmentJson())
            },
            workbook = checkpointWorkbook,
            execution = AssessmentExecution(concurrency = 3),
        )

        val interruptedRun = launch { firstCommand.run(arguments(directory, output)) }
        withTimeout(10_000.milliseconds) {
            while (twoRowsPersisted.count > 0) delay(10.milliseconds)
        }
        interruptedRun.cancelAndJoin()

        assertEquals(setOf("TST-1", "TST-2", "TST-3"), firstRunAssessments)
        assertWorkbookRows(
            output,
            expectedTickets = setOf(1, 2, 3),
            errorTickets = emptySet(),
            pendingTickets = setOf(3),
        )

        val restartedAssessments = mutableListOf<String>()
        val restartedCommand = IssueTriageCommand(
            source = { _, _, _ -> sixIssues().take(3) },
            commentSource = { _, _ -> emptyList() },
            assessor = { row, _, _ ->
                restartedAssessments += row.identifier
                IssueAssessment.parse(assessmentJson())
            },
            workbook = OdsTriageWorkbook(),
        )

        val result = restartedCommand.run(arguments(directory, output))

        assertEquals(0, result.exitCode)
        assertEquals(listOf("TST-3"), restartedAssessments)
        assertWorkbookRows(output, expectedTickets = setOf(1, 2, 3), errorTickets = emptySet())
    }

    @Test
    fun `cancellation terminates the running pi process and leaves a pending checkpoint`() = runBlocking {
        val directory = Files.createTempDirectory("cancel-assessment")
        val output = directory.resolve("inventory.ods")
        val pidFile = directory.resolve("pid.txt")
        val executable = directory.resolve("fake-pi-cancel")
        Files.writeString(
            executable,
            $$"""#!/bin/sh
printf '%s' "$$" > '$$pidFile'
cat > /dev/null
sleep 30
""",
        )
        assertTrue(executable.toFile().setExecutable(true))
        val command = IssueTriageCommand(
            source = { _, _, _ -> sixIssues().take(1) },
            commentSource = { _, _ -> emptyList() },
            assessor = PiIssueAssessor(
                piExecutable = executable.toString(),
                timeout = Duration.ofSeconds(30),
            ),
            workbook = OdsTriageWorkbook(),
        )

        val job = launch { command.run(arguments(directory, output)) }
        withTimeout(5_000.milliseconds) {
            while (!Files.exists(pidFile)) delay(10.milliseconds)
        }
        job.cancelAndJoin()

        val processHandle = ProcessHandle.of(Files.readString(pidFile).toLong())
        assertTrue(processHandle.isEmpty || !processHandle.get().isAlive)
        assertTrue(Files.exists(output))
        OdfSpreadsheetDocument.loadDocument(output.toFile()).use { document ->
            val ranking = document.spreadsheetTables.single { it.tableName == "Ranking" }
            assertEquals(2, ranking.rowCount)
            assertEquals("", ranking.getCellByPosition(TriageColumn.ASSESSMENT_STATUS.ordinal, 1).stringValue)
        }
    }

    private fun assertWorkbookRows(
        output: java.nio.file.Path,
        expectedTickets: Set<Int> = (1..6).toSet(),
        errorTickets: Set<Int> = setOf(2),
        pendingTickets: Set<Int> = emptySet(),
    ) {
        OdfSpreadsheetDocument.loadDocument(output.toFile()).use { document ->
            val ranking = document.spreadsheetTables.single { it.tableName == "Ranking" }
            val rowsByTicket = (1 until ranking.rowCount).associateBy { rowIndex ->
                ranking.getCellByPosition(TriageColumn.TICKET_ID.ordinal, rowIndex).stringValue
            }
            errorTickets.forEach { number ->
                val errorRow = requireNotNull(rowsByTicket["TST-$number"])
                assertEquals("", ranking.getCellByPosition(TriageColumn.DIFFICULTY.ordinal, errorRow).stringValue)
                assertEquals("", ranking.getCellByPosition(TriageColumn.RATIONALE.ordinal, errorRow).stringValue)
                val status = ranking.getCellByPosition(TriageColumn.ASSESSMENT_STATUS.ordinal, errorRow).stringValue
                assertEquals("Error", status)
            }
            pendingTickets.forEach { number ->
                val row = requireNotNull(rowsByTicket["TST-$number"])
                val status = ranking.getCellByPosition(TriageColumn.ASSESSMENT_STATUS.ordinal, row).stringValue
                assertEquals("", status)
            }
            (expectedTickets - errorTickets - pendingTickets).forEach { number ->
                val row = requireNotNull(rowsByTicket["TST-$number"])
                val status = ranking.getCellByPosition(TriageColumn.ASSESSMENT_STATUS.ordinal, row).stringValue
                assertEquals("Assessed", status)
            }
        }
    }

    private class BoundedFakePiProcess(
        concurrency: Int,
    ) : PiProcessRunner {
        val activeCount = AtomicInteger()
        val maximumActive = AtomicInteger()
        val attempts = ConcurrentHashMap<String, Int>()
        private val firstWorkersStarted = CountDownLatch(concurrency)

        override fun run(
            command: List<String>,
            prompt: String,
            workingDirectory: java.nio.file.Path,
            timeout: Duration,
        ): ProcessResult {
            val identifier = requireNotNull(IDENTIFIER.find(prompt)).groupValues[1]
            attempts.merge(identifier, 1, Int::plus)
            val active = activeCount.incrementAndGet()
            maximumActive.accumulateAndGet(active, ::maxOf)
            firstWorkersStarted.countDown()
            assertTrue(firstWorkersStarted.await(5, TimeUnit.SECONDS))
            return try {
                if (identifier == "TST-2") {
                    ProcessResult(75, "", "transient fake failure")
                } else {
                    ProcessResult(0, assessmentJson(), "")
                }
            } finally {
                activeCount.decrementAndGet()
            }
        }
    }

    private class CheckpointWorkbook(
        private val twoRowsPersisted: CountDownLatch,
    ) : TriageWorkbook {
        private val delegate = OdsTriageWorkbook()

        override fun mergeExisting(
            refresh: TriageRefresh,
            output: java.nio.file.Path,
        ): TriageInventory = delegate.mergeExisting(refresh, output)

        override fun write(inventory: TriageInventory, output: java.nio.file.Path) {
            delegate.write(inventory, output)
            if (inventory.rows.count { it.assessment != null } == 2) twoRowsPersisted.countDown()
        }
    }

    private class RecordingWorkbook : TriageWorkbook {
        private val delegate = OdsTriageWorkbook()
        private val activeWrites = AtomicInteger()
        val writeCount = AtomicInteger()

        override fun mergeExisting(
            refresh: TriageRefresh,
            output: java.nio.file.Path,
        ): TriageInventory = delegate.mergeExisting(refresh, output)

        override fun write(inventory: TriageInventory, output: java.nio.file.Path) {
            assertEquals(1, activeWrites.incrementAndGet())
            try {
                writeCount.incrementAndGet()
                delegate.write(inventory, output)
            } finally {
                activeWrites.decrementAndGet()
            }
        }
    }

    private companion object {
        val IDENTIFIER = Regex("Identifier: (TST-\\d+)")

        fun arguments(directory: java.nio.file.Path, output: java.nio.file.Path) = TriageArguments(
            linearConfig = directory.resolve("unused.json"),
            output = output,
            team = "TST",
            project = "Test Project",
            label = null,
            repositoryRoots = listOf(directory),
        )

        fun sixIssues() = (1..6).map { number ->
            LinearTriageIssue(
                id = "id-$number",
                identifier = "TST-$number",
                title = "Title TST-$number",
                description = "Description TST-$number",
                url = "https://linear/TST-$number",
                stateName = "Backlog",
                projectName = "Test Project",
                labelNames = emptySet(),
                updatedAt = Instant.parse("2026-04-02T00:00:00Z"),
            )
        }

        fun assessmentJson() = """
            {
              "difficulty": 4,
              "prNeeded": "Yes",
              "prReason": "Exact reason",
              "rationale": "src/example.kt:12",
              "confidence": "High",
              "model": "$DEFAULT_ASSESSMENT_MODEL",
              "thinking": "$DEFAULT_THINKING_LEVEL",
              "sourceUpdatedAt": "2026-04-02T00:00:00Z",
              "promptVersion": "$ASSESSMENT_PROMPT_VERSION",
              "status": "Assessed"
            }
        """.trimIndent()
    }
}
