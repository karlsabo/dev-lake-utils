package com.github.karlsabo.devlake.triage

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TriageArgumentsTest {
    @Test
    fun `parses assessment defaults and repeatable repository roots`() {
        val firstRoot = Files.createTempDirectory("triage-root-one")
        val secondRoot = Files.createTempDirectory("triage-root-two")
        val arguments = TriageArguments.parse(
            arrayOf(
                "--linear-config",
                "/tmp/linear.json",
                "--team",
                "TST",
                "--project",
                "Test Project",
                "--label",
                "test-label",
                "--repository-root",
                firstRoot.toString(),
                "--repository-root",
                secondRoot.toString(),
                "--output",
                "/tmp/triage.ods",
            ),
        )

        assertEquals("TST", arguments.team)
        assertEquals("Test Project", arguments.project)
        assertEquals("test-label", arguments.label)
        assertEquals(listOf(firstRoot, secondRoot), arguments.repositoryRoots)
        assertEquals("openai-codex/gpt-5.6-sol", arguments.model)
        assertEquals("medium", arguments.thinking)
        assertEquals(emptySet(), arguments.retriage)
        assertEquals(4, arguments.assessmentConcurrency)
    }

    @Test
    fun `expands home relative paths without source-specific defaults`() {
        val home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize()

        val arguments = TriageArguments.parse(
            arrayOf(
                "--linear-config",
                "~/.config/linear.json",
                "--team",
                "PLAT",
                "--label",
                "platform-review",
                "--repository-root",
                "~",
                "--output",
                "~/reports/platform.ods",
            ),
        )

        assertEquals(home.resolve(".config/linear.json"), arguments.linearConfig)
        assertEquals(listOf(home), arguments.repositoryRoots)
        assertEquals(home.resolve("reports/platform.ods"), arguments.output)
    }

    @Test
    fun `parses assessment concurrency`() {
        val repositoryRoot = Files.createTempDirectory("triage-concurrency-root")

        val arguments = TriageArguments.parse(
            requiredArguments(repositoryRoot) + arrayOf("--assessment-concurrency", "2"),
        )

        assertEquals(2, arguments.assessmentConcurrency)
    }

    @Test
    fun `rejects invalid assessment concurrency`() {
        val repositoryRoot = Files.createTempDirectory("triage-invalid-concurrency-root")

        listOf("0", "-1", "many").forEach { invalidValue ->
            assertFailsWith<IllegalArgumentException> {
                TriageArguments.parse(
                    requiredArguments(repositoryRoot) +
                        arrayOf("--assessment-concurrency", invalidValue),
                )
            }
        }
    }

    @Test
    fun `parses repeatable retriage ticket identifiers`() {
        val repositoryRoot = Files.createTempDirectory("triage-retriage-root")

        val arguments = TriageArguments.parse(
            requiredArguments(repositoryRoot) + arrayOf(
                "--retriage",
                "TST-2006",
                "--retriage",
                "TST-2010",
            ),
        )

        assertEquals(setOf("TST-2006", "TST-2010"), arguments.retriage)
    }

    @Test
    fun `accepts all as the only retriage selector`() {
        val repositoryRoot = Files.createTempDirectory("triage-retriage-all-root")

        val arguments = TriageArguments.parse(
            requiredArguments(repositoryRoot) + arrayOf("--retriage", "all"),
        )

        assertEquals(setOf("all"), arguments.retriage)
    }

    @Test
    fun `rejects all combined with retriage ticket identifiers`() {
        val repositoryRoot = Files.createTempDirectory("triage-invalid-retriage-root")

        assertFailsWith<IllegalArgumentException> {
            TriageArguments.parse(
                requiredArguments(repositoryRoot) + arrayOf(
                    "--retriage",
                    "all",
                    "--retriage",
                    "TST-2006",
                ),
            )
        }
    }

    @Test
    fun `requires a repository root`() {
        assertFailsWith<IllegalArgumentException> {
            TriageArguments.parse(
                arrayOf(
                    "--linear-config",
                    "/tmp/linear.json",
                    "--team",
                    "TST",
                    "--label",
                    "test-label",
                    "--output",
                    "/tmp/triage.ods",
                ),
            )
        }
    }

    private fun requiredArguments(repositoryRoot: java.nio.file.Path) = arrayOf(
        "--linear-config",
        "/tmp/linear.json",
        "--team",
        "TST",
        "--label",
        "test-label",
        "--repository-root",
        repositoryRoot.toString(),
        "--output",
        "/tmp/triage.ods",
    )
}
