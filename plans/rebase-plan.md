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
  - Fetch `origin` before integration and use the remote-tracking base when appropriate, retaining local-only stacked-base support. The exact local/remote divergence rule remains open below because always preferring the remote can omit unpushed parent commits.
  - Add compact Rebase and Merge buttons when a base is inferred, while retaining the same actions in the overflow/right-click menu.
  - Use normal `git merge --autostash <base-ref>` behavior so Git can fast-forward when possible and create a merge commit only when histories have diverged; do not force `--no-ff`.
  - Give merge conflicts the same Abort or Leave as is recovery choices as rebase conflicts, with operation-specific wording.
  - Preserve `--autostash` behavior for Rebase and Merge, surfacing autostash or restoration failures rather than rejecting dirty worktrees.
- All implementation PRs must pass `./gradlew clean build` from the repository root.

## Open Questions

1. **Local and remote parent have different commits:** If local `feature/base` has unpushed commits while `origin/feature/base` also exists, which ref should integration use? Always preferring the remote, as initially proposed, can rebase a stacked child away from commits on its actual local parent.
   - Recommendation: select the ref by ancestry after fetching: use whichever of local `feature/base` or `origin/feature/base` contains the other; if they have diverged, stop with an actionable error telling the user to reconcile the parent first. This includes both local and fetched remote base commits when there is a single coherent line and avoids silently choosing one side of a divergence.
   - Answer: awaiting confirmation.

2. **Fetch failure versus local fallback:** If `origin` exists but fetching it fails because the user is offline, authentication fails, or the server is unavailable, should integration proceed using the local parent?
   - Recommendation: fail the action and leave the worktree unchanged when a configured `origin` cannot be fetched; fall back to the local parent only when `origin` is not configured or the fetched remote has no matching branch. Otherwise “update from origin” can silently integrate stale data.
   - Answer: awaiting confirmation.

3. **Up-to-date controls:** Should Rebase and Merge remain clickable whenever a parent is inferred, even when `needsRebase` is false?
   - Recommendation: yes. `needsRebase` is based on currently known refs and can be stale until the action fetches; Git can safely report an up-to-date/no-op result. This also matches the existing Rebase menu behavior.
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
