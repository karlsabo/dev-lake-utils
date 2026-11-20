package com.github.karlsabo.devlake.enghub.screen

import com.github.karlsabo.devlake.enghub.component.WorktreeArchiveFlightState
import com.github.karlsabo.devlake.enghub.component.WorktreeRowExitState

/** Worktree row animation state, kept together so pane entry points stay small. */
internal data class WorktreePaneMotion(
    val exitState: WorktreeRowExitState = WorktreeRowExitState(),
    val archiveFlight: WorktreeArchiveFlightState = WorktreeArchiveFlightState(),
)
