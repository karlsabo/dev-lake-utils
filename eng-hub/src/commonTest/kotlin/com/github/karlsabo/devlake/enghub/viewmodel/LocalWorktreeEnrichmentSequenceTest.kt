package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.state.LocalWorktreeUiState
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalWorktreeEnrichmentSequenceTest {
    @Test
    fun unresolvedQueuedParentCannotResurrectTargetAfterAuthoritativeClear() {
        val queued = rows("child").map {
            if (it.branch == "child") it.copy(parentBranch = "base", integrationTargetBranch = "base") else it
        }
        val current = api(null).lookupLocalWorktreeEnrichment(DEV_LAKE_ROOT, queued).mergeInto(queued)
        for (origin in listOf(null, "main")) {
            val pending = api(origin, unresolved = setOf("child"))
                .lookupLocalWorktreeEnrichment(DEV_LAKE_ROOT, queued)
            val child = pending.mergeInto(current).single { it.branch == "child" }

            assertEquals(null, child.parentBranch)
            assertEquals(origin, child.integrationTargetBranch)
            assertEquals(false, child.needsRebase)
        }
    }

    @Test
    fun verifiedParentSurvivesObsoleteQueuedOriginEligibility() {
        val queued = rows("main").map { if (it.branch == "main") it.copy(canUpdateFromOrigin = true) else it }
        val verified = api(null, mapOf("main" to "base"))
            .lookupLocalWorktreeEnrichment(DEV_LAKE_ROOT, queued).mergeInto(queued)
        val pending = api(null, mapOf("main" to "base"), offline = true)
            .lookupLocalWorktreeEnrichment(DEV_LAKE_ROOT, queued)
        val current = verified.map { it.copy(isDirty = true) }

        assertEquals(current, pending.mergeInto(current))
        val main = pending.mergeInto(current).single { it.branch == "main" }
        assertEquals(false, main.canUpdateFromOrigin)
        assertEquals("base", main.parentBranch)
        assertEquals("base", main.integrationTargetBranch)
        assertEquals(true, main.needsRebase)
    }

    @Test
    fun parentAndOriginOutcomeMatrixUsesCurrentState() {
        for (currentParent in listOf(null, "base")) {
            for (eligible in listOf(false, true)) {
                verifyMatrix(currentParent, eligible)
            }
        }
    }

    private fun verifyMatrix(currentParent: String?, eligible: Boolean) {
        val queued = rows("main").map {
            if (it.branch == "main") {
                it.copy(
                    canUpdateFromOrigin = !eligible,
                    parentBranch = "obsolete",
                    integrationTargetBranch = "obsolete",
                )
            } else {
                it
            }
        }
        val current = rows("main").map {
            if (it.branch == "main") {
                it.copy(
                    canUpdateFromOrigin = eligible,
                    parentBranch = currentParent,
                    integrationTargetBranch = currentParent ?: "trunk",
                )
            } else {
                it
            }
        }
        for (parentOutcome in listOf("base", null, "unresolved")) {
            for (originOutcome in listOf("main", "trunk", null, "unresolved")) {
                verifyOutcome(queued, current, parentOutcome, originOutcome)
            }
        }
    }

    private fun verifyOutcome(
        queued: List<LocalWorktreeUiState>,
        current: List<LocalWorktreeUiState>,
        parentOutcome: String?,
        originOutcome: String?,
    ) {
        val previous = current.first()
        val lookup = api(
            originOutcome,
            parents = if (parentOutcome == "base") mapOf("main" to "base") else emptyMap(),
            unresolved = if (parentOutcome == "unresolved") setOf("main") else emptySet(),
            offline = originOutcome == "unresolved",
        ).lookupLocalWorktreeEnrichment(DEV_LAKE_ROOT, queued)
        val actual = lookup.mergeInto(current).first()
        val eligible = originOutcome == "main" || (originOutcome == "unresolved" && previous.canUpdateFromOrigin)
        val parent = if (eligible) {
            null
        } else {
            when (parentOutcome) {
                "base" -> "base"
                "unresolved" -> previous.parentBranch
                else -> null
            }
        }
        val context = "${previous.parentBranch}/${previous.canUpdateFromOrigin}: $parentOutcome/$originOutcome"
        assertEquals(eligible, actual.canUpdateFromOrigin, context)
        assertEquals(parent, actual.parentBranch, context)
        assertEquals(expectedTarget(previous, eligible, parent, originOutcome), actual.integrationTargetBranch, context)
        assertEquals(parent != null && parentOutcome == "base", actual.needsRebase, context)
    }

    private fun expectedTarget(
        previous: LocalWorktreeUiState,
        eligible: Boolean,
        parent: String?,
        originOutcome: String?,
    ): String? = when {
        eligible -> null
        parent != null -> parent
        originOutcome == "trunk" -> "trunk"
        originOutcome == "unresolved" && previous.parentBranch == null -> "trunk"
        else -> null
    }

    private fun api(
        origin: String?,
        parents: Map<String, String> = emptyMap(),
        unresolved: Set<String> = emptySet(),
        offline: Boolean = false,
    ): RecordingGitWorktreeApi = RecordingGitWorktreeApi(
        responses = RecordingGitWorktreeApiResponses(
            originDefaultBranchesByRepoPath = mapOf(DEV_LAKE_ROOT to origin),
            originDefaultBranchFailure = if (offline) IllegalStateException("offline") else null,
            parentBranchesByRepoPath = mapOf(DEV_LAKE_ROOT to parents),
            unresolvedParentBranches = unresolved,
            branchNeedsRebaseByCall = parents.map { (child, parent) ->
                BranchNeedsRebaseCall(DEV_LAKE_ROOT, parent, child) to true
            }.toMap(),
        ),
    )

    private fun rows(branch: String): List<LocalWorktreeUiState> = listOf(
        LocalWorktreeUiState(branch = branch, path = "/repo-child"),
        LocalWorktreeUiState(branch = "base", path = "/repo-base"),
    )
}
