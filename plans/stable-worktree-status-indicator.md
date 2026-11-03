# Stable worktree status indicator

**Goal:** Keep worktree rows stationary while dirty status is checked on initial display and during automatic refresh.

## Context

- Decision: replace the per-worktree `Checking status…` label with a neutral indicator in the same fixed-width slot as the clean/dirty emoji. Show that indicator instead of the last known clean/dirty emoji during a refresh. This preserves visible progress without shifting row contents.
- The current unknown-dirty-state label is `Checking status…`; it replaces the much narrower 🟡/🟢 dirty indicator in `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeWorktreeRows.kt` (`WorktreeDirtyIndicator`). The label and indicator share the start of a row, so changing their width shifts the branch and following content.
- Unknown dirty state is represented by `LocalWorktreeUiState.isDirty == null` in `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/state/LocalRepositoryUiState.kt`; the UI currently keeps status-dependent actions disabled until the check completes (`eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeRows.kt`). Keep that safety behavior unchanged.
- On automatic refresh, `LocalRepositoryRefreshTracker.publishDiscovered` in `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/LocalRepositoryController.kt` publishes basic worktree rows and resets dirty status to unknown while retaining enrichment from existing rows via `withEnrichmentFrom` in `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/LocalWorktreeStateMappers.kt`. Both initial discovery and automatic refresh can therefore use the same unknown → known indicator transition.
- Existing Compose coverage for the unknown and known states is in `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeWorktreeRowsTest.kt`.
- After implementation, repository instructions require `./gradlew clean build` from the repository root.

## Acceptance test

- Given an expanded repository with a visible clean `feature/login` worktree, when an automatic refresh checks its dirty status, then a neutral checking indicator replaces the green emoji in the same fixed-width slot without moving the branch label. Initial display uses that same slot and indicator.

## Stories (one PR each)

### 1. Keep worktree branch text stationary during status checks

**Acceptance criteria:** Given an expanded repository with `feature/login` visible, when its dirty status is checked, then a neutral checking indicator replaces the clean/dirty emoji in the same fixed-width slot without moving the branch label. After the result arrives, the appropriate clean or dirty emoji returns in that slot.
**Expected edits:** `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeWorktreeRows.kt`; `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeWorktreeRowsTest.kt`.
**Scope:** Cover initial discovery and automatic refresh using the existing `isDirty == null` transition; keep clean/dirty semantics, accessible “Checking status” description, and status-dependent action availability intact. Explicit worktree update progress (`Updating...`) and status-fetch scheduling are out of scope.
**Notes:** Use a neutral emoji or compact indicator in an explicitly fixed-width status slot; don't reserve the width of the old label. Verify the branch's measured horizontal position before/during/after the transition in Compose UI tests, plus unknown-status accessibility semantics. Refresh currently publishes basic rows in `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/LocalRepositoryController.kt`; the same indicator should handle this without new refresh state.
