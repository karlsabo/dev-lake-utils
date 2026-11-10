package com.github.karlsabo.devlake.enghub.agent

/** The two model conversations in one implement-then-review run. */
enum class StageName(
    val id: String,
) {
    IMPLEMENTOR("implementor"),
    REVIEWER("reviewer"),
}

/**
 * Opens an isolated conversation for [stage]; one stage must not expose another's messages or tools.
 */
fun interface StageSessionFactory {
    suspend fun open(stage: StageName): ChatSession
}

/** The validated reports from one implement-then-review run. */
data class StoryFlowResult(
    val implementation: ImplementationReport,
    val review: ReviewReport,
)

/**
 * Runs one story through a tool-capable implementor and then a fresh reviewer.
 *
 * The implementor session is closed before the reviewer opens, so the reviewer cannot inherit its
 * conversation. The reviewer sees changes [workingTreeChanges] read from the working tree, not the paths the
 * implementor claims; the baseline is recorded before the implementor starts, so a commit cannot hide a
 * change, and a failed change read fails the run.
 */
class ImplementReviewFlow(
    private val sessions: StageSessionFactory,
    private val workingTreeChanges: WorkingTreeChanges,
) {
    suspend fun run(story: String): StoryFlowResult {
        require(story.isNotBlank()) { "story must not be blank" }
        val baseline = workingTreeChanges.baseline()
        val implementation = implement(story)
        val changes = workingTreeChanges.read(baseline)
        val review = review(story, implementation, changes)
        return StoryFlowResult(implementation, review)
    }

    private suspend fun implement(story: String): ImplementationReport = withStage(StageName.IMPLEMENTOR) { session ->
        decodeImplementationReport(session.ask(implementorTaskPrompt(story)))
    }

    private suspend fun review(
        story: String,
        implementation: ImplementationReport,
        changes: WorkingTreeChangeReport,
    ): ReviewReport = withStage(StageName.REVIEWER) { session ->
        decodeReviewReport(session.ask(reviewerTaskPrompt(story, implementation, changes)))
    }

    private suspend fun <T> withStage(
        stage: StageName,
        block: suspend (ChatSession) -> T,
    ): T {
        val session = sessions.open(stage)
        val result = runCatching { block(session) }
        val closeFailure = runCatching { session.close() }.exceptionOrNull()
        return result.fold(
            onSuccess = { value ->
                closeFailure?.let { failure -> throw failure }
                value
            },
            onFailure = { failure ->
                closeFailure?.let(failure::addSuppressed)
                throw failure
            },
        )
    }
}
