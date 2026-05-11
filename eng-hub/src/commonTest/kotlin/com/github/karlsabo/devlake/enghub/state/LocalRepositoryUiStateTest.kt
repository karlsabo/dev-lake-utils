package com.github.karlsabo.devlake.enghub.state

import com.github.karlsabo.devlake.enghub.LocalRepositoryConfig
import com.github.karlsabo.git.Worktree
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalRepositoryUiStateTest {

    @Test
    fun sortsRepositoriesByFolderName() {
        val repositories = listOf(
            LocalRepositoryConfig(path = "/workspace/zephyr-service"),
            LocalRepositoryConfig(path = "/workspace/atlas-tooling"),
            LocalRepositoryConfig(path = "/workspace/orion-platform"),
            LocalRepositoryConfig(path = "/workspace/cedar-worker"),
        )

        val uiStates = repositories.toLocalRepositoryUiStates()

        assertEquals(
            listOf("atlas-tooling", "cedar-worker", "orion-platform", "zephyr-service"),
            uiStates.map { it.name },
        )
        assertEquals(
            listOf(
                "/workspace/atlas-tooling",
                "/workspace/cedar-worker",
                "/workspace/orion-platform",
                "/workspace/zephyr-service",
            ),
            uiStates.map { it.path },
        )
    }

    @Test
    fun derivesFolderNameFromTrailingSlashPath() {
        val uiStates = listOf(LocalRepositoryConfig(path = "/workspace/atlas-tooling/")).toLocalRepositoryUiStates()

        assertEquals(
            listOf(LocalRepositoryUiState(name = "atlas-tooling", path = "/workspace/atlas-tooling/")),
            uiStates
        )
    }

    @Test
    fun mapsWorktreeDirtyStatusAndRootStatus() {
        val uiStates = listOf(
            Worktree(path = "/repo", branch = "main", commitHash = "abc123", isDirty = false),
            Worktree(path = "/repo-feature", branch = "feature/x", commitHash = "def456", isDirty = true),
        ).toLocalWorktreeUiStates("/repo/")

        assertEquals(listOf(false, true), uiStates.map { it.isDirty })
        assertEquals(listOf(true, false), uiStates.map { it.isRoot })
    }
}
