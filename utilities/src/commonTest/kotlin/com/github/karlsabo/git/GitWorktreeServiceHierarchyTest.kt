package com.github.karlsabo.git

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GitWorktreeServiceHierarchyTest {
    @Test
    fun inferDefaultBranchRef_usesCurrentBranchUpstreamRemoteDefaultWhenAvailable() {
        val fake = FakeGitCommandApi()
        val repoPath = "/repos/dev-lake-utils"
        fake.currentBranchUpstreamRemoteAction = { "upstream" }
        fake.remoteDefaultBranchRefAction = { _, remote ->
            check(remote == "upstream") { "origin fallback should not be used when upstream default is available" }
            "upstream/HEAD"
        }
        val service: GitWorktreeApi = GitWorktreeService(fake)

        val defaultBranchRef = service.inferDefaultBranchRef(repoPath)

        assertEquals("upstream/HEAD", defaultBranchRef)
        assertEquals(
            listOf(
                FakeGitCommandApi.Call("currentBranchUpstreamRemote", listOf(repoPath)),
                FakeGitCommandApi.Call("remoteDefaultBranchRef", listOf(repoPath, "upstream")),
            ),
            fake.calls,
        )
    }

    @Test
    fun inferDefaultBranchRef_fallsBackToOriginHeadWhenUpstreamDefaultIsUnavailable() {
        val fake = FakeGitCommandApi()
        val repoPath = "/repos/dev-lake-utils"
        fake.currentBranchUpstreamRemoteAction = { "upstream" }
        fake.remoteDefaultBranchRefAction = { _, remote ->
            when (remote) {
                "upstream" -> null
                "origin" -> "origin/HEAD"
                else -> error("unexpected remote $remote")
            }
        }
        val service: GitWorktreeApi = GitWorktreeService(fake)

        val defaultBranchRef = service.inferDefaultBranchRef(repoPath)

        assertEquals("origin/HEAD", defaultBranchRef)
        assertEquals(
            listOf(
                FakeGitCommandApi.Call("currentBranchUpstreamRemote", listOf(repoPath)),
                FakeGitCommandApi.Call("remoteDefaultBranchRef", listOf(repoPath, "upstream")),
                FakeGitCommandApi.Call("remoteDefaultBranchRef", listOf(repoPath, "origin")),
            ),
            fake.calls,
        )
    }

    @Test
    fun inferDefaultBranchRef_returnsControlledAbsenceWhenDiscoveryFails() {
        val fake = FakeGitCommandApi()
        val repoPath = "/repos/dev-lake-utils"
        fake.currentBranchUpstreamRemoteAction = {
            throw GitCommandException(
                command = listOf("git", "branch", "--show-current"),
                exitCode = 128,
                gitOutput = "fatal: not a git repository",
            )
        }
        fake.remoteDefaultBranchRefAction = { _, _ ->
            throw GitCommandException(
                command = listOf("git", "rev-parse", "--verify", "--quiet", "refs/remotes/origin/HEAD"),
                exitCode = 1,
                gitOutput = "",
            )
        }
        val service: GitWorktreeApi = GitWorktreeService(fake)

        val defaultBranchRef = service.inferDefaultBranchRef(repoPath)

        assertNull(defaultBranchRef)
    }

    @Test
    fun inferWorktreeParentBranches_logsFetchFailureAndUsesLocalRefs() {
        val fake = FakeGitCommandApi()
        val repoPath = "/repos/dev-lake-utils"
        val fetchFailure = GitCommandException(
            command = listOf("git", "fetch", "origin"),
            exitCode = 128,
            gitOutput = "fatal: could not fetch origin",
        )
        val warnings = mutableListOf<Pair<String, Throwable>>()
        fake.worktreeListResult = """
            worktree /repos/dev-lake-utils
            HEAD abc123
            branch refs/heads/main

            worktree /repos/dev-lake-utils-feature-child
            HEAD def456
            branch refs/heads/feature/child
        """.trimIndent()
        fake.fetchAction = { _, remote, refSpecs ->
            assertEquals("origin", remote)
            assertTrue(refSpecs.isEmpty())
            throw fetchFailure
        }
        fake.remoteDefaultBranchRefAction = { _, remote ->
            when (remote) {
                "origin" -> "origin/HEAD"
                else -> null
            }
        }
        fake.isAncestorAction = { _, ancestorRef, descendantRef ->
            when (ancestorRef to descendantRef) {
                "refs/heads/main" to "refs/heads/feature/child" -> true
                "origin/HEAD" to "refs/heads/main" -> true
                "origin/HEAD" to "refs/heads/feature/child" -> true
                "refs/heads/main" to "origin/HEAD" -> true
                else -> false
            }
        }
        val service: GitWorktreeApi = GitWorktreeService(
            gitCommandApi = fake,
            logWarning = { message, cause -> warnings += message to cause },
        )

        val parents = service.inferWorktreeParentBranches(repoPath)

        assertEquals(mapOf("feature/child" to "main"), parents)
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().first.contains("using local refs"))
        assertSame(fetchFailure, warnings.single().second)
        assertTrue(
            fake.calls.indexOf(FakeGitCommandApi.Call("fetch", listOf(repoPath, "origin"))) <
                fake.calls.indexOf(
                    FakeGitCommandApi.Call(
                        "isAncestor",
                        listOf(repoPath, "refs/heads/main", "refs/heads/feature/child"),
                    ),
                ),
        )
    }

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
    fun inferWorktreeParentBranches_keepsVisibleParentWhenDefaultRefPointsAtSameCommit() {
        val fake = FakeGitCommandApi()
        val repoPath = "/repos/dev-lake-utils"
        fake.worktreeListResult = """
            worktree /repos/dev-lake-utils
            HEAD abc123
            branch refs/heads/main

            worktree /repos/dev-lake-utils-feature-child
            HEAD def456
            branch refs/heads/feature/child
        """.trimIndent()
        fake.remoteDefaultBranchRefAction = { _, remote ->
            when (remote) {
                "origin" -> "origin/HEAD"
                else -> null
            }
        }
        fake.isAncestorAction = { _, ancestorRef, descendantRef ->
            when (ancestorRef to descendantRef) {
                "refs/heads/main" to "refs/heads/feature/child" -> true
                "origin/HEAD" to "refs/heads/feature/child" -> true
                "refs/heads/main" to "origin/HEAD" -> true
                "origin/HEAD" to "refs/heads/main" -> true
                else -> false
            }
        }
        val service: GitWorktreeApi = GitWorktreeService(fake)

        val parents = service.inferWorktreeParentBranches(repoPath)

        assertEquals(mapOf("feature/child" to "main"), parents)
    }

    @Test
    fun inferWorktreeParentBranches_usesFirstVisibleEquivalentAncestorAsParent() {
        val fake = FakeGitCommandApi()
        val repoPath = "/repos/app"
        val childBranch = "202605-IAM-1227-migrate-app-notary"
        val firstParentBranch = "202605_IAM-1226-app-global-notary-secrets"
        val laterEquivalentBranch = "202606-IAM-1229-app-request-notary-migration"
        val equivalentCommit = "a3fba4e9cc3352549e81b04892767aa0c2bfca7c"
        fake.worktreeListResult = """
            worktree /repos/app-202605-IAM-1227-migrate-app-notary
            HEAD d4f7aa1
            branch refs/heads/$childBranch

            worktree /repos/app-202605_IAM-1226-app-global-notary-secrets
            HEAD $equivalentCommit
            branch refs/heads/$firstParentBranch

            worktree /repos/app-202606-IAM-1229-app-request-notary-migration
            HEAD $equivalentCommit
            branch refs/heads/$laterEquivalentBranch
        """.trimIndent()
        fake.isAncestorAction = { _, ancestorRef, descendantRef ->
            when (ancestorRef to descendantRef) {
                "refs/heads/$firstParentBranch" to "refs/heads/$childBranch" -> true
                "refs/heads/$laterEquivalentBranch" to "refs/heads/$childBranch" -> true
                "refs/heads/$firstParentBranch" to "refs/heads/$laterEquivalentBranch" -> true
                "refs/heads/$laterEquivalentBranch" to "refs/heads/$firstParentBranch" -> true
                else -> false
            }
        }
        val service: GitWorktreeApi = GitWorktreeService(fake)

        val parents = service.inferWorktreeParentBranches(repoPath)

        assertEquals(mapOf(childBranch to firstParentBranch), parents)
    }

    @Test
    fun inferWorktreeParentBranches_leavesAmbiguousNearestAncestorWithoutParent() {
        val fake = FakeGitCommandApi()
        val repoPath = "/repos/dev-lake-utils"
        fake.worktreeListResult = """
            worktree /repos/dev-lake-utils
            HEAD abc123
            branch refs/heads/main

            worktree /repos/dev-lake-utils-feature-base-a
            HEAD def456
            branch refs/heads/feature/base-a

            worktree /repos/dev-lake-utils-feature-base-b
            HEAD ghi789
            branch refs/heads/feature/base-b

            worktree /repos/dev-lake-utils-feature-stacked-pr
            HEAD jkl012
            branch refs/heads/feature/stacked-pr
        """.trimIndent()
        fake.isAncestorAction = { _, ancestorRef, descendantRef ->
            when (ancestorRef to descendantRef) {
                "refs/heads/main" to "refs/heads/feature/base-a" -> true
                "refs/heads/main" to "refs/heads/feature/base-b" -> true
                "refs/heads/main" to "refs/heads/feature/stacked-pr" -> true
                "refs/heads/feature/base-a" to "refs/heads/feature/stacked-pr" -> true
                "refs/heads/feature/base-b" to "refs/heads/feature/stacked-pr" -> true
                else -> false
            }
        }
        val service: GitWorktreeApi = GitWorktreeService(fake)

        val parents = service.inferWorktreeParentBranches(repoPath)

        assertEquals(
            mapOf(
                "feature/base-a" to "main",
                "feature/base-b" to "main",
            ),
            parents,
        )
    }

    @Test
    fun inferWorktreeParentBranches_ignoresLaterFailureAfterCandidateIsAlreadyNotNearest() {
        val fake = FakeGitCommandApi()
        val repoPath = "/repos/dev-lake-utils"
        fake.worktreeListResult = """
            worktree /repos/dev-lake-utils
            HEAD abc123
            branch refs/heads/main

            worktree /repos/dev-lake-utils-feature-base-pr
            HEAD def456
            branch refs/heads/feature/base-pr

            worktree /repos/dev-lake-utils-feature-intermediate
            HEAD ghi789
            branch refs/heads/feature/intermediate

            worktree /repos/dev-lake-utils-feature-stacked-pr
            HEAD jkl012
            branch refs/heads/feature/stacked-pr
        """.trimIndent()
        fake.isAncestorAction = { _, ancestorRef, descendantRef ->
            when (ancestorRef to descendantRef) {
                "refs/heads/main" to "refs/heads/feature/stacked-pr" -> true

                "refs/heads/feature/base-pr" to "refs/heads/feature/stacked-pr" -> true

                "refs/heads/feature/intermediate" to "refs/heads/feature/stacked-pr" -> true

                "refs/heads/main" to "refs/heads/feature/base-pr" -> true

                "refs/heads/feature/base-pr" to "refs/heads/feature/intermediate" -> true

                "refs/heads/main" to "refs/heads/feature/intermediate" -> throw GitCommandException(
                    command = listOf("git", "merge-base", "--is-ancestor"),
                    exitCode = 128,
                    gitOutput = "fatal: transient ancestry failure",
                )

                else -> false
            }
        }
        val service: GitWorktreeApi = GitWorktreeService(fake)

        val parents = service.inferWorktreeParentBranches(repoPath)

        assertEquals("feature/intermediate", parents["feature/stacked-pr"])
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
