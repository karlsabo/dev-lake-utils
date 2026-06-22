# Worktree branching plan

## Goal

Make the Worktree panel show stacked local branches as parent/child rows when a child branch is based on another visible worktree branch.

## Current failing example

Affected worktrees:

- Parent worktree: `/Users/karl.sabo/Klaviyo/Repos/app-202605_IAM-1226-app-global-notary-secrets`
- Child worktree: `/Users/karl.sabo/Klaviyo/Repos/app-202605-IAM-1227-migrate-app-notary`
- Parent branch: `202605_IAM-1226-app-global-notary-secrets`
- Child branch: `202605-IAM-1227-migrate-app-notary`

Expected UI shape:

```text
202605_IAM-1226-app-global-notary-secrets
  202605-IAM-1227-migrate-app-notary
```

## Findings from local Git/GitHub checks

Commands were run against `/Users/karl.sabo/Klaviyo/Repos/app-202605_IAM-1226-app-global-notary-secrets`.

- Both target branches are visible local worktrees:
  - `/Users/karl.sabo/Klaviyo/Repos/app-202605_IAM-1226-app-global-notary-secrets` on `202605_IAM-1226-app-global-notary-secrets`
  - `/Users/karl.sabo/Klaviyo/Repos/app-202605-IAM-1227-migrate-app-notary` on `202605-IAM-1227-migrate-app-notary`
- Git ancestry proves the intended parent is an ancestor of the child:

  ```bash
  git merge-base --is-ancestor \
    refs/heads/202605_IAM-1226-app-global-notary-secrets \
    refs/heads/202605-IAM-1227-migrate-app-notary
  # exit 0
  ```

- There is another visible local worktree branch at the same commit as the intended parent:
  - `202606-IAM-1229-app-request-notary-migration`
  - `202605_IAM-1226-app-global-notary-secrets`
  - both point at `a3fba4e9cc3352549e81b04892767aa0c2bfca7c`
- That duplicate branch is also an ancestor of the child:

  ```bash
  git merge-base --is-ancestor \
    refs/heads/202606-IAM-1229-app-request-notary-migration \
    refs/heads/202605-IAM-1227-migrate-app-notary
  # exit 0
  ```

- GitHub PR base metadata does not identify the stack:
  - `gh pr view 202605_IAM-1226-app-global-notary-secrets` => PR `120084`, base `master`, state `MERGED`
  - `gh pr view 202605-IAM-1227-migrate-app-notary` => PR `120254`, base `master`, state `OPEN`
  - `gh pr view 202606-IAM-1229-app-request-notary-migration` => no PR found

Conclusion: this is not primarily a Worktree panel rendering-chain bug. Local ancestry detects the parent/child relationship, but inference has an ambiguous nearest-parent set because two visible branches are equivalent ancestors at the same commit. GitHub PR base metadata is not useful for this case because the child PR base is `master`.

## Repo evidence

- Hierarchy inference is in `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitWorktreeService.kt`.
  - `GitWorktreeParentInferer.inferParentBranches` lists visible worktree branches and chooses the nearest visible ancestor using Git ancestry checks.
  - `dropDefaultRefDuplicates` removes equivalent default-ref candidates, but there is no equivalent de-dupe for visible branches that point at the same commit.
  - `nearestAncestorsOrNull(...).singleOrNull()` can drop the parent when multiple equivalent visible ancestor branches remain.
- UI mapping is in `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/LocalWorktreeStateMappers.kt`.
  - It calls `inferWorktreeParentBranches` and maps the result into `LocalWorktreeUiState.parentBranch`.
- `LocalWorktreeUiState.parentBranch` is defined and filtered in `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/state/LocalRepositoryUiState.kt`.
- Worktree row nesting is decided in `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeRows.kt`.
  - This may still need a follow-up for deeper visible chains, but it does not explain the confirmed local facts above by itself.
- Existing tests:
  - Git ancestry inference: `utilities/src/commonTest/kotlin/com/github/karlsabo/git/GitWorktreeServiceHierarchyTest.kt`
  - Simple UI nesting: `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreePanelTest.kt`

## Acceptance tests

### 1. Show a child under the first equivalent visible parent branch

Given the Worktree panel shows visible worktrees for:

- `202605-IAM-1227-migrate-app-notary`
- `202605_IAM-1226-app-global-notary-secrets`
- `202606-IAM-1229-app-request-notary-migration`

And:

- `202605_IAM-1226-app-global-notary-secrets` and `202606-IAM-1229-app-request-notary-migration` point at commit `a3fba4e9cc3352549e81b04892767aa0c2bfca7c`
- both branches are ancestors of `202605-IAM-1227-migrate-app-notary`
- `202605_IAM-1226-app-global-notary-secrets` appears before `202606-IAM-1229-app-request-notary-migration` in the visible worktree list

When the repository is expanded,
then `202605-IAM-1227-migrate-app-notary` appears nested under `202605_IAM-1226-app-global-notary-secrets` instead of being left flat.

### 2. Render a three-branch worktree chain

Given visible worktrees for a valid chain `branch-a -> branch-b -> branch-c`,
when the user expands the repository in the Worktree panel,
then the rows render in parent-before-child order with depths `branch-a: 0`, `branch-b: 1`, and `branch-c: 2`.

## Story / PR plan

### 1. Show a child under the first equivalent visible parent branch

**Acceptance criteria:** Given the Worktree panel shows the confirmed app worktree shape where `202605_IAM-1226-app-global-notary-secrets` and `202606-IAM-1229-app-request-notary-migration` point at the same commit and are both ancestors of `202605-IAM-1227-migrate-app-notary`, when the repository is expanded, then `202605-IAM-1227-migrate-app-notary` appears nested under `202605_IAM-1226-app-global-notary-secrets`.

**Expected edits:**

- `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitWorktreeService.kt`
- `utilities/src/commonTest/kotlin/com/github/karlsabo/git/GitWorktreeServiceHierarchyTest.kt`

**Scope:**

- In: treat visible ancestor candidates at the same commit as equivalent for nearest-parent selection.
- In: choose a deterministic representative by preserving the existing visible worktree list order.
- In: keep true ambiguity flat when candidates are unrelated different commits at the same nearest distance.
- Out: branch-name pattern inference.
- Out: GitHub PR base metadata; it does not help this confirmed case.
- Out: Worktree panel rendering changes.

**Notes:**

- The UI already consumes `LocalWorktreeUiState.parentBranch` via `visibleWorktreeRows`, so this slice can be proven by fixing parent inference and its tests.
- The current code already handles equivalent default refs with `dropDefaultRefDuplicates`; this story extends the same idea to visible branch candidates.
- Choosing a representative among same-commit branches is a policy decision. Worktree-list order is simple and deterministic, but it is not semantic truth. If that trade-off is not acceptable, we need persisted parent metadata instead.

### 2. Render a three-branch worktree chain

**Acceptance criteria:** Given a visible stack `branch-a -> branch-b -> branch-c`, when the repository is expanded, then the rows render in parent-before-child order with depths `branch-a: 0`, `branch-b: 1`, and `branch-c: 2`.

**Expected edits:**

- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeRows.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeWorktreeRows.kt`
- `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreePanelTest.kt`

**Scope:**

- In: allow a worktree row to be both a child and a parent for one valid three-branch chain.
- In: render the valid chain in parent-before-child order.
- In: make depth `2` visibly distinct by removing or raising the current row indentation cap.
- Out: invalid graph behavior beyond keeping existing tests green.
- Out: Git ancestry inference changes.
- Out: branch-name pattern inference.
- Out: GitHub PR base metadata.
- Out: rebase behavior.

**Notes:**

- This story is a follow-up unless we find the UI still flattens after Story 1.
- The likely smallest fix is in `visibleWorktreeRows` / `hasValidParentBranchNesting` in `WorktreeRows.kt` plus the indentation cap in `WorktreeWorktreeRows.kt`.
- Missing-parent fallback already has coverage in `WorktreePanelTest.kt`; duplicate branch rows and cycles are deferred edge-case stories if they become needed.

## Decisions

1. Preserving visible worktree list order is an acceptable tie-breaker when two visible ancestor branches point at the same commit.
2. Persisted parent metadata is not needed for this slice because the visible-order tie-breaker is acceptable.
3. The UI should behave as a tree with arbitrary nesting depth. If deep stacks need it later, add horizontal scrolling; expected stack depth is low.

## Open questions

None.
