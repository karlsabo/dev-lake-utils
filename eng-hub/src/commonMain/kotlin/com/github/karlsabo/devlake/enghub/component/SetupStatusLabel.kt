package com.github.karlsabo.devlake.enghub.component

import com.github.karlsabo.git.WorktreeSetupStatus

internal fun WorktreeSetupStatus.setupStatusLabel(): String = when (this) {
    WorktreeSetupStatus.WAITING_FOR_REPOSITORY -> "Waiting for repo..."
    WorktreeSetupStatus.CREATING_OR_REUSING_WORKTREE -> "Creating worktree..."
    WorktreeSetupStatus.RUNNING_SETUP_COMMANDS -> "Running setup..."
}

internal fun setupActionLabel(defaultLabel: String, setupStatus: WorktreeSetupStatus?): String = setupStatus?.setupStatusLabel() ?: defaultLabel
