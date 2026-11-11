package com.github.karlsabo.devlake.enghub.agent

private const val FAKE_BASELINE_REVISION = "4f0c1a2"
private const val FAKE_PATCH = "--- a/Greeting.kt\n+++ b/Greeting.kt\n@@ -1 +1 @@\n" +
    "-fun greet() = \"Hello\"\n+fun greet() = \"Hello, Ada!\"\n"

/**
 * A scripted [WorkingTreeChanges] that records when the flow reads the working tree and reports the
 * change set the reviewer must see.
 */
internal class RecordingWorkingTreeChanges(
    private val report: WorkingTreeChangeReport = greetingReport(),
    private val events: MutableList<String> = mutableListOf(),
    private val failure: RuntimeException? = null,
) : WorkingTreeChanges {
    var baselineCalls = 0

    var readCalls = 0

    override fun baseline(): WorkingTreeBaseline {
        baselineCalls++
        events += "baseline"
        return WorkingTreeBaseline(report.baselineRevision)
    }

    override fun read(baseline: WorkingTreeBaseline): WorkingTreeChangeReport {
        readCalls++
        events += "changes:${baseline.revision}"
        failure?.let { failure -> throw failure }
        return report
    }
}

internal fun greetingReport(
    changes: List<WorkingTreeChange> = listOf(WorkingTreeChange("Greeting.kt", WorkingTreeChangeKind.MODIFIED)),
    baselineRevision: String = FAKE_BASELINE_REVISION,
    patch: String = FAKE_PATCH,
    patchTruncated: Boolean = false,
): WorkingTreeChangeReport = WorkingTreeChangeReport(
    baselineRevision = baselineRevision,
    changes = changes,
    patch = patch,
    patchTruncated = patchTruncated,
)
