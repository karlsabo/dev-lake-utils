package devlake.gradle.timing

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.build.event.BuildEventsListenerRegistry
import org.gradle.tooling.events.FinishEvent
import org.gradle.tooling.events.OperationCompletionListener
import org.gradle.tooling.events.task.TaskFailureResult
import org.gradle.tooling.events.task.TaskFinishEvent
import org.gradle.tooling.events.task.TaskSkippedResult
import org.gradle.tooling.events.task.TaskSuccessResult
import java.io.File
import javax.inject.Inject

abstract class TaskTimingPlugin : Plugin<Project> {
    @get:Inject
    abstract val buildEventsListenerRegistry: BuildEventsListenerRegistry

    override fun apply(project: Project) {
        require(project == project.rootProject) { "Task timing must be applied to the root project" }
        if (!project.providers.gradleProperty("taskTiming").map(String::toBoolean).getOrElse(false)) return

        val timingService = project.gradle.sharedServices.registerIfAbsent(
            "taskTiming",
            TaskTimingService::class.java,
        ) {
            parameters.jsonReport.set(project.layout.buildDirectory.file("reports/task-timing.json"))
            parameters.textReport.set(project.layout.buildDirectory.file("reports/task-timing.txt"))
        }
        buildEventsListenerRegistry.onTaskCompletion(timingService)
    }
}

abstract class TaskTimingService :
    BuildService<TaskTimingService.Parameters>,
    OperationCompletionListener,
    AutoCloseable {
    interface Parameters : BuildServiceParameters {
        val jsonReport: RegularFileProperty
        val textReport: RegularFileProperty
    }

    private val records = mutableListOf<TaskTimingRecord>()

    override fun onFinish(event: FinishEvent) {
        if (event !is TaskFinishEvent) return
        val result = event.result
        val record = TaskTimingRecord(
            path = event.descriptor.taskPath,
            startTimeMillis = result.startTime,
            endTimeMillis = result.endTime,
            durationMillis = (result.endTime - result.startTime).coerceAtLeast(0),
            outcome = when (result) {
                is TaskFailureResult -> "FAILED"

                is TaskSkippedResult -> result.skipMessage

                is TaskSuccessResult -> when {
                    result.isFromCache -> "FROM_CACHE"
                    result.isUpToDate -> "UP_TO_DATE"
                    else -> "SUCCESS"
                }

                else -> "UNKNOWN"
            },
            skipped = result is TaskSkippedResult,
            upToDate = (result as? TaskSuccessResult)?.isUpToDate == true,
        )
        synchronized(records) { records.add(record) }
    }

    override fun close() {
        val snapshot = synchronized(records) { records.toList() }
        writeJson(parameters.jsonReport.get().asFile, snapshot)
        writeText(parameters.textReport.get().asFile, snapshot)
    }

    private fun writeJson(file: File, records: List<TaskTimingRecord>) {
        file.parentFile.mkdirs()
        file.writeText(
            records.joinToString(prefix = "[\n", postfix = "\n]\n", separator = ",\n") { record ->
                buildString {
                    append("  {\"path\":\"${record.path.jsonEscaped()}\"")
                    append(",\"startTimeMillis\":${record.startTimeMillis}")
                    append(",\"endTimeMillis\":${record.endTimeMillis}")
                    append(",\"durationMillis\":${record.durationMillis}")
                    append(",\"outcome\":\"${record.outcome}\"")
                    append(",\"skipped\":${record.skipped}")
                    append(",\"upToDate\":${record.upToDate}}")
                }
            },
        )
    }

    private fun writeText(file: File, records: List<TaskTimingRecord>) {
        file.parentFile.mkdirs()
        val sortedRecords = records.sortedWith(
            compareByDescending<TaskTimingRecord> { it.durationMillis }.thenBy { it.path },
        )
        file.writeText(
            buildString {
                appendLine("Duration (ms)  Outcome      Task")
                sortedRecords.forEach { record ->
                    appendLine("%13d  %-11s  %s".format(record.durationMillis, record.outcome, record.path))
                }
            },
        )
    }

    private fun String.jsonEscaped(): String = buildString {
        this@jsonEscaped.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(character)
            }
        }
    }
}

private data class TaskTimingRecord(
    val path: String,
    val startTimeMillis: Long,
    val endTimeMillis: Long,
    val durationMillis: Long,
    val outcome: String,
    val skipped: Boolean,
    val upToDate: Boolean,
)
