package com.github.karlsabo.devlake.enghub.state

import com.github.karlsabo.devlake.enghub.LocalRepositoryConfig
import com.github.karlsabo.git.Worktree
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
            uiStates,
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

    @Test
    fun mapsDetachedWorktreeDisplayBranchAndBaseCommitHash() {
        val uiStates = listOf(
            Worktree(path = "/repo-detached", branch = "", commitHash = "abc123"),
            Worktree(path = "/repo-main", branch = "main", commitHash = "def456"),
        ).toLocalWorktreeUiStates("/repo")

        val detachedWorktree = uiStates.single { it.path == "/repo-detached" }
        val branchWorktree = uiStates.single { it.path == "/repo-main" }

        assertEquals("(detached)", detachedWorktree.branch)
        assertEquals("abc123", detachedWorktree.baseCommitHash)
        assertNull(branchWorktree.baseCommitHash)
    }

    @Test
    fun mapsInferredWorktreeParentBranches() {
        val uiStates = listOf(
            Worktree(path = "/repo-base", branch = "feature/base-pr", commitHash = "abc123"),
            Worktree(path = "/repo-stacked", branch = "feature/stacked-pr", commitHash = "def456"),
        ).toLocalWorktreeUiStates(
            repositoryRootPath = "/repo",
            parentBranchesByChildBranch = mapOf("feature/stacked-pr" to "feature/base-pr"),
        )

        val repository = LocalRepositoryUiState(name = "repo", path = "/repo", worktrees = uiStates)

        assertNull(repository.worktrees.single { it.branch == "feature/base-pr" }.parentBranch)
        assertEquals(
            "feature/base-pr",
            repository.worktrees.single { it.branch == "feature/stacked-pr" }.parentBranch,
        )
    }

    @Test
    fun omitsParentBranchWhenHierarchyIsNotSupplied() {
        val uiStates = listOf(
            Worktree(path = "/repo", branch = "main", commitHash = "abc123"),
            Worktree(path = "/repo-feature", branch = "feature/x", commitHash = "def456"),
        ).toLocalWorktreeUiStates("/repo")

        assertEquals(listOf(null, null), uiStates.map { it.parentBranch })
    }
}
