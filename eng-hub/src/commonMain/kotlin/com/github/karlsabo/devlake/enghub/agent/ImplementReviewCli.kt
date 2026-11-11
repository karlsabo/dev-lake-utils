package com.github.karlsabo.devlake.enghub.agent

const val STORY_PROMPT_PREFIX = "story> "
const val IMPLEMENTOR_PREFIX = "implementor> "
const val REVIEWER_PREFIX = "reviewer> "

/**
 * Reads stories until [EXIT_COMMAND] or end of input and runs a fresh flow for each nonblank one.
 *
 * A flow failure propagates so the launcher fails instead of printing a review that was never produced.
 */
class ImplementReviewCli(
    private val flow: ImplementReviewFlow,
    private val io: TerminalIO,
) {
    suspend fun run() {
        var story = readStory()
        while (story != null) {
            when {
                story == EXIT_COMMAND -> {
                    io.write("\n")
                    return
                }

                story.isNotEmpty() -> respond(story)
            }
            story = readStory()
        }
        io.write("\n")
    }

    private fun readStory(): String? {
        io.write(STORY_PROMPT_PREFIX)
        return io.readLine()?.trim()
    }

    private suspend fun respond(story: String) {
        val result = flow.run(story)
        io.write(renderStoryFlowResult(result))
    }
}

/** Renders the implementor report and the completed review for terminal output. */
internal fun renderStoryFlowResult(result: StoryFlowResult): String = buildString {
    append('\n')
    append(IMPLEMENTOR_PREFIX)
    append("files: ")
    append(result.implementation.files.joinToString(", ").ifEmpty { "(none)" })
    append('\n')
    append(IMPLEMENTOR_PREFIX)
    append("what: ")
    append(result.implementation.what)
    append('\n')
    append(IMPLEMENTOR_PREFIX)
    append("why: ")
    append(result.implementation.why)
    append('\n')
    append(REVIEWER_PREFIX)
    append(result.review.verdict.name.lowercase())
    if (result.review.findings.isEmpty()) {
        append('\n')
        append(REVIEWER_PREFIX)
        append("no findings")
    } else {
        result.review.findings.forEach { finding ->
            append('\n')
            append(REVIEWER_PREFIX)
            append("- ")
            append(finding)
        }
    }
    append('\n')
}
