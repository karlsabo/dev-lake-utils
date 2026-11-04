# Recycle bin completion and force-removal badge

**Goal:** Make the Eng Hub recycle bin reflect completed worktree removals promptly and visibly flag worktrees that require force-removal confirmation.

## Context

- Requested behavior: “The recycle bin needs to auto empty when a worktree has finished being removed. When there is a worktree that needs to be force removed, the recycle icon needs a badge on it so I know to check it.”
- Target is Eng Hub's sidebar recycle bin, implemented in `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeArchiveBinEntry.kt`; its entries come from `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/screen/WorktreeMutationScreenState.kt` via `EngHubScreenBody.kt`.
- Existing intended behavior already removes completed jobs after successful Git removal, repository reconciliation, and persisted deletion: `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/LocalWorktreeArchiveCompletion.kt` and `LocalWorktreeArchiveController.kt`. Tests asserting this include `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubWorktreeArchiveCompletionTest.kt` and `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeArchiveBinTest.kt`. A job can deliberately remain when reconciliation or persistence fails; it is then marked FAILED, not silently discarded. Avoid deleting jobs before safety checks complete.
- Dirty-worktree removal failures are retained as `NEEDS_FORCE_CONFIRMATION` in `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/LocalWorktreeArchiveRemoval.kt`. The entry already exposes `needsForceConfirmation` and a “Review force removal” action in `WorktreeArchiveBinEntry.kt`, but the ♻ icon has no badge.
- Repository rule in `AGENTS.md`: implementation must pass `./gradlew clean build` without suppressing or weakening analysis rules.

## Open Questions

1. **Meaning of auto-empty:** Is the issue that a successfully removed worktree remains listed after removal, or that the open bin menu should close automatically once its last entry is removed? Can you give a specific reproduction (including whether the entry says “Being removed” or “Removal failed”)?
   - Recommendation: Treat this as stale completed entries only; preserve the open empty-state menu and existing failure retention unless the observed behavior indicates otherwise. Existing tests already specify successful removal clears entries.
   - Answer: When a worktree gets deleted, it's not always removed from the recycle notification area, sometimes it stays there forever (until app restart)
2. **Completion boundary:** Should a bin entry disappear only after repository reconciliation and persisted job deletion, or immediately when the Git removal command succeeds?
   - Recommendation: Keep the current safe boundary (reconciliation plus persisted deletion); otherwise failures and restarts could lose actionable records.
   - Answer: I like your recommendation
3. **Badge meaning:** Should the badge indicate only worktrees awaiting explicit force confirmation, or all failures as well? Should it be a dot/exclamation or a count?
   - Recommendation: Show a small high-contrast dot or exclamation only while at least one `NEEDS_FORCE_CONFIRMATION` entry exists; do not count ordinary queued/removing worktrees, and give the icon an accessible description of required attention.
   - Answer: I like your recommendation

## Candidate acceptance tests (pending answers)

1. Given `feature/login` in the bin is being removed, when Git removal, reconciliation, and persisted deletion finish successfully, then `feature/login` disappears from the bin without clicking Dismiss. (Already covered by existing tests; investigate a concrete regression before creating a duplicate implementation story.)
2. Given the bin contains `feature/wip` awaiting force-removal confirmation, when that state becomes visible, then the closed recycle icon shows an attention badge so the user knows to open the bin.

## Candidate stories (to finalize after clarifications)

### 1. Clear any confirmed stale completed worktree from the recycle bin

**Acceptance criteria:** Given `feature/login` is being removed, when its removal completes safely, then its bin entry disappears automatically without manual dismissal.
**Expected edits:** Only after identifying the failure path; likely `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/LocalWorktreeArchiveCompletion.kt` or `LocalWorktreeArchiveController.kt`, plus a regression in `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubWorktreeArchiveCompletionTest.kt` and/or `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeArchiveBinTest.kt`.
**Scope:** Remove successfully completed entries automatically. Keep failed/force-confirmation records, queue undo, and safe reconciliation intact; do not create this PR if existing behavior is already correct and the reported symptom is a different interaction.
**Notes:** Existing `LocalWorktreeArchiveCompletion.complete` calls `store.deleteRemovingJob` then `clearEntry`; the existing UI test `completedEntryDisappearsFromOpenBinWithoutCompletedHistory` expects entries to vanish. Need reproduction to find the actual gap.

### 2. Badge the recycle icon when force-removal review is needed

**Acceptance criteria:** Given `feature/wip` is awaiting force-removal confirmation, when the recycle bin is closed, then its icon visibly indicates that review is required.
**Expected edits:** `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeArchiveBinEntry.kt`; `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeArchiveBinTest.kt`; optionally entry mapping in `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/screen/WorktreeMutationScreenState.kt` if existing `needsForceConfirmation` is insufficient.
**Scope:** Visual badge and accessible icon description based on `needsForceConfirmation`; preserve existing “Review force removal” action and do not auto-force remove.
**Notes:** `LocalWorktreeArchiveRemoval.retainFailure` already publishes `NEEDS_FORCE_CONFIRMATION`; `WorktreeMutationScreenState.toArchiveBinEntry` maps that to `needsForceConfirmation`; `WorktreeArchiveBinEntry.kt` currently renders ♻ with only `Recycle bin (${entries.size})` semantics. Badge should derive from entries rather than introducing duplicate state.
