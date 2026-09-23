# Delayed worktree archive

**Goal:** Let users archive a worktree without an initial confirmation, provide a 60-second undo window in a recycle-bin UI, and make archive progress and recoverability explicit.

## Context

- The request originates in the repository TODO at `README.md:16-17`: archive without prompting, delay removal by roughly 60 seconds, animate the worktree into a recycle bin, allow cancellation, and support recovery when possible.
- Today both the compact archive shortcut and overflow-menu action create a `PendingArchive`, and `WorktreeDialogHost` displays `ArchiveWorktreeDialog` before calling the view model (`eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreePanel.kt`, `WorktreeDialogs.kt`, and `WorktreeArchiveDialogs.kt`).
- `LocalWorktreeArchiveController` currently acquires the per-path mutation lease and calls `gitWorktreeApi.archiveWorktree(...)` immediately. It exposes only a set of paths actively being archived; it has no scheduled/cancelable state (`eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/LocalWorktreeArchiveController.kt` and `EngHubViewModelState.kt`).
- Archive execution removes the Git worktree and any leftover checkout directory, then prunes worktree metadata. It does not delete the branch, so a clean archived worktree is normally recoverable from its existing local branch; origin refs may provide another recovery source (`utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitWorktreeService.kt` and `utilities/src/commonTest/kotlin/com/github/karlsabo/git/GitWorktreeServiceArchiveTest.kt`).
- A dirty worktree currently fails non-force removal and then opens a separate force-archive warning because forcing discards local changes (`eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/LocalWorktreeArchiveController.kt`, `EngHubViewModelCommon.kt`, and `WorktreeArchiveDialogs.kt`). That destructive warning will remain after the delayed ordinary-removal attempt.
- Existing worktree rows already disable all actions and show `Archiving...` while a path is in `archivingLocalWorktreePaths`; the compact shortcut already uses `TooltipArea` (`eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeWorktreeRows.kt`, `WorktreeActionMenu.kt`, and `WorktreeShortcuts.kt`).
- The overall screen owns the global sidebar while `WorktreePanel` owns only the worktree list, so the always-visible recycle bin belongs at the screen boundary (`eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/screen/EngHubScreen.kt` and `EngHubScreenPanes.kt`).
- Compose UI tests and archive-controller tests already cover row actions, dialogs, mutation guarding, dirty failures, and refresh reconciliation (`eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeWorktreeRowsTest.kt`, `WorktreePanelTest.kt`, and `viewmodel/EngHubLocalWorktreeArchiveViewModelTest.kt`).
- Every implementation PR must pass `./gradlew clean build` from the repository root, per `AGENTS.md`.
- Confirmed behavior:
  - The bucket is UI/state only; the checkout remains at its registered path until deletion starts.
  - Dirty worktrees retain an explicit force-removal confirmation after normal removal refuses to discard local changes.
  - Multiple worktrees may be queued concurrently. Opening the bin shows each repository/branch, remaining wait or removal state, and its applicable action.
  - The recycle bin is a global sidebar control above Settings, remains visible on every pane even when empty, and opens to an empty-state message when it has no entries.
  - Pending removals survive application restarts through database persistence. Every queued entry loaded at startup receives a fresh 60-second deadline rather than resuming elapsed wall-clock time.
  - An entry remains in the bin only until cleanup succeeds. Once deletion starts, Undo is disabled, the entry says `Being removed`, and its tooltip says `This worktree is being removed and can no longer be canceled.`
  - A non-dirty removal failure remains persisted in the bin as `Removal failed`, reports the error, and offers Retry and Dismiss.
  - The recycle bin supports cancellation only before deletion starts. After successful deletion, users may recreate the worktree through the existing local/origin branch flow; completed archives are not retained in the bin.
  - Animation is an animated row exit plus a recycle-bin pulse, not a screen-coordinate flight animation.
- The only existing SQLDelight database is notification-specific (`utilities/src/commonMain/sqldelight/com/github/karlsabo/notifications/`, `utilities/src/commonMain/kotlin/com/github/karlsabo/notifications/SqlDelightNotificationIgnoreStore.kt`, and the `NotificationDatabase` configuration in `utilities/utilities.gradle.kts`). Use a dedicated worktree-archive database and store rather than putting archive jobs behind notification-named APIs. This accepts a small amount of driver wiring duplication in exchange for independent schema evolution and a reversible boundary.
- If Eng Hub stops while an entry is `Being removed`, startup keeps it non-cancelable and retries cleanup immediately. It must never restore Undo after deletion may have partially completed.
- `Failed` and `NeedsForceConfirmation` entries also remain persisted across restart with their existing actions; startup must reacquire their mutation guards without resetting either state to `Queued`.

## Acceptance Tests

Each numbered acceptance test maps to one story, ticket, and PR.

1. **Queue an archive without confirmation**
   - Given the non-root worktree `feature/login` is active, when the user selects Archive, then no confirmation dialog opens and `feature/login` appears in the global recycle bin with a 60-second cancellation window while no Git removal command has run.

2. **Undo a queued archive**
   - Given `feature/login` is waiting in the recycle bin, when the user clicks Undo before its deadline, then it returns to the active worktree list and no Git removal command runs.

3. **Execute an expired archive**
   - Given `feature/login` has remained in the recycle bin for 60 seconds, when its deadline expires, then Git removal starts exactly once and the item becomes non-cancelable with the tooltip “This worktree is being removed and can no longer be canceled.”

4. **Remove a completed archive entry**
   - Given Git removal is running for `feature/login`, when worktree cleanup succeeds, then the entry disappears from the recycle bin and its persisted archive record is deleted.

5. **Expose a failed archive**
   - Given Git removal is running for `feature/login`, when cleanup fails for a reason other than local changes, then the recycle bin retains the persisted entry as `Removal failed`, reports the failure, and offers Retry and Dismiss without claiming that deletion was canceled.

6. **Handle a dirty worktree safely**
   - Given dirty worktree `feature/wip` reaches its archive deadline, when ordinary Git removal refuses to discard changes, then the bin marks the item as requiring confirmation and no forced removal occurs until the user explicitly confirms it.

7. **Restore queued archives after restart**
   - Given `feature/login` is persisted as queued when Eng Hub exits, when Eng Hub starts again, then the global recycle bin restores the entry with a new 60-second cancellation window.

8. **Resume interrupted removal safely**
   - Given `feature/login` was persisted as `Being removed` when Eng Hub stopped, when Eng Hub starts again, then the entry remains non-cancelable and cleanup is retried immediately.

9. **Restore a failed removal after restart**
   - Given `feature/login` is persisted as `Removal failed` when Eng Hub exits, when Eng Hub starts again, then the global bin restores its error with Retry and Dismiss available and without offering Undo.

10. **Restore force confirmation after restart**
   - Given dirty `feature/wip` is persisted as requiring force confirmation when Eng Hub exits, when Eng Hub starts again, then the global bin restores that state and still requires explicit confirmation before forced removal.

11. **Animate archive movement**
   - Given `feature/login` is visible in the worktree list, when the user selects Archive, then its row animates out and the global sidebar recycle bin visibly pulses to indicate receipt of the item.

## Sequence and Trade-offs

1. Queue without prompt (tracer bullet through row action, persisted state, and global bin UI).
2. Undo during the delay (delivers the core safety property).
3. Expiry and non-cancelable execution state (connects the queue to the existing archive service).
4. Successful cleanup and persisted-record removal.
5. Generic failure handling.
6. Dirty-worktree handling (preserves the existing destructive-action safeguard).
7. Queued restart restoration.
8. Interrupted-removal restart safety.
9. Failed-removal restart restoration.
10. Force-confirmation restart restoration.
11. Animation polish (kept separate so visual complexity cannot block functional undo).

A dedicated archive database duplicates a small driver-factory seam, but avoids coupling archive schema and migrations to the notification-named database. A fresh 60-second delay on every startup favors safety and predictability over strict wall-clock execution. Post-removal recreation is deliberately excluded: it cannot restore discarded files and is already supported by the existing-branch workflow.

## Stories

### 1. Queue a worktree in the persistent global recycle bin

**Status:** Done

**Acceptance criteria:** Given active non-root worktree `feature/login`, when the user selects Archive from either the compact shortcut or action menu, then no confirmation dialog opens, the active row is hidden, and a global recycle-bin control above Settings lists `feature/login` with 60 seconds remaining while `GitWorktreeApi.archiveWorktree` has not run.

**Expected edits:**

- Add archive persistence contracts and models at `utilities/src/commonMain/kotlin/com/github/karlsabo/worktreearchive/WorktreeArchiveStore.kt`.
- Add SQLDelight storage at `utilities/src/commonMain/sqldelight/com/github/karlsabo/worktreearchive/WorktreeArchiveJobs.sq`, `utilities/src/commonMain/kotlin/com/github/karlsabo/worktreearchive/SqlDelightWorktreeArchiveStore.kt`, and platform driver factories under `utilities/src/jvmMain/kotlin/com/github/karlsabo/worktreearchive/` and `utilities/src/nativeMain/kotlin/com/github/karlsabo/worktreearchive/`.
- Register a dedicated `WorktreeArchiveDatabase` in `utilities/utilities.gradle.kts` and add store tests under `utilities/src/commonTest/kotlin/com/github/karlsabo/worktreearchive/`.
- Inject the store through `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHubComponent.kt` and `viewmodel/EngHubViewModelServices.kt`.
- Replace immediate archive dispatch with persisted `Queued` state in `viewmodel/LocalWorktreeArchiveController.kt`, `EngHubViewModelState.kt`, and `EngHubViewModel.kt`.
- Remove the initial `PendingArchive`/`ArchiveWorktreeDialog` path from `component/WorktreePanel.kt`, `WorktreeDialogs.kt`, and `WorktreeArchiveDialogs.kt` while retaining force confirmation for later use.
- Add the global bin state/actions in `screen/EngHubScreenState.kt` and `screen/EngHubScreen.kt`, with a new UI component at `component/WorktreeArchiveBin.kt`.
- Update `component/WorktreeRows.kt`, `WorktreeRepositoryRows.kt`, and their tests so queued paths are omitted from active rows.

**Scope:** Includes persistent queue creation, multiple queued items, global sidebar placement, empty-bin content, branch/repository identity, and a test-controlled initial 60-second deadline. Excludes Undo, expiry execution, restart loading, failure states, and animation.

**Notes:** Persist repository root, normalized worktree path, display branch, lifecycle state, and state timestamps. Acquire the existing `LocalWorktreeMutationGuard` before accepting a queue request so programmatic mutations cannot race the hidden row. Store writes must complete before the UI claims the item is safely queued; report persistence failure and leave the row active. Use a dedicated database rather than extending `NotificationDatabase`. Relevant current boundaries are `LocalWorktreeArchiveController.kt`, `EngHubScreen.kt`, and `EngHubComponent.kt`.

### 2. Undo a queued worktree archive - Done

**Status:** Done

**Acceptance criteria:** Given `feature/login` is in `Queued` state before its deadline, when the user clicks Undo, then its persisted archive record is removed, its mutation lease is released, it returns to the active worktree list, and no Git archive call occurs.

**Expected edits:** `viewmodel/LocalWorktreeArchiveController.kt`, `EngHubViewModelState.kt`, `EngHubViewModel.kt`, `screen/EngHubScreenState.kt`, `component/WorktreeArchiveBin.kt`, `utilities/src/commonMain/kotlin/com/github/karlsabo/worktreearchive/WorktreeArchiveStore.kt`, its SQLDelight implementation/query file, `viewmodel/EngHubLocalWorktreeArchiveViewModelTest.kt`, and new archive-bin UI tests under `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/component/`.

**Scope:** Undo applies only to `Queued` entries and must work independently for multiple entries. Excludes cancellation after removal starts and post-removal worktree recreation.

**Notes:** Make the state transition conditional so an Undo racing the deadline either wins before `Removing` is persisted or is rejected after it; it must never delete a `Removing` record. Release the mutation lease exactly once.

### 3. Start removal when the 60-second deadline expires

**Acceptance criteria:** Given queued `feature/login` reaches its deadline, when its timer expires, then its persisted state atomically changes to `Removing`, `GitWorktreeApi.archiveWorktree` starts exactly once, Undo is disabled, the bin displays `Being removed`, and hovering the disabled action shows `This worktree is being removed and can no longer be canceled.`

**Expected edits:** `viewmodel/LocalWorktreeArchiveController.kt`, `EngHubViewModelState.kt`, `EngHubViewModel.kt`, `component/WorktreeArchiveBin.kt`, archive-store compare-and-set/update operations in `utilities/src/commonMain/kotlin/com/github/karlsabo/worktreearchive/` and `WorktreeArchiveJobs.sq`, plus `viewmodel/EngHubLocalWorktreeArchiveViewModelTest.kt` and archive-bin Compose tests.

**Scope:** Covers in-process scheduling and the `Queued` to `Removing` boundary. Excludes success cleanup, error classification, force removal, and startup restoration.

**Notes:** Inject clock/delay behavior or a scheduler boundary so tests do not sleep for 60 seconds. Persist `Removing` before invoking Git. Keep the existing refresh/reconciliation guard in `LocalWorktreeArchiveController.kt`; the database transition is the cross-restart exactly-once claim, while the Git operation itself should be treated as retryable rather than transactionally exactly once.

### 4. Clear a successfully removed worktree from the bin

**Acceptance criteria:** Given `feature/login` is `Removing`, when archive and repository refresh complete successfully, then its persisted archive record is deleted, its bin entry disappears, and its mutation lease is released.

**Expected edits:** `viewmodel/LocalWorktreeArchiveController.kt`, archive-store delete operations under `utilities/src/commonMain/kotlin/com/github/karlsabo/worktreearchive/`, `component/WorktreeArchiveBin.kt`, and `viewmodel/EngHubLocalWorktreeArchiveViewModelTest.kt`.

**Scope:** Success path only. Excludes failures and completed-history/recovery UI.

**Notes:** Delete the record only after the current archive service and repository-state reconciliation have established successful cleanup. Keep no completed history. Existing local/origin branches remain available through the separate existing-branch creation flow.

### 5. Retain and act on a failed worktree removal

**Acceptance criteria:** Given `feature/login` is `Removing`, when removal fails for a reason other than dirty files, then its persisted state becomes `Failed`, the bin displays `Removal failed` and the error, Retry starts another non-cancelable removal attempt, and Dismiss removes the failed record without claiming the worktree was restored.

**Expected edits:** `viewmodel/LocalWorktreeArchiveController.kt`, `EngHubViewModelState.kt`, `EngHubViewModel.kt`, `screen/EngHubScreenState.kt`, `component/WorktreeArchiveBin.kt`, archive-store state/error fields and queries under `utilities/src/commonMain/`, and archive controller/UI tests.

**Scope:** Generic failures, Retry, and Dismiss. Dirty-worktree classification remains for Story 6. Dismiss forgets the archive job and refreshes repository state; it is not Undo.

**Notes:** Retry must persist `Removing` before invoking Git and remain idempotent under repeated clicks. Dismiss releases the lease and triggers best-effort worktree discovery because the prior attempt may have partially changed Git metadata or disk state. Preserve actionable error text in persistence so it survives restart.

### 6. Require confirmation before force-removing a dirty worktree

**Acceptance criteria:** Given dirty `feature/wip` reaches its deadline, when ordinary removal refuses to discard local changes, then its persisted state becomes `NeedsForceConfirmation`, the bin identifies that confirmation is required, and no forced Git archive call occurs until the user confirms.

**Expected edits:** `viewmodel/LocalWorktreeArchiveController.kt`, `EngHubViewModelState.kt`, `EngHubViewModel.kt`, `screen/EngHubScreenState.kt`, `component/WorktreeArchiveBin.kt`, `component/WorktreeDialogs.kt`, `component/WorktreeArchiveDialogs.kt`, archive-store lifecycle values/queries, and dirty archive tests in `viewmodel/EngHubLocalWorktreeArchiveViewModelTest.kt` and component tests.

**Scope:** Preserves the current force-confirmation safeguard and supports confirm/dismiss. Confirm transitions back to non-cancelable `Removing` and calls the existing force archive path. Dismissing confirmation leaves the item in `NeedsForceConfirmation` so the user can reopen it from the bin; it does not restore Undo.

**Notes:** Reuse `Throwable.isDirtyWorktreeArchiveFailure()` from `EngHubViewModelCommon.kt`. Move force-dialog hosting to the global screen boundary because the recycle bin is available outside the Worktrees pane. Clearly state that forced deletion discards uncommitted files and cannot be recovered from local or remote branches.

### 7. Restore queued archives with a fresh delay after restart

**Acceptance criteria:** Given `feature/login` is persisted as `Queued` when Eng Hub exits, when Eng Hub starts again, then the global bin restores it, reacquires its mutation guard, and gives it a new 60-second cancellation window before any Git removal call.

**Expected edits:** startup loading in `viewmodel/EngHubViewModelState.kt`, `EngHubViewModel.kt`, and `LocalWorktreeArchiveController.kt`; archive-store loading/update operations under `utilities/src/commonMain/kotlin/com/github/karlsabo/worktreearchive/`; dependency fixtures in `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModelTestFixtures.kt`; and restart-focused archive controller tests.

**Scope:** Restores only `Queued` records. Each process start resets and persists the deadline to startup time plus 60 seconds. Excludes `Removing`, `Failed`, and `NeedsForceConfirmation` restoration behavior except that they remain persisted for later stories.

**Notes:** Validate that the repository/worktree identity is still usable before acquiring the lease. If a queued record references a worktree already absent from discovery and disk, report it as failed rather than pretending Undo is available. This story intentionally permits repeated restarts to postpone deletion.

### 8. Retry an interrupted removal immediately after restart

**Acceptance criteria:** Given `feature/login` is persisted as `Removing` when Eng Hub stops, when Eng Hub starts again, then the bin restores it as non-cancelable and retries cleanup immediately without displaying Undo.

**Expected edits:** restoration routing in `viewmodel/LocalWorktreeArchiveController.kt`, `EngHubViewModelState.kt`, and `EngHubViewModel.kt`; `component/WorktreeArchiveBin.kt`; archive-store loading tests; and interrupted-removal tests in `viewmodel/EngHubLocalWorktreeArchiveViewModelTest.kt`.

**Scope:** `Removing` records only. A successful retry follows Story 4; a failed retry follows Story 5 or Story 6. Excludes automatic retry backoff beyond this single startup retry.

**Notes:** The underlying `GitWorktreeService.archiveWorktree` already handles a missing `.git` marker by deleting a leftover checkout directory and pruning (`utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitWorktreeService.kt`), which supports retry after partial progress. Never downgrade `Removing` to `Queued` because partial deletion makes Undo misleading.

### 9. Restore a failed removal after restart

**Acceptance criteria:** Given `feature/login` is persisted as `Failed` with error `permission denied` when Eng Hub exits, when Eng Hub starts again, then the global bin displays `Removal failed` and `permission denied`, reacquires the path mutation guard, offers Retry and Dismiss, and does not offer Undo.

**Expected edits:** startup restoration routing in `viewmodel/LocalWorktreeArchiveController.kt`, `EngHubViewModelState.kt`, and `EngHubViewModel.kt`; `component/WorktreeArchiveBin.kt`; dependency fixtures in `viewmodel/EngHubViewModelTestFixtures.kt`; and failed-restoration tests in `viewmodel/EngHubLocalWorktreeArchiveViewModelTest.kt` and archive-bin Compose tests.

**Scope:** `Failed` records only. Retry and Dismiss retain Story 5 semantics. Excludes automatic retry.

**Notes:** Reacquire the mutation lease before exposing Retry because the earlier failure may have partially changed the checkout. If the lease cannot be acquired at startup, retain the record and report the conflict rather than dropping persisted evidence.

### 10. Restore dirty-worktree force confirmation after restart

**Acceptance criteria:** Given dirty `feature/wip` is persisted as `NeedsForceConfirmation` when Eng Hub exits, when Eng Hub starts again, then the global bin restores the confirmation-required state, reacquires the path mutation guard, and does not force removal until the user explicitly confirms.

**Expected edits:** startup restoration routing in `viewmodel/LocalWorktreeArchiveController.kt`, `EngHubViewModelState.kt`, and `EngHubViewModel.kt`; global force-dialog state in `screen/EngHubScreenState.kt`; `component/WorktreeArchiveBin.kt`; and restart tests in `viewmodel/EngHubLocalWorktreeArchiveViewModelTest.kt` and component tests.

**Scope:** `NeedsForceConfirmation` records only. Confirmation and dismissal retain Story 6 semantics.

**Notes:** Startup must not automatically reopen a modal over an unrelated pane. Restore the bin status and let the user reopen confirmation deliberately from the bin.

### 11. Animate a queued row into the global recycle bin

**Acceptance criteria:** Given visible worktree `feature/login`, when its queue request succeeds, then the row animates out and the global sidebar recycle-bin control pulses once to acknowledge the newly queued item.

**Expected edits:** `component/WorktreeWorktreeRows.kt`, `component/WorktreeRepositoryRows.kt`, `component/WorktreeArchiveBin.kt`, `screen/EngHubScreen.kt`, and Compose tests under `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/component/` and `screen/EngHubScreenTest.kt`.

**Scope:** Animated row exit and bin pulse only. Excludes screen-coordinate motion, sound, and animation for startup-restored entries.

**Notes:** Trigger animation only after persistence succeeds. Use stable worktree-path keys and Compose visibility/content animation so scrolling or resizing does not require coordinate tracking. Respect platform reduced-motion behavior where Compose exposes it; otherwise keep transitions short and preserve all semantics without animation.
