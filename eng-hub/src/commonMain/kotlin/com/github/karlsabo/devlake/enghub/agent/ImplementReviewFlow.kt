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

/** The default number of repair replies allowed after one stage report fails validation. */
const val DEFAULT_STAGE_REPAIR_BUDGET = 1

/** The validated reports from one implement-then-review run. */
data class StoryFlowResult(
    val implementation: ImplementationReport,
    val review: ReviewReport,
)

/**
 * Runs one story through an implementor and a reviewer that cannot inherit its conversation.
 *
 * The reviewer judges changes read from the working tree after the implementor finishes, so a commit cannot hide
 * a change. Only report-validation failures are repaired, by re-asking the same session without replaying the stage.
 *
 * @param sessions opens each stage's conversation.
 * @param workingTreeChanges reads the working tree, not the paths the implementor claims.
 * @param repairBudget repair asks allowed per stage (default one), not model turns.
 */
class ImplementReviewFlow(
    private val sessions: StageSessionFactory,
    private val workingTreeChanges: WorkingTreeChanges,
    private val repairBudget: Int = DEFAULT_STAGE_REPAIR_BUDGET,
) {
    init {
        require(repairBudget >= 0) { "repairBudget must not be negative: $repairBudget" }
    }

    suspend fun run(story: String): StoryFlowResult {
        require(story.isNotBlank()) { "story must not be blank" }
        val baseline = workingTreeChanges.baseline()
        val implementation = implement(story)
        val changes = workingTreeChanges.read(baseline)
        val review = review(story, implementation, changes)
        return StoryFlowResult(implementation, review)
    }

    private suspend fun implement(story: String): ImplementationReport = withStage(StageName.IMPLEMENTOR) { session ->
        askForReport(session, StageName.IMPLEMENTOR, implementorTaskPrompt(story), ::decodeImplementationReport)
    }

    private suspend fun review(
        story: String,
        implementation: ImplementationReport,
        changes: WorkingTreeChangeReport,
    ): ReviewReport = withStage(StageName.REVIEWER) { session ->
        askForReport(
            session,
            StageName.REVIEWER,
            reviewerTaskPrompt(story, implementation, changes),
            ::decodeReviewReport,
        )
    }

    private suspend fun <T> askForReport(
        session: ChatSession,
        stage: StageName,
        prompt: String,
        decode: (String) -> T,
    ): T {
        var nextPrompt = prompt
        var repairsRemaining = repairBudget
        while (true) {
            val response = session.ask(nextPrompt)
            try {
                return decode(response)
            } catch (rejection: InvalidStageReportException) {
                if (repairsRemaining == 0) throw rejection
                repairsRemaining--
                nextPrompt = reportRepairPrompt(stage, prompt, response, rejection.message.orEmpty())
            }
        }
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
