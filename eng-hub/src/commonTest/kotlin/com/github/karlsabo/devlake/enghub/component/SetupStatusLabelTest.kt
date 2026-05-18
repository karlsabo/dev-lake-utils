package com.github.karlsabo.devlake.enghub.component

import com.github.karlsabo.git.WorktreeSetupStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class SetupStatusLabelTest {

    @Test
    fun mapsSetupStagesToUserFacingLabels() {
        assertEquals(
            "Waiting for repo...",
            WorktreeSetupStatus.WAITING_FOR_REPOSITORY.setupStatusLabel(),
        )
        assertEquals(
            "Creating worktree...",
            WorktreeSetupStatus.CREATING_OR_REUSING_WORKTREE.setupStatusLabel(),
        )
        assertEquals(
            "Running setup...",
            WorktreeSetupStatus.RUNNING_SETUP_COMMANDS.setupStatusLabel(),
        )
    }

    @Test
    fun setupActionLabelFallsBackWhenNoSetupIsActive() {
        assertEquals(
            "Setup",
            setupActionLabel(defaultLabel = "Setup", setupStatus = null),
        )
    }
}
