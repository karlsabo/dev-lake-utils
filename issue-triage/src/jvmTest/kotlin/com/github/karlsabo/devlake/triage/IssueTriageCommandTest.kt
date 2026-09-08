package com.github.karlsabo.devlake.triage

import com.github.karlsabo.devlake.triage.assessment.ASSESSMENT_PROMPT_VERSION
import com.github.karlsabo.devlake.triage.assessment.AssessmentConfidence
import com.github.karlsabo.devlake.triage.assessment.AssessmentStatus
import com.github.karlsabo.devlake.triage.assessment.IssueAssessment
import com.github.karlsabo.devlake.triage.assessment.IssueAssessor
import com.github.karlsabo.devlake.triage.assessment.PrNeeded
import com.github.karlsabo.devlake.triage.spreadsheet.OdsTriageWorkbook
import com.github.karlsabo.linear.LinearTriageIssue
import com.github.karlsabo.projectmanagement.ProjectComment
import kotlinx.coroutines.test.runTest
import org.odftoolkit.odfdom.doc.OdfSpreadsheetDocument
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Collections
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant as KotlinInstant

class IssueTriageCommandTest {
    @Test
    fun `assesses each TST inventory row and exports validated results`() = runTest {
        val directory = Files.createTempDirectory("issue-triage-test")
        val output = directory.resolve("inventory.ods")
        val arguments = TriageArguments(
            linearConfig = directory.resolve("unused.json"),
            output = output,
            team = "TST",
            project = "Test Project",
            label = "test-label",
            repositoryRoots = listOf(directory),
        )
        val source = LinearInventorySource { _, _, _ -> issues }
        val requestedCommentCounts = Collections.synchronizedList(mutableListOf<Int>())
        val commentSource = LinearCommentSource { _, maxResults ->
            requestedCommentCounts += maxResults
            List(maxResults) { ProjectComment(id = "comment-$it", body = "Context $it") }
        }
        val assessedIdentifiers = Collections.synchronizedList(mutableListOf<String>())
        val assessor = IssueAssessor { row, comments, configuration ->
            assessedIdentifiers += row.identifier
            assertEquals(10, comments.size)
            assertEquals(DEFAULT_ASSESSMENT_MODEL, configuration.model)
            assessment(row.updatedAt)
        }
        val command = IssueTriageCommand(
            source = source,
            commentSource = commentSource,
            assessor = assessor,
            workbook = OdsTriageWorkbook(),
            clock = Clock.fixed(Instant.parse("2026-04-02T12:00:00Z"), ZoneOffset.UTC),
        )

        command.run(arguments)

        assertEquals(listOf("TST-1", "TST-2", "TST-3"), assessedIdentifiers.sorted())
        assertEquals(listOf(10, 10, 10), requestedCommentCounts.sorted())
        ZipFile(output.toFile()).use { archive ->
            assertNotNull(archive.getEntry("content.xml"))
            assertNotNull(archive.getEntry("META-INF/manifest.xml"))
        }
        OdfSpreadsheetDocument.loadDocument(output.toFile()).use { document ->
            val summary = document.spreadsheetTables.single { it.tableName == "Summary" }
            val ranking = document.spreadsheetTables.single { it.tableName == "Ranking" }
            assertEquals("2026-04-02T12:00:00Z", summary.getCellByPosition(1, 1).stringValue)
            assertEquals(DEFAULT_ASSESSMENT_MODEL, summary.getCellByPosition(1, 5).stringValue)
            assertEquals("3", summary.getCellByPosition(1, 8).stringValue)
            assertEquals(4, ranking.rowCount)
            assertEquals("Test Project project", ranking.getCellByPosition(5, 1).stringValue)
            assertEquals("project + label", ranking.getCellByPosition(5, 2).stringValue)
            assertEquals("test-label label", ranking.getCellByPosition(5, 3).stringValue)
            assertEquals("4", ranking.getCellByPosition(7, 1).stringValue)
            assertEquals("Yes", ranking.getCellByPosition(8, 1).stringValue)
            assertEquals("Exact reason", ranking.getCellByPosition(9, 1).stringValue)
            assertEquals("src/example.kt:12", ranking.getCellByPosition(10, 1).stringValue)
            assertEquals("High", ranking.getCellByPosition(11, 1).stringValue)
            assertEquals(DEFAULT_ASSESSMENT_MODEL, ranking.getCellByPosition(12, 1).stringValue)
            assertEquals("Assessed", ranking.getCellByPosition(16, 1).stringValue)
        }
    }

    @Test
    fun `refresh preserves edited assessment by Linear ID and assesses only new rows`() = runTest {
        val directory = Files.createTempDirectory("issue-triage-refresh-test")
        val output = directory.resolve("inventory.ods")
        val arguments = TriageArguments(
            linearConfig = directory.resolve("unused.json"),
            output = output,
            team = "TST",
            project = "Test Project",
            label = "test-label",
            repositoryRoots = listOf(directory),
        )
        var currentIssues = issues
        val assessedIdentifiers = mutableListOf<String>()
        val command = IssueTriageCommand(
            source = LinearInventorySource { _, _, _ -> currentIssues },
            commentSource = LinearCommentSource { _, _ -> emptyList() },
            assessor = IssueAssessor { row, _, _ ->
                assessedIdentifiers += row.identifier
                assessment(row.updatedAt)
            },
            workbook = OdsTriageWorkbook(),
            clock = Clock.fixed(Instant.parse("2026-04-02T12:00:00Z"), ZoneOffset.UTC),
        )
        command.run(arguments)
        editAssessment(output, "id-2")

        assessedIdentifiers.clear()
        currentIssues = issues.map { issue ->
            if (issue.id == "id-2") issue.copy(identifier = "TST-200", title = "Refreshed Linear title") else issue
        } + issue("id-4", "TST-4", "Test Project", emptySet())
        command.run(arguments)

        assertEquals(listOf("TST-4"), assessedIdentifiers)
        OdfSpreadsheetDocument.loadDocument(output.toFile()).use { document ->
            val ranking = document.spreadsheetTables.single { it.tableName == "Ranking" }
            val refreshedRow = (1 until ranking.rowCount).single { rowIndex ->
                ranking.getCellByPosition(0, rowIndex).stringValue == "id-2"
            }
            assertEquals("TST-200", ranking.getCellByPosition(1, refreshedRow).stringValue)
            assertEquals("Refreshed Linear title", ranking.getCellByPosition(2, refreshedRow).stringValue)
            assertEquals("human estimate", ranking.getCellByPosition(7, refreshedRow).stringValue)
            assertEquals("Human-edited evidence", ranking.getCellByPosition(10, refreshedRow).stringValue)
            assertEquals("manual", ranking.getCellByPosition(12, refreshedRow).stringValue)
            assertEquals(
                "collapse",
                ranking.getColumnByIndex(0).odfElement.getTableVisibilityAttribute(),
            )
        }
        val temporaryFileRemains = Files.list(directory).use { paths ->
            paths.anyMatch { it.fileName.toString().startsWith(".inventory.ods-") }
        }
        assertTrue(!temporaryFileRemains)
    }

    @Test
    fun `refresh marks changed assessments stale without reassessing or overwriting them`() = runTest {
        val directory = Files.createTempDirectory("issue-triage-stale-test")
        val output = directory.resolve("inventory.ods")
        val arguments = TriageArguments(
            linearConfig = directory.resolve("unused.json"),
            output = output,
            team = "TST",
            project = "Test Project",
            label = "test-label",
            repositoryRoots = listOf(directory),
        )
        var currentIssues = issues
        val assessedIdentifiers = mutableListOf<String>()
        val command = IssueTriageCommand(
            source = LinearInventorySource { _, _, _ -> currentIssues },
            commentSource = LinearCommentSource { _, _ -> emptyList() },
            assessor = IssueAssessor { row, _, _ ->
                assessedIdentifiers += row.identifier
                assessment(row.updatedAt)
            },
            workbook = OdsTriageWorkbook(),
            clock = Clock.fixed(Instant.parse("2026-04-02T12:00:00Z"), ZoneOffset.UTC),
        )
        command.run(arguments)
        editPromptVersion(output, "id-2", "issue-triage-v0")

        assessedIdentifiers.clear()
        currentIssues = issues.map { issue ->
            if (issue.id == "id-1") {
                issue.copy(updatedAt = KotlinInstant.parse("2026-04-03T00:00:00Z"))
            } else {
                issue
            }
        }
        command.run(arguments)

        assertTrue(assessedIdentifiers.isEmpty())
        OdfSpreadsheetDocument.loadDocument(output.toFile()).use { document ->
            val ranking = document.spreadsheetTables.single { it.tableName == "Ranking" }
            val rowsById = (1 until ranking.rowCount).associateBy { rowIndex ->
                ranking.getCellByPosition(TriageColumn.LINEAR_ID.ordinal, rowIndex).stringValue
            }
            listOf("id-1", "id-2").forEach { linearId ->
                val rowIndex = requireNotNull(rowsById[linearId])
                assertEquals("4", ranking.getCellByPosition(TriageColumn.DIFFICULTY.ordinal, rowIndex).stringValue)
                assertEquals(
                    "src/example.kt:12",
                    ranking.getCellByPosition(TriageColumn.RATIONALE.ordinal, rowIndex).stringValue,
                )
                assertEquals(
                    AssessmentStatus.Stale.name,
                    ranking.getCellByPosition(TriageColumn.ASSESSMENT_STATUS.ordinal, rowIndex).stringValue,
                )
            }
            val unchangedRow = requireNotNull(rowsById["id-3"])
            assertEquals(
                AssessmentStatus.Assessed.name,
                ranking.getCellByPosition(TriageColumn.ASSESSMENT_STATUS.ordinal, unchangedRow).stringValue,
            )
        }
    }

    @Test
    fun `archives completed canceled Linear-archived and removed issues and reactivates preserved history`() = runTest {
        val directory = Files.createTempDirectory("issue-triage-archive-test")
        val output = directory.resolve("inventory.ods")
        val arguments = triageArguments(directory, output)
        val linearArchivedIssue = issue("id-4", "TST-4", "Test Project", emptySet())
        var currentIssues = issues + linearArchivedIssue
        val assessedIdentifiers = Collections.synchronizedList(mutableListOf<String>())
        fun commandAt(timestamp: String) = IssueTriageCommand(
            source = LinearInventorySource { _, _, _ -> currentIssues },
            commentSource = LinearCommentSource { _, _ -> emptyList() },
            assessor = IssueAssessor { row, _, _ ->
                assessedIdentifiers += row.identifier
                assessment(row.updatedAt)
            },
            workbook = OdsTriageWorkbook(),
            clock = Clock.fixed(Instant.parse(timestamp), ZoneOffset.UTC),
        )

        commandAt("2026-04-02T12:00:00Z").run(arguments)
        editAssessment(output, "id-3")
        assessedIdentifiers.clear()
        currentIssues = inactiveIssues(linearArchivedIssue)

        commandAt("2026-04-03T12:00:00Z").run(arguments)

        assertTrue(assessedIdentifiers.isEmpty())
        assertArchivedRows(
            output = output,
            expectedReasons = mapOf(
                "id-1" to ArchiveReason.COMPLETED.value,
                "id-2" to ArchiveReason.CANCELED.value,
                "id-3" to ArchiveReason.NO_LONGER_MATCHES_SCOPE.value,
                "id-4" to ArchiveReason.ARCHIVED.value,
            ),
            expectedTimestamp = "2026-04-03T12:00:00Z",
        )

        currentIssues = listOf(issues[2])
        commandAt("2026-04-04T12:00:00Z").run(arguments)

        assertTrue(assessedIdentifiers.isEmpty())
        OdfSpreadsheetDocument.loadDocument(output.toFile()).use { document ->
            val ranking = document.spreadsheetTables.single { it.tableName == "Ranking" }
            val archived = document.spreadsheetTables.single { it.tableName == "Archived" }
            assertEquals(2, ranking.rowCount)
            assertEquals("id-3", ranking.getCellByPosition(TriageColumn.LINEAR_ID.ordinal, 1).stringValue)
            assertEquals("human estimate", ranking.getCellByPosition(TriageColumn.DIFFICULTY.ordinal, 1).stringValue)
            assertEquals(
                "Human-edited evidence",
                ranking.getCellByPosition(TriageColumn.RATIONALE.ordinal, 1).stringValue,
            )
            assertEquals(4, archived.rowCount)
            val archivedIds = (1 until archived.rowCount).map { rowIndex ->
                archived.getCellByPosition(TriageColumn.LINEAR_ID.ordinal, rowIndex).stringValue
            }
            assertEquals(listOf("id-1", "id-2", "id-4"), archivedIds)
            archivedIds.indices.forEach { index ->
                assertEquals(
                    "2026-04-03T12:00:00Z",
                    archived.getCellByPosition(TriageColumn.entries.size + 1, index + 1).stringValue,
                )
            }
        }
    }

    @Test
    fun `retriage replaces only explicitly selected assessment`() = runTest {
        val directory = Files.createTempDirectory("issue-triage-retriage-test")
        val output = directory.resolve("inventory.ods")
        val baseArguments = triageArguments(directory, output)
        var currentIssues = issues
        var difficulty = 4
        val assessedIdentifiers = Collections.synchronizedList(mutableListOf<String>())
        val command = IssueTriageCommand(
            source = LinearInventorySource { _, _, _ -> currentIssues },
            commentSource = LinearCommentSource { _, _ -> emptyList() },
            assessor = IssueAssessor { row, _, configuration ->
                assessedIdentifiers += row.identifier
                assessment(row.updatedAt).copy(
                    difficulty = difficulty,
                    model = configuration.model,
                    thinking = configuration.thinking,
                )
            },
            workbook = OdsTriageWorkbook(),
        )
        command.run(baseArguments)

        assessedIdentifiers.clear()
        difficulty = 9
        currentIssues = issues.map { issue ->
            if (issue.identifier == "TST-1") {
                issue.copy(updatedAt = KotlinInstant.parse("2026-04-03T00:00:00Z"))
            } else {
                issue
            }
        }
        command.run(
            baseArguments.copy(
                model = "replacement-model",
                thinking = "high",
                retriage = setOf("TST-1"),
            ),
        )

        assertEquals(listOf("TST-1"), assessedIdentifiers)
        assertTargetedRetriageResult(output)
    }

    @Test
    fun `failed retriage preserves the last good assessment`() = runTest {
        val directory = Files.createTempDirectory("issue-triage-failed-retriage-test")
        val output = directory.resolve("inventory.ods")
        val baseArguments = triageArguments(directory, output)
        var commentsAvailable = true
        val command = IssueTriageCommand(
            source = LinearInventorySource { _, _, _ -> issues },
            commentSource = LinearCommentSource { identifier, _ ->
                check(commentsAvailable || identifier != "TST-1") { "comments unavailable" }
                emptyList()
            },
            assessor = IssueAssessor { row, _, _ -> assessment(row.updatedAt) },
            workbook = OdsTriageWorkbook(),
        )
        command.run(baseArguments)
        editAssessment(output, "id-1")
        commentsAvailable = false

        val result = command.run(baseArguments.copy(retriage = setOf("TST-1")))

        assertEquals(listOf(AssessmentFailure("TST-1", "comments unavailable")), result.failures)
        OdfSpreadsheetDocument.loadDocument(output.toFile()).use { document ->
            val ranking = document.spreadsheetTables.single { it.tableName == "Ranking" }
            val rowIndex = (1 until ranking.rowCount).single { index ->
                ranking.getCellByPosition(TriageColumn.LINEAR_ID.ordinal, index).stringValue == "id-1"
            }
            assertEquals(
                "human estimate",
                ranking.getCellByPosition(TriageColumn.DIFFICULTY.ordinal, rowIndex).stringValue,
            )
            assertEquals(
                "Human-edited evidence",
                ranking.getCellByPosition(TriageColumn.RATIONALE.ordinal, rowIndex).stringValue,
            )
            assertEquals(
                AssessmentStatus.Assessed.name,
                ranking.getCellByPosition(TriageColumn.ASSESSMENT_STATUS.ordinal, rowIndex).stringValue,
            )
        }
    }

    @Test
    fun `retriage all replaces every assessment`() = runTest {
        val directory = Files.createTempDirectory("issue-triage-retriage-all-test")
        val output = directory.resolve("inventory.ods")
        val baseArguments = triageArguments(directory, output)
        val assessedIdentifiers = Collections.synchronizedList(mutableListOf<String>())
        val command = IssueTriageCommand(
            source = LinearInventorySource { _, _, _ -> issues },
            commentSource = LinearCommentSource { _, _ -> emptyList() },
            assessor = IssueAssessor { row, _, _ ->
                assessedIdentifiers += row.identifier
                assessment(row.updatedAt)
            },
            workbook = OdsTriageWorkbook(),
        )
        command.run(baseArguments)

        assessedIdentifiers.clear()
        command.run(baseArguments.copy(retriage = setOf("all")))

        assertEquals(listOf("TST-1", "TST-2", "TST-3"), assessedIdentifiers.sorted())
    }

    @Test
    fun `rejects retriage ticket identifiers outside the active inventory before assessment`() = runTest {
        val directory = Files.createTempDirectory("issue-triage-invalid-retriage-test")
        var assessmentCount = 0
        val command = IssueTriageCommand(
            source = LinearInventorySource { _, _, _ -> issues },
            commentSource = LinearCommentSource { _, _ -> emptyList() },
            assessor = IssueAssessor { row, _, _ ->
                assessmentCount += 1
                assessment(row.updatedAt)
            },
            workbook = OdsTriageWorkbook(),
        )

        val error = assertFailsWith<IllegalArgumentException> {
            command.run(
                triageArguments(directory, directory.resolve("inventory.ods")).copy(
                    retriage = setOf("TST-2006"),
                ),
            )
        }

        assertEquals(0, assessmentCount)
        assertTrue(error.message.orEmpty().contains("TST-2006"))
    }

    private fun assertArchivedRows(
        output: java.nio.file.Path,
        expectedReasons: Map<String, String>,
        expectedTimestamp: String,
    ) {
        OdfSpreadsheetDocument.loadDocument(output.toFile()).use { document ->
            val ranking = document.spreadsheetTables.single { it.tableName == "Ranking" }
            val archived = document.spreadsheetTables.single { it.tableName == "Archived" }
            assertEquals(1, ranking.rowCount)
            assertEquals(expectedReasons.size + 1, archived.rowCount)
            val rowsById = (1 until archived.rowCount).associateBy { rowIndex ->
                archived.getCellByPosition(TriageColumn.LINEAR_ID.ordinal, rowIndex).stringValue
            }
            expectedReasons.forEach { (linearId, reason) ->
                val rowIndex = requireNotNull(rowsById[linearId])
                assertEquals(
                    reason,
                    archived.getCellByPosition(TriageColumn.entries.size, rowIndex).stringValue,
                )
                assertEquals(
                    expectedTimestamp,
                    archived.getCellByPosition(TriageColumn.entries.size + 1, rowIndex).stringValue,
                )
                val expectedDifficulty = if (linearId == "id-3") "human estimate" else "4"
                assertEquals(
                    expectedDifficulty,
                    archived.getCellByPosition(TriageColumn.DIFFICULTY.ordinal, rowIndex).stringValue,
                )
            }
            val removedRow = requireNotNull(rowsById["id-3"])
            assertEquals(
                "human estimate",
                archived.getCellByPosition(TriageColumn.DIFFICULTY.ordinal, removedRow).stringValue,
            )
            assertEquals(
                "Human-edited evidence",
                archived.getCellByPosition(TriageColumn.RATIONALE.ordinal, removedRow).stringValue,
            )
        }
    }

    private fun assertTargetedRetriageResult(output: java.nio.file.Path) {
        OdfSpreadsheetDocument.loadDocument(output.toFile()).use { document ->
            val ranking = document.spreadsheetTables.single { it.tableName == "Ranking" }
            val rowsByTicket = (1 until ranking.rowCount).associateBy { rowIndex ->
                ranking.getCellByPosition(TriageColumn.TICKET_ID.ordinal, rowIndex).stringValue
            }
            val replacedRow = requireNotNull(rowsByTicket["TST-1"])
            assertEquals("9", ranking.getCellByPosition(TriageColumn.DIFFICULTY.ordinal, replacedRow).stringValue)
            assertEquals(
                "2026-04-03T00:00:00Z",
                ranking.getCellByPosition(TriageColumn.ASSESSMENT_SOURCE_UPDATED_AT.ordinal, replacedRow).stringValue,
            )
            assertEquals(
                AssessmentStatus.Assessed.name,
                ranking.getCellByPosition(TriageColumn.ASSESSMENT_STATUS.ordinal, replacedRow).stringValue,
            )
            assertEquals(
                "replacement-model",
                ranking.getCellByPosition(TriageColumn.ASSESSMENT_MODEL.ordinal, replacedRow).stringValue,
            )
            assertEquals(
                "high",
                ranking.getCellByPosition(TriageColumn.THINKING_LEVEL.ordinal, replacedRow).stringValue,
            )
            val preservedRow = requireNotNull(rowsByTicket["TST-2"])
            assertEquals("4", ranking.getCellByPosition(TriageColumn.DIFFICULTY.ordinal, preservedRow).stringValue)
            assertEquals(
                "2026-04-02T00:00:00Z",
                ranking.getCellByPosition(TriageColumn.ASSESSMENT_SOURCE_UPDATED_AT.ordinal, preservedRow).stringValue,
            )
            assertEquals(
                DEFAULT_ASSESSMENT_MODEL,
                ranking.getCellByPosition(TriageColumn.ASSESSMENT_MODEL.ordinal, preservedRow).stringValue,
            )
        }
    }

    private fun triageArguments(directory: java.nio.file.Path, output: java.nio.file.Path) = TriageArguments(
        linearConfig = directory.resolve("unused.json"),
        output = output,
        team = "TST",
        project = "Test Project",
        label = "test-label",
        repositoryRoots = listOf(directory),
    )

    private fun editPromptVersion(
        output: java.nio.file.Path,
        linearId: String,
        promptVersion: String,
    ) {
        OdfSpreadsheetDocument.loadDocument(output.toFile()).use { document ->
            val ranking = document.spreadsheetTables.single { it.tableName == "Ranking" }
            val rowIndex = (1 until ranking.rowCount).single { index ->
                ranking.getCellByPosition(TriageColumn.LINEAR_ID.ordinal, index).stringValue == linearId
            }
            ranking.getCellByPosition(TriageColumn.PROMPT_VERSION.ordinal, rowIndex).setStringValue(promptVersion)
            document.save(output.toFile())
        }
    }

    private fun editAssessment(output: java.nio.file.Path, linearId: String) {
        OdfSpreadsheetDocument.loadDocument(output.toFile()).use { document ->
            val ranking = document.spreadsheetTables.single { it.tableName == "Ranking" }
            val rowIndex = (1 until ranking.rowCount).single { index ->
                ranking.getCellByPosition(0, index).stringValue == linearId
            }
            ranking.getCellByPosition(7, rowIndex).setStringValue("human estimate")
            ranking.getCellByPosition(10, rowIndex).setStringValue("Human-edited evidence")
            ranking.getCellByPosition(12, rowIndex).setStringValue("manual")
            document.save(output.toFile())
        }
    }

    private companion object {
        val issues = listOf(
            issue("id-1", "TST-1", "Test Project", emptySet()),
            issue("id-2", "TST-2", "Test Project", setOf("test-label")),
            issue("id-3", "TST-3", null, setOf("test-label")),
        )

        fun inactiveIssues(linearArchivedIssue: LinearTriageIssue) = listOf(
            issues[0].copy(
                stateName = "Done",
                stateType = "completed",
                completedAt = KotlinInstant.parse("2026-04-03T09:00:00Z"),
            ),
            issues[1].copy(
                stateName = "Canceled",
                stateType = "canceled",
                canceledAt = KotlinInstant.parse("2026-04-03T10:00:00Z"),
            ),
            linearArchivedIssue.copy(
                archivedAt = KotlinInstant.parse("2026-04-03T11:00:00Z"),
            ),
        )

        fun assessment(updatedAt: String?) = IssueAssessment(
            difficulty = 4,
            prNeeded = PrNeeded.Yes,
            prReason = "Exact reason",
            rationale = "src/example.kt:12",
            confidence = AssessmentConfidence.High,
            model = DEFAULT_ASSESSMENT_MODEL,
            thinking = DEFAULT_THINKING_LEVEL,
            sourceUpdatedAt = updatedAt,
            promptVersion = ASSESSMENT_PROMPT_VERSION,
            status = AssessmentStatus.Assessed,
        )

        fun issue(
            id: String,
            identifier: String,
            project: String?,
            labels: Set<String>,
        ) = LinearTriageIssue(
            id = id,
            identifier = identifier,
            title = "Title $identifier",
            description = "Description $identifier",
            url = "https://linear/$identifier",
            stateName = "Backlog",
            projectName = project,
            labelNames = labels,
            updatedAt = KotlinInstant.parse("2026-04-02T00:00:00Z"),
        )
    }
}
