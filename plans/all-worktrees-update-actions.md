# Update actions for every branch worktree

**Goal:** Ensure every non-default branch worktree with a discoverable origin default branch can update from that branch and can explicitly rebase or merge it through the overflow menu.

## Context

- The screenshots at `plans/img.png` and `plans/img_1.png` show several `k-repo` worktrees without the compact Update control; their overflow menus also omit Rebase and Merge.
- The UI currently shows the compact Update control only when `LocalWorktreeUiState.canUpdateFromOrigin` is true or `parentBranch` is populated (`eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeShortcuts.kt`).
- The overflow menu similarly includes Rebase and Merge only when `parentBranch` is populated (`eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeRows.kt` and `WorktreeActionMenu.kt`).
- Enrichment marks only the origin default branch as `canUpdateFromOrigin`. Other branches receive a `parentBranch` only when parent inference succeeds and that parent is another visible worktree. Therefore a normal feature branch with no inferred visible parent gets none of the three controls (`eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/LocalWorktreeStateMappers.kt`).
- Origin default-branch discovery already queries the server and falls back to the cached origin tracking HEAD; it is not limited to the repository root's current branch (`utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitWorktreeService.kt`, `GitDefaultBranchRefResolver`). This supports both `main` and `master` without hard-coding either name.
- Existing child update behavior automatically chooses rebase when child-only history has no merge commit and merge otherwise. Explicit rebase and merge operations already fetch and resolve the matching origin branch before integrating it (`utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitWorktreeService.kt`, `GitWorktreeChildUpdater`, `GitWorktreeIntegrationRefResolver`, `GitWorktreeRebaser`, and `GitWorktreeMerger`).
- `parentBranch` currently represents both hierarchy/nesting and the integration target. Those concepts differ for a fallback: a branch can remain top-level in the UI while still integrating from the origin default branch. The implementation should model the action target separately rather than claiming an inferred hierarchy that Git did not discover.
- Preserve the current precedence: an inferred visible stacked-branch parent remains the integration target; the origin default branch is only the fallback for a branch without that target. The origin default worktree itself continues to use its fast-forward-only Update-from-origin action and must not offer rebase/merge onto itself.
- Existing mutation leases, progress state, conflict dialogs, refreshes, and error reporting should be reused rather than adding a second integration path (`eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/LocalWorktreeUpdateController.kt`, `LocalWorktreeRebaseController.kt`, and `LocalWorktreeMergeController.kt`).
- Every implementation PR must pass `./gradlew clean build` from the repository root, per `AGENTS.md`.
- Confirmed behavior:
  - Integration actions apply to named branch worktrees, not detached-HEAD worktrees.
  - Eng Hub uses the discovered origin default branch and does not guess `main` or `master` when discovery fails; this avoids integrating the wrong branch in repositories that use another default.
  - Explicit menu labels name the actual target, such as `Rebase onto main` and `Merge main into worktree`, rather than calling every fallback target a parent.

## Proposed Acceptance Tests

Each acceptance test maps to one ticket and one PR.

1. **Update a parentless feature worktree from the origin default branch**
   - Given repository `widgets` has origin default branch `main` and worktree `feature/login` has no inferred visible parent, when the user clicks its compact Update control, then Eng Hub updates `feature/login` from `main` through the existing automatic rebase-or-merge strategy.

2. **Explicitly rebase a parentless feature worktree onto the origin default branch**
   - Given repository `legacy-api` has origin default branch `master` and worktree `feature/audit` has no inferred visible parent, when the user selects `Rebase onto master` from the three-dot menu, then Eng Hub rebases `feature/audit` onto the resolved `origin/master` integration ref.

3. **Explicitly merge the origin default branch into a parentless feature worktree**
   - Given repository `widgets` has origin default branch `main` and worktree `feature/login` has no inferred visible parent, when the user selects `Merge main into worktree` from the three-dot menu, then Eng Hub merges the resolved `origin/main` integration ref into `feature/login`.

## Sequence and Trade-offs

1. Deliver the compact Update fallback first as the tracer bullet. It provides the fastest path for the common workflow while reusing automatic strategy selection and existing conflict handling.
2. Add explicit Rebase and Merge choices as separate user-visible slices. They share the integration-target model introduced by the first slice but exercise different destructive/history-shaping operations.
3. Keep hierarchy (`parentBranch`) separate from action targeting. This adds one state concept, but avoids falsely nesting unrelated worktrees beneath `main`/`master` and makes the fallback rule explicit and testable.
4. Do not add new Git commands. The existing integration resolver already fetches a same-named origin branch and chooses the safe local/remote ref based on ancestry.

## Stories

### 1. Offer Update for a branch without an inferred parent — Done

**Acceptance criteria:** Given repository `widgets` has origin default branch `main` and worktree `feature/login` has no inferred visible parent, when the user clicks its compact Update control, then Eng Hub updates `feature/login` from `main` through the existing automatic rebase-or-merge strategy.

**Expected edits:**

- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/state/LocalRepositoryUiState.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/LocalWorktreeStateMappers.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeShortcuts.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeRepositoryRows.kt`
- `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/LocalWorktreeStateMappersTest.kt`
- `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeIntegrationShortcutsTest.kt`
- `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeRepositoryRowsTest.kt`

**Scope:**

- In: represent an integration target independently from hierarchy; choose inferred visible parent first and discovered origin default branch second; show and route Update for eligible named branches; preserve default-branch Update-from-origin behavior.
- Out: detached HEAD, guessing default branch names, new strategy selection, explicit menu actions, and changes to conflict handling.

**Notes:**

- Replace the regression expectation `childUpdateShortcutIsHiddenWithoutAnInferredBase` in `WorktreeIntegrationShortcutsTest.kt` with coverage that a supplied default integration target makes Update visible and routes to `onUpdateFromParent`.
- `LocalWorktreeStateMappers.kt` must retain enrichment across lightweight refreshes in `withEnrichmentFrom`; otherwise controls can disappear while polling.
- Do not set `parentBranch = main` solely to expose actions. `WorktreeRowNesting.kt` uses that field to render hierarchy, and the default fallback is not evidence of a parent-child branch relationship.
- The operation should continue through `LocalWorktreeUpdateController.updateLocalWorktreeFromParent` and `GitWorktreeChildUpdater`; no duplicate rebase/merge heuristic belongs in the UI.

### 2. Offer explicit Rebase onto the integration target — Done

**Acceptance criteria:** Given repository `legacy-api` has origin default branch `master` and worktree `feature/audit` has no inferred visible parent, when the user selects `Rebase onto master` from the three-dot menu, then Eng Hub rebases `feature/audit` onto the resolved `origin/master` integration ref.

**Expected edits:**

- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeRows.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeActionMenu.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeRepositoryRows.kt`
- `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeRowsTest.kt`
- `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeIntegrationShortcutsTest.kt`
- `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeRepositoryRowsTest.kt`
- Potentially `utilities/src/commonTest/kotlin/com/github/karlsabo/git/GitWorktreeServiceRebaseTest.kt` if existing service coverage does not explicitly prove the origin ref selected for a default-branch target.

**Scope:**

- In: expose Rebase whenever a non-self integration target exists; label it with the actual target; route the target through the existing rebase controller and origin-aware ref resolver.
- Out: merge-menu behavior, detached HEAD, target-selection dialogs, and rebase conflict UX changes.

**Notes:**

- Replace `manualIntegrationActionsAreHiddenWithoutAnInferredParent` with tests based on whether an integration target exists, not whether hierarchy inference succeeded.
- Keep the action disabled under the same setup/archive/update/rebase/merge states enforced today.
- Verify that the origin default worktree does not receive `Rebase onto main/master`, which would be a self-integration action.

### 3. Offer explicit Merge from the integration target — Done

**Acceptance criteria:** Given repository `widgets` has origin default branch `main` and worktree `feature/login` has no inferred visible parent, when the user selects `Merge main into worktree` from the three-dot menu, then Eng Hub merges the resolved `origin/main` integration ref into `feature/login`.

**Expected edits:**

- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeRows.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeActionMenu.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeRepositoryRows.kt`
- `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeRowsTest.kt`
- `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeIntegrationShortcutsTest.kt`
- `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeRepositoryRowsTest.kt`
- Potentially `utilities/src/commonTest/kotlin/com/github/karlsabo/git/GitWorktreeServiceMergeTest.kt` if existing service coverage does not explicitly prove the origin ref selected for a default-branch target.

**Scope:**

- In: expose Merge whenever a non-self integration target exists; label it with the actual target; route the target through the existing merge controller and origin-aware ref resolver.
- Out: detached HEAD, target-selection dialogs, automatic strategy changes, and merge conflict UX changes.

**Notes:**

- Preserve the existing mutation guard and disabled-state behavior so Update, Rebase, Merge, Archive, and setup cannot mutate one worktree concurrently.
- Confirm both three-dot activation and right-click activation render the same branch-specific action and invoke only Merge.
- Verify that visible inferred stacked parents still win over the default fallback, so `feature/stacked` continues to merge `feature/base`, not `main`.
