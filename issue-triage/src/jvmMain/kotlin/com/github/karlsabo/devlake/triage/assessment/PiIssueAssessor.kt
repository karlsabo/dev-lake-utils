package com.github.karlsabo.devlake.triage.assessment

import com.github.karlsabo.devlake.triage.NoOpTriageProgressReporter
import com.github.karlsabo.devlake.triage.TriageProgressEvent
import com.github.karlsabo.devlake.triage.TriageProgressReporter
import com.github.karlsabo.devlake.triage.TriageRow
import com.github.karlsabo.projectmanagement.ProjectComment
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

internal fun interface IssueAssessor {
    fun assess(
        row: TriageRow,
        comments: List<ProjectComment>,
        configuration: AssessmentConfiguration,
    ): IssueAssessment
}

internal data class AssessmentConfiguration(
    val model: String,
    val thinking: String,
    val repositoryRoots: List<Path>,
    val progressReporter: TriageProgressReporter = NoOpTriageProgressReporter,
)

internal data class ProcessResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

internal fun interface PiProcessRunner {
    fun run(
        command: List<String>,
        prompt: String,
        workingDirectory: Path,
        timeout: Duration,
    ): ProcessResult
}

internal class PiIssueAssessor(
    private val processRunner: PiProcessRunner = JvmPiProcessRunner(),
    private val piCommandPrefix: List<String> = listOf("pi"),
    private val timeout: Duration = DEFAULT_TIMEOUT,
    private val transientRetries: Int = DEFAULT_TRANSIENT_RETRIES,
) : IssueAssessor {
    init {
        require(piCommandPrefix.isNotEmpty()) { "pi command must not be empty" }
        require(!timeout.isNegative && !timeout.isZero) { "pi timeout must be positive" }
        require(transientRetries >= 0) { "Transient retry count must not be negative" }
    }

    override fun assess(
        row: TriageRow,
        comments: List<ProjectComment>,
        configuration: AssessmentConfiguration,
    ): IssueAssessment {
        require(configuration.repositoryRoots.isNotEmpty()) { "At least one repository root is required" }
        val prompt = AssessmentPrompt.create(
            row = row,
            comments = comments,
            repositoryRoots = configuration.repositoryRoots,
            model = configuration.model,
            thinking = configuration.thinking,
        )
        return assessWithRetry(
            command = piCommand(configuration),
            prompt = prompt,
            workingDirectory = configuration.repositoryRoots.first(),
            row = row,
            configuration = configuration,
        )
    }

    private fun assessWithRetry(
        command: List<String>,
        prompt: String,
        workingDirectory: Path,
        row: TriageRow,
        configuration: AssessmentConfiguration,
    ): IssueAssessment {
        var lastFailure: AssessmentException? = null
        repeat(transientRetries + 1) { attemptIndex ->
            try {
                val result = processRunner.run(command, prompt, workingDirectory, timeout)
                if (result.exitCode != 0) {
                    throw PiProcessException(
                        "pi assessment failed for ${row.identifier} with exit code " +
                            "${result.exitCode}: ${result.stderr.trim()}",
                    )
                }
                return parseAndValidate(result.stdout, row, configuration)
            } catch (failure: AssessmentException) {
                lastFailure = failure
                if (attemptIndex < transientRetries) {
                    configuration.progressReporter.report(
                        TriageProgressEvent.AssessmentRetrying(row.identifier, attempt = attemptIndex + 2),
                    )
                }
            }
        }
        throw requireNotNull(lastFailure)
    }

    private fun parseAndValidate(
        stdout: String,
        row: TriageRow,
        configuration: AssessmentConfiguration,
    ): IssueAssessment = try {
        IssueAssessment.parse(stdout).also { assessment ->
            validateMetadata(assessment, row, configuration)
        }
    } catch (failure: SerializationException) {
        throw AssessmentException("pi returned an invalid assessment: ${failure.message}", failure)
    } catch (failure: IllegalArgumentException) {
        throw AssessmentException("pi returned an invalid assessment: ${failure.message}", failure)
    }

    private fun piCommand(configuration: AssessmentConfiguration): List<String> = piCommandPrefix + listOf(
        "--print",
        "--no-session",
        "--model",
        configuration.model,
        "--thinking",
        configuration.thinking,
        "--tools",
        READ_ONLY_TOOLS,
        "--no-extensions",
        "--extension",
        ROOT_GUARD_EXTENSION.toString(),
        "--triage-roots",
        Json.encodeToString(configuration.repositoryRoots.map { root -> root.toRealPath().toString() }),
        "--no-skills",
        "--no-prompt-templates",
        "--no-context-files",
        "--no-approve",
    )

    private fun validateMetadata(
        assessment: IssueAssessment,
        row: TriageRow,
        configuration: AssessmentConfiguration,
    ) {
        require(assessment.model == configuration.model) { "Assessment returned an unexpected model" }
        require(assessment.thinking == configuration.thinking) { "Assessment returned an unexpected thinking level" }
        require(assessment.sourceUpdatedAt == row.updatedAt) { "Assessment returned an unexpected source timestamp" }
        require(assessment.promptVersion == ASSESSMENT_PROMPT_VERSION) {
            "Assessment returned an unexpected prompt version"
        }
        require(assessment.status == AssessmentStatus.Assessed) { "Assessment returned an unexpected status" }
    }

    private companion object {
        const val READ_ONLY_TOOLS = "read,grep,find,ls"
        const val DEFAULT_TRANSIENT_RETRIES = 1
        val DEFAULT_TIMEOUT: Duration = Duration.ofMinutes(10)
    }
}

private val ROOT_GUARD_EXTENSION: Path by lazy {
    val extension = Files.createTempFile("issue-triage-root-guard-", ".ts")
    val source = requireNotNull(PiIssueAssessor::class.java.getResourceAsStream("/pi/repository-root-guard.ts")) {
        "Missing pi repository root guard"
    }
    source.use { input -> Files.newOutputStream(extension).use(input::copyTo) }
    extension.toFile().deleteOnExit()
    extension
}

internal open class AssessmentException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

internal class PiProcessException(
    message: String,
    cause: Throwable? = null,
) : AssessmentException(message, cause)

private class JvmPiProcessRunner : PiProcessRunner {
    override fun run(
        command: List<String>,
        prompt: String,
        workingDirectory: Path,
        timeout: Duration,
    ): ProcessResult {
        val process = try {
            ProcessBuilder(command).directory(workingDirectory.toFile()).start()
        } catch (failure: IOException) {
            throw PiProcessException("Unable to start pi: ${failure.message}", failure)
        }
        val stdout = AtomicReference(byteArrayOf())
        val stderr = AtomicReference(byteArrayOf())
        val writerFailure = AtomicReference<Throwable?>()
        val writer = thread(name = "pi-stdin-writer") {
            runCatching {
                process.outputStream.bufferedWriter(StandardCharsets.UTF_8).use { it.write(prompt) }
            }.onFailure(writerFailure::set)
        }
        val stdoutReader = thread(name = "pi-stdout-reader") { stdout.set(process.inputStream.readAllBytes()) }
        val stderrReader = thread(name = "pi-stderr-reader") { stderr.set(process.errorStream.readAllBytes()) }
        val completed = try {
            process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (failure: InterruptedException) {
            terminate(process)
            joinThreads(writer, stdoutReader, stderrReader)
            Thread.currentThread().interrupt()
            throw failure
        }
        if (!completed) {
            terminate(process)
            joinThreads(writer, stdoutReader, stderrReader)
            throw PiProcessException("pi assessment timed out after ${timeout.toMillis()} ms")
        }
        joinThreads(writer, stdoutReader, stderrReader)
        writerFailure.get()?.let { failure ->
            throw PiProcessException("Unable to send assessment prompt to pi: ${failure.message}", failure)
        }
        return ProcessResult(
            exitCode = process.exitValue(),
            stdout = stdout.get().toString(StandardCharsets.UTF_8),
            stderr = stderr.get().toString(StandardCharsets.UTF_8),
        )
    }
}

private fun terminate(process: Process) {
    process.toHandle().descendants().forEach(ProcessHandle::destroy)
    process.destroy()
    if (!process.waitFor(TERMINATION_GRACE_MILLIS, TimeUnit.MILLISECONDS)) {
        process.toHandle().descendants().forEach(ProcessHandle::destroyForcibly)
        process.destroyForcibly()
        process.waitFor()
    }
}

private fun joinThreads(vararg threads: Thread) {
    threads.forEach(Thread::join)
}

private const val TERMINATION_GRACE_MILLIS = 250L
