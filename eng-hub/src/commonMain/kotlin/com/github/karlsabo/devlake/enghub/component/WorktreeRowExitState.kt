package com.github.karlsabo.devlake.enghub.component

internal data class WorktreeRowExitState(
    val finishedPaths: Set<String> = emptySet(),
    val onComplete: (String) -> Unit = {},
)
