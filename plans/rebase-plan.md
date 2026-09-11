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
- All implementation PRs must pass `./gradlew clean build` from the repository root.

## Open Questions

1. **Meaning of origin:** Should each action first fetch `origin`, then integrate the remote-tracking version of the inferred base (for example `origin/feature/base`), or should it integrate the existing local inferred base branch as the current Rebase action does?
   - Recommendation: fetch `origin` and prefer `origin/<inferred-base>` when it exists, falling back to the local inferred base only when no matching remote branch exists. This makes “update from base” include changes already pushed by collaborators while retaining support for local-only stacked bases.
   - Answer: awaiting confirmation.

2. **Button placement:** Does “buttons on worktrees” require always-visible compact Rebase and Merge row buttons, or are entries in the existing overflow/right-click menu sufficient?
   - Recommendation: add compact Rebase and Merge buttons only when a base is inferred, and retain the same actions in the overflow menu. The direct buttons satisfy the request while the menu preserves discoverability and keyboard/context-menu use.
   - Answer: awaiting confirmation.

3. **Merge strategy:** Should Merge create the normal Git merge result chosen by `git merge`, or force a merge commit with `--no-ff`?
   - Recommendation: use normal `git merge --autostash <base-ref>` behavior so Git can fast-forward when possible and creates a merge commit only when histories have diverged.
   - Answer: awaiting confirmation.

4. **Conflict handling:** On a merge conflict, should Eng Hub offer the same “Abort” or “Leave as is” dialog currently used for rebase conflicts?
   - Recommendation: yes. Both actions can leave the worktree in an in-progress Git operation, so they should have equivalent recovery UX while naming the actual operation.
   - Answer: awaiting confirmation.

5. **Dirty worktrees:** Is the existing `--autostash` behavior desired for both Rebase and Merge?
   - Recommendation: yes, use autostash for both and surface restoration failures rather than rejecting every dirty worktree. This matches existing Rebase behavior.
   - Answer: awaiting confirmation.

## Preliminary acceptance tests

These are provisional until the open questions are answered.

1. **Rebase a stacked worktree from its inferred base**
   - Given visible worktrees `main`, `feature/base`, and `feature/child`, and Git ancestry identifies `feature/base` as the nearest parent of `feature/child`, when the user chooses Rebase on the `feature/child` row, then Eng Hub rebases `feature/child` onto the selected ref for `feature/base`, shows per-worktree progress, and refreshes the repository state.

2. **Merge a stacked worktree’s inferred base into it**
   - Given visible worktrees `main`, `feature/base`, and `feature/child`, and Git ancestry identifies `feature/base` as the nearest parent of `feature/child`, when the user chooses Merge on the `feature/child` row, then Eng Hub merges the selected ref for `feature/base` into `feature/child`, shows per-worktree progress, and refreshes the repository state.

3. **Recover from an integration conflict**
   - Given Rebase or Merge of `feature/base` into `feature/child` stops with conflicts, when Eng Hub reports the conflict, then the user can abort that operation or leave the worktree in its conflicted state for manual resolution.

## Stories

Story decomposition will be finalized after the acceptance tests and open questions are agreed. The existing Rebase path should be extended rather than rebuilt; the likely new slices are remote-aware base selection, Merge behavior, and compact row controls/conflict UX.
