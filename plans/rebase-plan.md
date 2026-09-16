# Worktree base integration plan

**Goal**: Let a user update a worktree from its automatically inferred base branch by rebasing onto that base or merging that base into the worktree.

## Context

- The original request is the repository TODO, “Need buttons on worktrees to rebase or merge in origin,” in `README.md`.
- A stacked worktree must use its inferred parent branch as its base. For example, if `feature/child` is stacked on `feature/base`, both Rebase and Merge should act on `feature/base`, not always on the repository default branch.
- Parent inference already exists in `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitWorktreeService.kt`. `GitWorktreeParentInferer` finds the nearest visible ancestor branch, with the remote default branch used as an inference candidate.
- The inferred parent and whether it has commits absent from the child are mapped into `LocalWorktreeUiState.parentBranch` and `LocalWorktreeUiState.needsRebase` by `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/LocalWorktreeStateMappers.kt`.
- Rebase support is already substantially implemented:
  - `GitWorktreeRebaseApi` and `GitWorktreeRebaser` run `git rebase --autostash <parentBranch>` in the selected worktree (`utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitWorktreeApi.kt`, `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitWorktreeService.kt`, and `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitCommandService.kt`).
  - `LocalWorktreeRebaseController` tracks progress, refreshes the repository, reports failures, and supports aborting or leaving a conflicted rebase in place (`eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/LocalWorktreeRebaseController.kt`).
  - “Rebase onto parent” is currently available in the overflow/context menu when `parentBranch` is known (`eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeRows.kt` and `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeActionMenu.kt`).
- There is no corresponding merge command/service/controller/UI action. Compact row shortcuts currently exist only for Open and Archive in `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeShortcuts.kt` and are rendered by `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeWorktreeRows.kt`.
- Confirmed decisions:
  - Fetch `origin` before integration and select between the local and remote-tracking parent by ancestry: use whichever contains the other; if they have diverged, stop and tell the user to reconcile the parent first. This avoids dropping either fetched or unpushed parent commits.
  - If a configured `origin` cannot be fetched, fail the action and leave the worktree unchanged. Fall back to the local parent only when `origin` is not configured or the successfully fetched remote has no matching branch.
  - Add compact Rebase and Merge buttons when a base is inferred, while retaining the same actions in the overflow/right-click menu.
  - Keep Rebase and Merge clickable whenever a parent is inferred, even when `needsRebase` is false, because that value can be stale before fetch and Git safely handles no-op integration.
  - Use normal `git merge --autostash <base-ref>` behavior so Git can fast-forward when possible and create a merge commit only when histories have diverged; do not force `--no-ff`.
  - Give merge conflicts the same Abort or Leave as is recovery choices as rebase conflicts, with operation-specific wording.
  - Preserve `--autostash` behavior for Rebase and Merge, surfacing autostash or restoration failures rather than rejecting dirty worktrees.
- All implementation PRs must pass `./gradlew clean build` from the repository root.

## Acceptance tests

Each agreed scenario is one observable behavior and maps to one story and one PR.

1. **Rebase from a newer fetched parent**
   - Given `feature/child` has inferred parent `feature/base`, and fetched `origin/feature/base` contains local `feature/base`, when the user chooses Rebase, then Eng Hub rebases `feature/child` onto `origin/feature/base`.

2. **Rebase from an unpublished local parent**
   - Given `feature/child` has inferred parent `feature/base`, and local `feature/base` contains fetched `origin/feature/base`, when the user chooses Rebase, then Eng Hub rebases `feature/child` onto local `feature/base`.

3. **Rebase from a local-only parent**
   - Given `feature/child` has inferred parent `feature/base`, and a successful fetch finds no `origin/feature/base`, when the user chooses Rebase, then Eng Hub rebases `feature/child` onto local `feature/base`.

4. **Reject a diverged parent**
   - Given local `feature/base` and fetched `origin/feature/base` each contain commits absent from the other, when the user chooses Rebase, then Eng Hub leaves `feature/child` unchanged and reports that the parent must be reconciled first.

5. **Reject integration after a fetch failure**
   - Given `origin` is configured but cannot be fetched, when the user chooses Rebase, then Eng Hub leaves `feature/child` unchanged and reports the fetch failure.

6. **Merge a newer fetched parent**
   - Given `feature/child` has inferred parent `feature/base`, and fetched `origin/feature/base` contains local `feature/base`, when the user chooses Merge, then Eng Hub normally merges `origin/feature/base` into `feature/child` with autostash enabled.

7. **Merge a local-only parent**
   - Given `feature/child` has inferred parent `feature/base`, and no matching remote parent exists, when the user chooses Merge, then Eng Hub normally merges local `feature/base` into `feature/child` with autostash enabled.

8. **Abort a merge conflict**
   - Given Merge of `feature/base` into `feature/child` stops with conflicts, when the user chooses Abort, then Eng Hub aborts the merge and refreshes the worktree state.

9. **Leave a merge conflict for manual resolution**
   - Given Merge of `feature/base` into `feature/child` stops with conflicts, when the user chooses Leave as is, then Eng Hub dismisses the conflict prompt without aborting the merge.

10. **Use the compact Rebase control**
    - Given a worktree has an inferred parent and `needsRebase` is false, when its row is rendered, then an enabled compact Rebase control invokes the same action as the overflow/context-menu Rebase item.

11. **Use the compact Merge control**
    - Given a worktree has an inferred parent and `needsRebase` is false, when its row is rendered, then an enabled compact Merge control invokes the same action as the overflow/context-menu Merge item.

## Stories

Implement in order. Each story is one ticket and one PR, and every PR must pass `./gradlew clean build`.

### 1. Rebase from a newer fetched parent

**Acceptance criteria:** Given `feature/child` has inferred parent `feature/base`, and fetched `origin/feature/base` contains local `feature/base`, when the user chooses Rebase, then Eng Hub rebases `feature/child` onto `origin/feature/base`.

**Expected edits:** `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitWorktreeApi.kt`; `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitWorktreeService.kt`; `utilities/src/commonTest/kotlin/com/github/karlsabo/git/FakeGitCommandApi.kt`; `utilities/src/commonTest/kotlin/com/github/karlsabo/git/GitWorktreeServiceRebaseTest.kt`; possibly `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModelTestFixtures.kt` if the API signature changes.

**Scope:** Fetch configured `origin`, compare local and remote parent ancestry, and pass the remote-tracking ref to the existing rebase path when it contains the local parent. No Merge behavior or new row controls.

**Notes:** Extend the existing `GitWorktreeRebaser` path rather than duplicating Rebase orchestration. Preserve `git rebase --autostash` and existing progress, refresh, error, and conflict behavior.

### 2. Rebase from an unpublished local parent

**Acceptance criteria:** Given `feature/child` has inferred parent `feature/base`, and local `feature/base` contains fetched `origin/feature/base`, when the user chooses Rebase, then Eng Hub rebases `feature/child` onto local `feature/base`.

**Expected edits:** `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitWorktreeService.kt`; `utilities/src/commonTest/kotlin/com/github/karlsabo/git/GitWorktreeServiceRebaseTest.kt`.

**Scope:** Complete the ancestry-based ref selection for the local-ahead case. Do not update, reset, or check out the parent branch.

**Notes:** The selected ref must contain both fetched remote commits and unpublished local parent commits. Reusing one resolver for Rebase and the later Merge stories keeps the policy in one place.

### 3. Rebase from a local-only parent

**Acceptance criteria:** Given `feature/child` has inferred parent `feature/base`, and a successful fetch finds no `origin/feature/base`, when the user chooses Rebase, then Eng Hub rebases `feature/child` onto local `feature/base`.

**Expected edits:** `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitWorktreeService.kt`; `utilities/src/commonTest/kotlin/com/github/karlsabo/git/GitWorktreeServiceRebaseTest.kt`.

**Scope:** Add local fallback after a successful fetch when no matching remote branch exists, including repositories with no configured `origin`. Fetch failures are not fallback conditions.

**Notes:** Validate the inferred branch before constructing refs. Do not use stale remote-tracking refs as proof that a remote branch still exists.

### 4. Reject a diverged parent

**Acceptance criteria:** Given local `feature/base` and fetched `origin/feature/base` each contain commits absent from the other, when the user chooses Rebase, then Eng Hub leaves `feature/child` unchanged and reports that the parent must be reconciled first.

**Expected edits:** `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitWorktreeService.kt`; `utilities/src/commonTest/kotlin/com/github/karlsabo/git/GitWorktreeServiceRebaseTest.kt`; `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubLocalWorktreeRebaseViewModelTest.kt` if user-visible error propagation lacks coverage.

**Scope:** Detect two-way divergence before invoking Rebase and return an actionable failure. Do not choose a side, mutate the parent, or attempt to reconcile it automatically.

**Notes:** The failure should name both `feature/base` and `origin/feature/base`. Existing action-error reporting in `LocalWorktreeRebaseController.kt` should remain the UI delivery mechanism.

### 5. Reject integration after a fetch failure

**Acceptance criteria:** Given `origin` is configured but cannot be fetched, when the user chooses Rebase, then Eng Hub leaves `feature/child` unchanged and reports the fetch failure.

**Expected edits:** `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitWorktreeService.kt`; `utilities/src/commonTest/kotlin/com/github/karlsabo/git/GitWorktreeServiceRebaseTest.kt`; `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubLocalWorktreeRebaseViewModelTest.kt`.

**Scope:** Distinguish “no configured origin” from “configured origin failed to fetch.” Never invoke Rebase after the latter.

**Notes:** Preserve the underlying Git output in the wrapped failure so authentication, connectivity, and server errors remain diagnosable.

### 6. Merge a newer fetched parent

**Acceptance criteria:** Given `feature/child` has inferred parent `feature/base`, and fetched `origin/feature/base` contains local `feature/base`, when the user chooses Merge, then Eng Hub normally merges `origin/feature/base` into `feature/child` with autostash enabled.

**Expected edits:** `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitCommandApi.kt`; `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitCommandService.kt`; `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitWorktreeApi.kt`; `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitWorktreeService.kt`; `utilities/src/commonTest/kotlin/com/github/karlsabo/git/FakeGitCommandApi.kt`; `utilities/src/commonTest/kotlin/com/github/karlsabo/git/GitCommandServiceTest.kt`; new `utilities/src/commonTest/kotlin/com/github/karlsabo/git/GitWorktreeServiceMergeTest.kt`; new `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/LocalWorktreeMergeController.kt`; `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModel.kt`; `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModelState.kt`; `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeRows.kt`; `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeActionMenu.kt`; `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreePanelState.kt`; `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreePanel.kt`; `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeRepositoryRows.kt`; `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeWorktreeRows.kt`; `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/screen/EngHubScreenState.kt`; new `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubLocalWorktreeMergeViewModelTest.kt`; `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeRowsTest.kt`; `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeWorktreeRowsTest.kt`; `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreePanelTest.kt`.

**Scope:** Add an overflow/context-menu Merge action, normal `git merge --autostash <selected-ref>` semantics, per-worktree “Merging…” progress, action exclusion while integration is active, and repository refresh after success. Conflict recovery is deferred to Stories 8–9.

**Notes:** Reuse the integration-ref selection policy established in Stories 1–5. Do not force `--no-ff`. Keep Rebase and Merge operation state explicit rather than hiding both behind the existing `isRebasing` name.

### 7. Merge a local-only parent

**Acceptance criteria:** Given `feature/child` has inferred parent `feature/base`, and no matching remote parent exists, when the user chooses Merge, then Eng Hub normally merges local `feature/base` into `feature/child` with autostash enabled.

**Expected edits:** `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitWorktreeService.kt`; `utilities/src/commonTest/kotlin/com/github/karlsabo/git/GitWorktreeServiceMergeTest.kt`; `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubLocalWorktreeMergeViewModelTest.kt`.

**Scope:** Apply the established local fallback to Merge. Do not duplicate remote-selection logic in the Merge controller.

**Notes:** Cover at least the successfully fetched origin-without-branch case. The shared resolver’s no-origin behavior is already established by Story 3.

### 8. Abort a merge conflict

**Acceptance criteria:** Given Merge of `feature/base` into `feature/child` stops with conflicts, when the user chooses Abort, then Eng Hub aborts the merge and refreshes the worktree state.

**Expected edits:** `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitCommandApi.kt`; `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitCommandService.kt`; `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitWorktreeApi.kt`; `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitWorktreeService.kt`; `utilities/src/commonTest/kotlin/com/github/karlsabo/git/GitCommandServiceTest.kt`; `utilities/src/commonTest/kotlin/com/github/karlsabo/git/GitWorktreeServiceMergeTest.kt`; `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/LocalWorktreeMergeController.kt`; `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModelState.kt`; `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeDialogs.kt`; `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreePanelState.kt`; `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeRebaseConflictDialogTest.kt` or a new operation-neutral conflict-dialog test; `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubLocalWorktreeMergeViewModelTest.kt`.

**Scope:** Detect an in-progress merge, present operation-specific conflict text, run `git merge --abort`, clear only the matching request, and refresh. Rebase conflict behavior must remain intact.

**Notes:** Prefer generalizing conflict request/dialog models only where that reduces duplicated operation-state logic; retain explicit operation names in commands and user-facing messages.

### 9. Leave a merge conflict for manual resolution

**Acceptance criteria:** Given Merge of `feature/base` into `feature/child` stops with conflicts, when the user chooses Leave as is, then Eng Hub dismisses the conflict prompt without aborting the merge.

**Expected edits:** `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/LocalWorktreeMergeController.kt`; `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModel.kt`; `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeDialogs.kt`; `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreePanelState.kt`; corresponding merge view-model and dialog tests under `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub`.

**Scope:** Dismiss the matching merge-conflict request without running any Git command or refreshing away the in-progress state.

**Notes:** Closing the dialog window should have the same semantics as Leave as is, matching existing Rebase behavior.

### 10. Add the compact Rebase control

**Acceptance criteria:** Given a worktree has an inferred parent and `needsRebase` is false, when its row is rendered, then an enabled compact Rebase control invokes the same action as the overflow/context-menu Rebase item.

**Expected edits:** `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeShortcuts.kt`; `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeWorktreeRows.kt`; `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeRows.kt`; `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeWorktreeRowsTest.kt`; `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeRowsTest.kt`.

**Scope:** Show Rebase only when `parentBranch` is known; keep it enabled regardless of `needsRebase`; route it through the existing Rebase callback; disable it during setup, archive, Rebase, or Merge operations.

**Notes:** Give the control a tooltip and branch-specific accessibility description. Do not remove the menu action.

### 11. Add the compact Merge control

**Acceptance criteria:** Given a worktree has an inferred parent and `needsRebase` is false, when its row is rendered, then an enabled compact Merge control invokes the same action as the overflow/context-menu Merge item.

**Expected edits:** `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeShortcuts.kt`; `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeWorktreeRows.kt`; `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeRows.kt`; `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeWorktreeRowsTest.kt`; `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeRowsTest.kt`.

**Scope:** Show Merge only when `parentBranch` is known; keep it enabled regardless of `needsRebase`; route it through the Merge callback from Story 6; disable it during setup, archive, Rebase, or Merge operations.

**Notes:** Give the control a tooltip and branch-specific accessibility description. Do not remove the menu action.
