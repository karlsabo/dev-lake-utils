package com.github.karlsabo.devlake.triage

import java.nio.file.Files
import java.nio.file.Path

internal const val DEFAULT_ASSESSMENT_MODEL = "openai-codex/gpt-5.6-sol"
internal const val DEFAULT_THINKING_LEVEL = "medium"
internal const val DEFAULT_ASSESSMENT_CONCURRENCY = 4

internal data class TriageArguments(
    val linearConfig: Path,
    val output: Path,
    val team: String,
    val project: String?,
    val label: String?,
    val repositoryRoots: List<Path>,
    val model: String = DEFAULT_ASSESSMENT_MODEL,
    val thinking: String = DEFAULT_THINKING_LEVEL,
    val retriage: Set<String> = emptySet(),
    val assessmentConcurrency: Int = DEFAULT_ASSESSMENT_CONCURRENCY,
) {
    init {
        require(assessmentConcurrency > 0) { "--assessment-concurrency must be positive" }
        require(!retriage.requestsAllRetriage() || retriage.size == 1) {
            "--retriage all cannot be combined with ticket identifiers"
        }
    }

    companion object {
        fun parse(arguments: Array<String>): TriageArguments {
            val values = parseNamedValues(arguments)
            val project = values.singleOrNull(PROJECT)
            val label = values.singleOrNull(LABEL)
            require(project != null || label != null) { "At least one of --project or --label is required" }
            val output = normalizedPath(values.required(OUTPUT))
            require(output.fileName.toString().endsWith(".ods", ignoreCase = true)) {
                "Output path must end in .ods"
            }
            val roots = values.getValue(REPOSITORY_ROOT).map(::validatedRepositoryRoot)
            return TriageArguments(
                linearConfig = normalizedPath(values.required(LINEAR_CONFIG)),
                output = output,
                team = values.required(TEAM),
                project = project,
                label = label,
                repositoryRoots = roots,
                model = values.singleOrNull(MODEL) ?: DEFAULT_ASSESSMENT_MODEL,
                thinking = validatedThinking(values.singleOrNull(THINKING) ?: DEFAULT_THINKING_LEVEL),
                retriage = validatedRetriage(values[RETRIAGE].orEmpty()),
                assessmentConcurrency = validatedAssessmentConcurrency(values.singleOrNull(ASSESSMENT_CONCURRENCY)),
            )
        }
    }
}

private const val LINEAR_CONFIG = "--linear-config"
private const val OUTPUT = "--output"
private const val TEAM = "--team"
private const val PROJECT = "--project"
private const val LABEL = "--label"
private const val REPOSITORY_ROOT = "--repository-root"
private const val MODEL = "--model"
private const val THINKING = "--thinking"
private const val RETRIAGE = "--retriage"
private const val ASSESSMENT_CONCURRENCY = "--assessment-concurrency"
private const val RETRIAGE_ALL = "all"
private val SUPPORTED_ARGUMENTS = setOf(
    LINEAR_CONFIG,
    OUTPUT,
    TEAM,
    PROJECT,
    LABEL,
    REPOSITORY_ROOT,
    MODEL,
    THINKING,
    RETRIAGE,
    ASSESSMENT_CONCURRENCY,
)
private val REPEATABLE_ARGUMENTS = setOf(REPOSITORY_ROOT, RETRIAGE)
private val THINKING_LEVELS = setOf("off", "minimal", "low", "medium", "high", "xhigh", "max")

private fun parseNamedValues(arguments: Array<String>): Map<String, List<String>> {
    require(arguments.size % 2 == 0) { "Every argument must have a value" }
    val values = mutableMapOf<String, MutableList<String>>()
    arguments.asList().chunked(2).forEach { pair ->
        val name = pair.first()
        val value = pair.last()
        require(name in SUPPORTED_ARGUMENTS) { "Unknown argument: $name" }
        require(value.isNotBlank()) { "$name must not be blank" }
        require(name in REPEATABLE_ARGUMENTS || name !in values) { "Argument must not be repeated: $name" }
        values.getOrPut(name, ::mutableListOf).add(value)
    }
    require(values[REPOSITORY_ROOT].orEmpty().isNotEmpty()) { "At least one --repository-root is required" }
    return values
}

private fun Map<String, List<String>>.required(
    name: String,
): String = requireNotNull(singleOrNull(name)) { "Missing required argument: $name" }

private fun Map<String, List<String>>.singleOrNull(name: String): String? = this[name]?.single()

private fun validatedRepositoryRoot(value: String): Path {
    val path = normalizedPath(value)
    require(Files.isDirectory(path)) { "Repository root is not a directory: $path" }
    return path
}

private fun normalizedPath(value: String): Path {
    val path = when {
        value == "~" -> Path.of(System.getProperty("user.home"))
        value.startsWith("~/") -> Path.of(System.getProperty("user.home")).resolve(value.removePrefix("~/"))
        else -> Path.of(value)
    }
    return path.toAbsolutePath().normalize()
}

private fun validatedThinking(value: String): String {
    require(value in THINKING_LEVELS) { "Unsupported thinking level: $value" }
    return value
}

private fun validatedRetriage(values: List<String>): Set<String> = values.toSet()

private fun validatedAssessmentConcurrency(value: String?): Int {
    if (value == null) return DEFAULT_ASSESSMENT_CONCURRENCY
    val concurrency = requireNotNull(value.toIntOrNull()) { "--assessment-concurrency must be an integer" }
    require(concurrency > 0) { "--assessment-concurrency must be positive" }
    return concurrency
}

internal fun Set<String>.requestsAllRetriage(): Boolean = RETRIAGE_ALL in this

internal fun Set<String>.requestsRetriage(identifier: String): Boolean = requestsAllRetriage() || identifier in this
