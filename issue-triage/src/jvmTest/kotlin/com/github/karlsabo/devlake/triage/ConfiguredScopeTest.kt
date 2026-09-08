package com.github.karlsabo.devlake.triage

import com.github.karlsabo.devlake.triage.assessment.ASSESSMENT_PROMPT_VERSION
import com.github.karlsabo.devlake.triage.assessment.PiIssueAssessor
import com.github.karlsabo.devlake.triage.assessment.PiProcessRunner
import com.github.karlsabo.devlake.triage.assessment.ProcessResult
import com.github.karlsabo.devlake.triage.spreadsheet.OdsTriageWorkbook
import com.github.karlsabo.linear.LinearTriageIssue
import kotlinx.coroutines.test.runTest
import org.odftoolkit.odfdom.doc.OdfSpreadsheetDocument
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant as KotlinInstant

class ConfiguredScopeTest {
    @Test
    fun `custom configuration flows through query assessment summary and output`() = runTest {
        val directory = Files.createTempDirectory("configured-triage-scope")
        val firstRoot = Files.createDirectory(directory.resolve("service-one"))
        val secondRoot = Files.createDirectory(directory.resolve("service-two"))
        val output = directory.resolve("reports/platform-triage.ods")
        val arguments = TriageArguments.parse(
            arrayOf(
                "--linear-config",
                directory.resolve("linear.json").toString(),
                "--team",
                "PLAT",
                "--project",
                "Platform Reliability",
                "--label",
                "reliability-review",
                "--repository-root",
                firstRoot.toString(),
                "--repository-root",
                secondRoot.toString(),
                "--model",
                "example/custom-model",
                "--thinking",
                "high",
                "--output",
                output.toString(),
            ),
        )
        var requestedScope: Triple<String, String?, String?>? = null
        val processRunner = RecordingPiProcessRunner()
        val command = IssueTriageCommand(
            source = LinearInventorySource { team, project, label ->
                requestedScope = Triple(team, project, label)
                listOf(platformIssue)
            },
            commentSource = LinearCommentSource { _, _ -> emptyList() },
            assessor = PiIssueAssessor(processRunner = processRunner),
            workbook = OdsTriageWorkbook(),
            clock = Clock.fixed(Instant.parse("2026-05-01T12:00:00Z"), ZoneOffset.UTC),
        )

        val result = command.run(arguments)

        assertEquals(0, result.exitCode)
        assertEquals(Triple("PLAT", "Platform Reliability", "reliability-review"), requestedScope)
        assertEquals(firstRoot, processRunner.workingDirectory)
        assertEquals("example/custom-model", processRunner.command.optionValue("--model"))
        assertEquals("high", processRunner.command.optionValue("--thinking"))
        assertTrue(processRunner.prompt.contains(firstRoot.toString()))
        assertTrue(processRunner.prompt.contains(secondRoot.toString()))
        assertTrue(Files.exists(output))
        OdfSpreadsheetDocument.loadDocument(output.toFile()).use { document ->
            val summary = document.spreadsheetTables.single { it.tableName == "Summary" }
            assertEquals("PLAT", summary.getCellByPosition(1, 2).stringValue)
            assertEquals("Platform Reliability", summary.getCellByPosition(1, 3).stringValue)
            assertEquals("reliability-review", summary.getCellByPosition(1, 4).stringValue)
            assertEquals("example/custom-model", summary.getCellByPosition(1, 5).stringValue)
            assertEquals("high", summary.getCellByPosition(1, 6).stringValue)
        }
    }

    private class RecordingPiProcessRunner : PiProcessRunner {
        lateinit var command: List<String>
        lateinit var prompt: String
        lateinit var workingDirectory: Path

        override fun run(
            command: List<String>,
            prompt: String,
            workingDirectory: Path,
            timeout: java.time.Duration,
        ): ProcessResult {
            this.command = command
            this.prompt = prompt
            this.workingDirectory = workingDirectory
            return ProcessResult(
                exitCode = 0,
                stdout = """
                    {
                      "difficulty": 5,
                      "prNeeded": "Yes",
                      "prReason": "Implementation is required",
                      "rationale": "service-one/src/main.kt:10 owns the behavior",
                      "confidence": "High",
                      "model": "example/custom-model",
                      "thinking": "high",
                      "sourceUpdatedAt": "2026-05-01T10:00:00Z",
                      "promptVersion": "$ASSESSMENT_PROMPT_VERSION",
                      "status": "Assessed"
                    }
                """.trimIndent(),
                stderr = "",
            )
        }
    }

    private companion object {
        val platformIssue = LinearTriageIssue(
            id = "platform-id-1",
            identifier = "PLAT-42",
            title = "Improve platform reliability",
            description = "Use the platform repositories.",
            url = "https://linear.test/PLAT-42",
            stateName = "In Progress",
            stateType = "started",
            projectName = "Platform Reliability",
            labelNames = setOf("reliability-review"),
            updatedAt = KotlinInstant.parse("2026-05-01T10:00:00Z"),
            completedAt = null,
            canceledAt = null,
            archivedAt = null,
        )
    }
}

private fun List<String>.optionValue(option: String): String = get(indexOf(option) + 1)
