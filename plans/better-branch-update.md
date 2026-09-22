# Better worktree branch update plan

**Goal**: Replace the separate compact Rebase and Merge controls with one ⬇️ Update control that integrates a worktree’s inferred base without flattening merge topology, while retaining explicit Rebase and Merge choices in the three-dot menu.

## Context

- Eng Hub currently renders three integration shortcuts in each worktree row: ⬇️ Update-from-origin for the origin default branch, plus separate 🔁 Rebase and 🔀 Merge shortcuts for worktrees with an inferred parent (`eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeShortcuts.kt` and `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeWorktreeRows.kt`).
- The agreed UI is one compact ⬇️ Update action for every updatable worktree. The downward arrow communicates that commits are coming from the remote/base into the local worktree.
- For a child worktree, automatic Update will prefer Rebase. It will choose Merge only when the child-only history already contains a merge commit, because ordinary rebase would otherwise flatten that topology. “Child-only history” means commits reachable from the checked-out child but not from the resolved base ref; inherited merge commits already contained by the base must not force Merge.
- Strategy selection must occur after resolving the freshest safe base ref. Both current integration paths use `GitWorktreeIntegrationRefResolver` to fetch and select between the local and `origin` parent, preserving whichever contains the other and rejecting divergence (`utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitWorktreeService.kt`). The automatic path should resolve once, inspect `<resolved-base>..HEAD`, and execute the selected operation against that same ref.
- The origin default branch is not rebased or normally merged. Its ⬇️ Update action retains the existing fetch-and-fast-forward-only behavior exposed by `GitWorktreeBaseUpdateApi.updateWorktreeFromOrigin` and orchestrated by `LocalWorktreeUpdateController` (`utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitWorktreeApi.kt` and `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/LocalWorktreeUpdateController.kt`).
- Explicit “Rebase onto parent” and “Merge parent into worktree” actions remain in the three-dot/right-click menu whenever `LocalWorktreeUiState.parentBranch` is known (`eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeRows.kt` and `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeActionMenu.kt`). They continue to bypass automatic strategy selection.
- Parent inference is already performed by `GitWorktreeParentInferer` and exposed as `LocalWorktreeUiState.parentBranch` through `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/LocalWorktreeStateMappers.kt`.
- Automatic Update runs exactly one selected operation. It must not attempt Merge after a failed Rebase or Rebase after a failed Merge. Existing operation-specific Abort or Leave-as-is conflict recovery remains available.
- A generic “Updating...” row status is sufficient while automatic selection and integration run. If a conflict occurs, the existing exception type identifies whether the user must resolve a Rebase or Merge conflict. This keeps strategy selection in the Git layer instead of duplicating it in UI state.
- Worktree mutations remain serialized by `LocalWorktreeMutationGuard`; Update, explicit Rebase, and explicit Merge must exclude one another.
- The child update behavior is intentionally one atomic story despite having two strategy examples: shipping only one side of the decision could either flatten existing merges or leave ordinary branches without the promised Update action.
- Required validation for every implementation PR is `./gradlew clean build` from the repository root.

## Acceptance tests

1. **Choose the safe child integration strategy**
   - Given `feature/login` has inferred base `main` and no merge commit in `main..feature/login`, while `feature/payments` has inferred base `main` and a merge commit in `main..feature/payments`, when the user clicks ⬇️ Update on each worktree, then Eng Hub rebases `feature/login` onto the resolved `main` ref and merges that resolved ref into `feature/payments`.

2. **Recover from an automatically selected conflict**
   - Given automatic Update selects Rebase or Merge and that operation stops with conflicts, when Eng Hub reports the conflict, then the dialog names the selected operation and its Abort and Leave-as-is actions retain their existing behavior without trying the other integration strategy.

3. **Keep explicit strategy selection**
   - Given `feature/login` has inferred base `main`, when the user opens its three-dot menu, then separate “Rebase onto parent” and “Merge parent into worktree” actions remain available and invoke exactly the selected operation.

4. **Use one compact Update affordance**
   - Given a child worktree has an inferred base, when its row is displayed, then it shows one ⬇️ Update shortcut instead of separate 🔁 Rebase and 🔀 Merge shortcuts, with base-specific tooltip/accessibility text and the existing operation-exclusion behavior.

5. **Update the origin default branch through the same affordance**
   - Given `main` is the origin default branch, when the user clicks its ⬇️ Update shortcut, then Eng Hub performs the existing fetch-and-fast-forward-only update from `origin/main` rather than selecting Rebase or Merge.

## Stories

### 1. Intelligently update a child worktree from its inferred base

**Acceptance criteria:** Given `feature/login` has inferred base `main` and no merge commit in `main..feature/login`, while `feature/payments` has inferred base `main` and a merge commit in `main..feature/payments`, when the user clicks ⬇️ Update on each worktree, then Eng Hub rebases `feature/login` onto the resolved `main` ref and merges that resolved ref into `feature/payments`.

**Expected edits:** `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitCommandApi.kt`; `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitCommandService.kt`; `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitWorktreeApi.kt`; `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitWorktreeService.kt`; `utilities/src/commonTest/kotlin/com/github/karlsabo/git/FakeGitCommandApi.kt`; a focused automatic-update test under `utilities/src/commonTest/kotlin/com/github/karlsabo/git/`; `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/LocalWorktreeUpdateController.kt`; `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModel.kt`; `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubLocalWorktreeUpdateViewModelTest.kt`.

**Scope:** Add an automatic child-update API that resolves the freshest safe base exactly once, checks for merge commits only in `<resolved-base>..HEAD`, defaults to Rebase when none exist, and uses Merge when one or more exist. Execute the selected operation with the existing `--autostash`, fetch-failure, diverged-base, mutation-guard, refresh, and error semantics. Return or encode the selected operation sufficiently for diagnostics and conflict handling. Do not alter the default branch’s fast-forward-only updater.

**Notes:** Reuse `GitWorktreeIntegrationRefResolver`; do not independently resolve in the selector and again in `GitWorktreeRebaser`/`GitWorktreeMerger`. Extract integration-by-resolved-ref methods if necessary so explicit actions retain their current behavior while automatic Update can resolve once. A command equivalent to `git log --merges --format=%H <resolved-base>..HEAD` is sufficient; merge commits inherited from the base must not affect the choice. Unit-test empty history, linear child-only commits, a child-only merge commit, and a merge commit that exists only in the base.

### 2. Preserve operation-specific recovery for automatic Update

**Acceptance criteria:** Given automatic Update selects Rebase or Merge and that operation stops with conflicts, when Eng Hub reports the conflict, then the dialog names the selected operation and its Abort and Leave-as-is actions retain their existing behavior without trying the other integration strategy.

**Expected edits:** `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/LocalWorktreeUpdateController.kt`; `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/WorktreeConflictResolutionRequests.kt`; potentially `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/LocalWorktreeRebaseController.kt` and `LocalWorktreeMergeController.kt` if shared conflict handling is extracted; `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubLocalWorktreeUpdateViewModelTest.kt`; `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeConflictDialogTest.kt`.

**Scope:** Translate `GitRebaseConflictException` and `GitMergeConflictException` from automatic Update into the existing operation-specific conflict request. Keep generic “Updating...” progress while the command runs, then use the selected operation in conflict text and abort dispatch. Leave-as-is must dismiss only the matching request. Never invoke the alternate strategy after any failure.

**Notes:** Prefer shared conflict-enqueue helpers over copying the Rebase and Merge controller implementations. Preserve the current guarantee that the mutation lease is released before the user can initiate conflict recovery.

### 3. Replace compact Rebase and Merge with one child Update shortcut

**Acceptance criteria:** Given a child worktree has an inferred base, when its row is displayed, then it shows one ⬇️ Update shortcut instead of separate 🔁 Rebase and 🔀 Merge shortcuts, with base-specific tooltip/accessibility text and the existing operation-exclusion behavior.

**Expected edits:** `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeShortcuts.kt`; `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeWorktreeRows.kt`; `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeRepositoryRows.kt`; `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreePanelState.kt`; `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/screen/EngHubScreenState.kt`; `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeIntegrationShortcutsTest.kt`; `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeWorktreeRowsTest.kt`.

**Scope:** Route a child row’s compact ⬇️ Update action to the automatic child-update path. Remove only the compact Rebase and Merge controls; retain their callbacks for menu actions. Show one disabled Update control while setup, archive, update, rebase, or merge is active. Use wording such as “Update feature/login from base main” rather than promising a strategy before Git state is refreshed.

**Notes:** Keep `LocalWorktreeRowActions` explicit: one automatic Update callback plus the two manual menu callbacks. Update constrained-row tests to verify that removing a shortcut reduces row pressure and that the remaining controls stay within bounds.

### 4. Retain manual Rebase and Merge in the three-dot menu

**Acceptance criteria:** Given `feature/login` has inferred base `main`, when the user opens its three-dot menu, then separate “Rebase onto parent” and “Merge parent into worktree” actions remain available and invoke exactly the selected operation.

**Expected edits:** `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeRows.kt`; `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeActionMenu.kt`; `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeRowsTest.kt`; `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeIntegrationShortcutsTest.kt` or a focused action-menu test.

**Scope:** Preserve both explicit actions, their enabled-state rules, and their direct routing to existing Rebase/Merge controllers after compact controls are removed. Verify right-click and three-dot menu presentation continue to share the same action list. Do not add automatic Update to the menu unless later requested.

**Notes:** Most production code should remain unchanged; this story primarily protects intentional behavior while the row action wiring changes.

### 5. Use the unified ⬇️ Update shortcut for the origin default branch

**Acceptance criteria:** Given `main` is the origin default branch, when the user clicks its ⬇️ Update shortcut, then Eng Hub performs the existing fetch-and-fast-forward-only update from `origin/main` rather than selecting Rebase or Merge.

**Expected edits:** `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeShortcuts.kt`; `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeRepositoryRows.kt`; `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeIntegrationShortcutsTest.kt`; `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubLocalWorktreeUpdateViewModelTest.kt`; possibly `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreePanelState.kt` if the unified callback carries both branch and optional parent.

**Scope:** Reuse the same visible icon/component as child Update, but route `canUpdateFromOrigin` worktrees to the existing default-branch updater. Preserve fetch validation, checked-out-branch validation, local-ahead rejection, fast-forward-only merge, generic progress, and refresh behavior.

**Notes:** Keep the routing decision based on the already-derived `canUpdateFromOrigin` flag. A default branch must never enter the merge-commit strategy selector, even if its history contains merge commits.
