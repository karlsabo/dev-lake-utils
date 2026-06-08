package com.github.karlsabo.git

import kotlin.test.Test
import kotlin.test.assertEquals

class GitWorktreeServiceHierarchyTest {
    @Test
    fun inferWorktreeParentBranches_selectsNearestAncestorAmongVisibleWorktrees() {
        val fake = FakeGitCommandApi()
        val repoPath = "/repos/dev-lake-utils"
        fake.worktreeListResult = """
            worktree /repos/dev-lake-utils
            HEAD abc123
            branch refs/heads/main

            worktree /repos/dev-lake-utils-feature-base-pr
            HEAD def456
            branch refs/heads/feature/base-pr

            worktree /repos/dev-lake-utils-feature-stacked-pr
            HEAD ghi789
            branch refs/heads/feature/stacked-pr
        """.trimIndent()
        fake.isAncestorAction = { _, ancestorRef, descendantRef ->
            when (ancestorRef to descendantRef) {
                "refs/heads/main" to "refs/heads/feature/base-pr" -> true
                "refs/heads/main" to "refs/heads/feature/stacked-pr" -> true
                "refs/heads/feature/base-pr" to "refs/heads/feature/stacked-pr" -> true
                else -> false
            }
        }
        val service: GitWorktreeApi = GitWorktreeService(fake)

        val parents = service.inferWorktreeParentBranches(repoPath)

        assertEquals(
            mapOf(
                "feature/base-pr" to "main",
                "feature/stacked-pr" to "feature/base-pr",
            ),
            parents,
        )
    }

    @Test
    fun inferWorktreeParentBranches_ancestryFailureLeavesAffectedWorktreeWithoutParent() {
        val fake = FakeGitCommandApi()
        val repoPath = "/repos/dev-lake-utils"
        fake.worktreeListResult = """
            worktree /repos/dev-lake-utils
            HEAD abc123
            branch refs/heads/main

            worktree /repos/dev-lake-utils-feature-base-pr
            HEAD def456
            branch refs/heads/feature/base-pr

            worktree /repos/dev-lake-utils-feature-stacked-pr
            HEAD ghi789
            branch refs/heads/feature/stacked-pr
        """.trimIndent()
        fake.isAncestorAction = { _, ancestorRef, descendantRef ->
            when (ancestorRef to descendantRef) {
                "refs/heads/main" to "refs/heads/feature/base-pr" -> true
                "refs/heads/main" to "refs/heads/feature/stacked-pr" -> throw GitCommandException(
                    command = listOf("git", "merge-base", "--is-ancestor"),
                    exitCode = 128,
                    gitOutput = "fatal: bad revision",
                )

                else -> false
            }
        }
        val service: GitWorktreeApi = GitWorktreeService(fake)

        val parents = service.inferWorktreeParentBranches(repoPath)

        assertEquals(mapOf("feature/base-pr" to "main"), parents)
    }
}
