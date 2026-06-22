# Worktree branching plan

## Goal

Make the Worktree panel show stacked local branches as parent/child rows when a child branch is based on another visible worktree branch.

## Current failing example

- Parent branch: `202605_IAM-1226-app-global-notary-secrets`
- Child branch: `202605-IAM-1227-migrate-app-notary`

Expected UI shape:

```text
202605_IAM-1226-app-global-notary-secrets
  202605-IAM-1227-migrate-app-notary
```

## Repo evidence

- Hierarchy inference is in `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitWorktreeService.kt`.
  - `GitWorktreeParentInferer.inferParentBranches` lists visible worktree branches and chooses the nearest visible ancestor using Git ancestry checks.
- UI mapping is in `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/LocalWorktreeStateMappers.kt`.
  - It calls `inferWorktreeParentBranches` and maps the result into `LocalWorktreeUiState.parentBranch`.
- `LocalWorktreeUiState.parentBranch` is defined and filtered in `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/state/LocalRepositoryUiState.kt`.
- Worktree row nesting is decided in `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeRows.kt`.
  - `hasValidParentBranchNesting` currently rejects chains where a parent also has its own parent.
  - That means a valid stack like `main -> parent -> child` can fall back to a flat list.
- Row indentation is clamped to one child level in `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeWorktreeRows.kt`.
- Existing tests:
  - Simple nesting: `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreePanelTest.kt`
  - Git ancestry inference: `utilities/src/commonTest/kotlin/com/github/karlsabo/git/GitWorktreeServiceHierarchyTest.kt`

## Acceptance tests

### 1. Render chained worktree parentage instead of flattening

Given visible worktrees for:

- `main`
- `202605_IAM-1226-app-global-notary-secrets`
- `202605-IAM-1227-migrate-app-notary`

And Git ancestry maps:

- `202605_IAM-1226-app-global-notary-secrets` under `main`
- `202605-IAM-1227-migrate-app-notary` under `202605_IAM-1226-app-global-notary-secrets`

When the user expands the repository in the Worktree panel,
then `202605-IAM-1227-migrate-app-notary` appears directly under `202605_IAM-1226-app-global-notary-secrets` as its child instead of the panel falling back to a flat list.

## Story / PR plan

### 1. Support valid parent chains in Worktree panel rows

**Acceptance criteria:** Given a visible stack `main -> 202605_IAM-1226-app-global-notary-secrets -> 202605-IAM-1227-migrate-app-notary`, when the repository is expanded, then the child branch renders immediately below the parent branch with child indentation.

**Expected edits:**

- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeRows.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeWorktreeRows.kt` only if deeper indentation should be visibly distinct
- `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreePanelTest.kt`

**Scope:**

- In: allow a worktree row to be both a child and a parent.
- In: render valid parent chains recursively or iteratively in parent-before-child order.
- In: preserve flat fallback for invalid graphs: missing parents, duplicate branch rows, or cycles.
- Out: changing Git ancestry inference.
- Out: deriving hierarchy from branch name patterns.
- Out: fetching GitHub PR base metadata.
- Out: changing rebase behavior.

**Implementation notes:**

- The likely smallest fix is in `visibleWorktreeRows` / `hasValidParentBranchNesting` in `WorktreeRows.kt`.
- Replace the current rule that rejects nested parents with validation that:
  - every non-null `parentBranch` exists exactly once in visible rows,
  - every branch appears exactly once,
  - parent links do not form a cycle.
- Build visible rows by starting at roots (`parentBranch == null`) and walking children in original list order.
- If validation fails, keep returning the original flat order. A conservative flat list is better than showing a wrong stack.

## Verification before implementation

Run this in the affected repository/worktree:

```bash
git merge-base --is-ancestor \
  202605_IAM-1226-app-global-notary-secrets \
  202605-IAM-1227-migrate-app-notary
echo $?
```

- If it exits `0`, Git ancestry proves the parent/child relationship and the UI rendering fix above should address the bug.
- If it does not exit `0`, this is not just a Worktree panel rendering bug. Git ancestry cannot prove the stack, and we need a new source of truth, probably GitHub PR base branch metadata.
