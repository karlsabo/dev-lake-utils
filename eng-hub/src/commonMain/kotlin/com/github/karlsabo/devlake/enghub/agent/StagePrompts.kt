package com.github.karlsabo.devlake.enghub.agent

/** The user prompt that starts the implementor stage for [story]. */
internal fun implementorTaskPrompt(story: String): String = """
    Story:
    ${story.trim()}

    Implement this story in the current working tree.
""".trimIndent()

/**
 * The user prompt that starts the reviewer stage.
 *
 * It carries the story and the implementor's report only; the implementor's conversation stays with
 * its own closed session.
 */
internal fun reviewerTaskPrompt(
    story: String,
    implementation: ImplementationReport,
): String = """
    Story:
    ${story.trim()}

    The implementor reported:
    files: ${implementation.files.joinToString(", ").ifEmpty { "(none)" }}
    what: ${implementation.what}
    why: ${implementation.why}

    Review this implementation independently.
""".trimIndent()
