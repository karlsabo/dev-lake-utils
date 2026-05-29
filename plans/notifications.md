# Notification Done Reliability Plan

## Goal

Make Eng Hub notification “Done” handling reliably clear the corresponding GitHub notification, while still surfacing later GitHub activity on the same notification thread.

## Context

- GitHub notification thread IDs are currently persisted as a bare ignored set via `NotificationIgnoreStore.listIgnoredThreadIds()` and `SqlDelightNotificationIgnoreStore.listIgnoredThreadIds()` (`utilities/src/commonMain/kotlin/com/github/karlsabo/notifications/NotificationIgnoreStore.kt`, `utilities/src/commonMain/kotlin/com/github/karlsabo/notifications/SqlDelightNotificationIgnoreStore.kt`).
- Eng Hub loads that bare set into `hiddenThreadIds` and filters matching GitHub notifications before processing or enrichment (`eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModel.kt`). This can blanket-hide a GitHub thread if GitHub later reuses the same notification ID after a new commit or comment.
- The persisted schema stores only `thread_id`, `repository_full_name`, `subject_type`, `ignore_reason`, and `ignored_at_epoch_ms` (`utilities/src/commonMain/sqldelight/com/github/karlsabo/notifications/IgnoredNotificationThreads.sq`). It does not store the GitHub notification `updated_at` watermark.
- `Notification` already exposes `updatedAt`, which is the GitHub-provided activity timestamp needed to distinguish “same old thread” from “same thread with new activity” (`utilities/src/commonMain/kotlin/com/github/karlsabo/github/Notification.kt`).
- The GitHub API implementation marks a notification done by `DELETE https://api.github.com/notifications/threads/{threadId}` (`utilities/src/commonMain/kotlin/com/github/karlsabo/github/GitHubRestApi.kt`).
- Manual Done, approve, and submit-review flows call `gitHubApi.markNotificationAsDone(...)` before persisting local DONE state (`eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModel.kt`).
- The unsubscribe flow currently unsubscribes, persists local ignore state, then calls `markNotificationAsDone(...)`; if that last call fails, the app can hide a notification locally while it remains visible in GitHub (`eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModel.kt`).
- Existing behavior is covered in `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubNotificationPersistenceViewModelTest.kt` and notification store/migration tests under `utilities/src/commonTest/kotlin/com/github/karlsabo/notifications/`, `utilities/src/jvmTest/kotlin/com/github/karlsabo/notifications/`, and `utilities/src/macosArm64Test/kotlin/com/github/karlsabo/notifications/`.

## Assumptions

- “Done” means the GitHub mark-done API call must succeed before we persist a local DONE hide for that exact notification snapshot.
- A later GitHub notification with the same `thread_id` but a newer `updated_at` is new user-visible activity and must not be hidden solely because the thread ID was previously marked DONE.
- `DONE` hides are snapshot-aware: a later GitHub notification with a newer `updated_at` should be shown or reprocessed.
- `UNSUBSCRIBED` hides are thread-wide: once unsubscribe succeeds, future activity on the same thread should remain hidden locally to match the user’s GitHub unsubscribe intent.
- Legacy ignored rows with no GitHub notification watermark should be treated as stale for `DONE` records, so they are re-evaluated instead of silently hiding GitHub-visible notifications forever.

## Stories

### 1. Persist the GitHub notification update watermark with ignored threads

**Status:** Done.

**Acceptance criteria:** Given a notification thread is saved as ignored, when the app restarts and reads the ignore store, then the store returns the thread ID with the GitHub notification `updated_at` timestamp that was current when it was ignored.

**Expected edits:**

- `utilities/src/commonMain/kotlin/com/github/karlsabo/notifications/NotificationIgnoreStore.kt`
- `utilities/src/commonMain/kotlin/com/github/karlsabo/notifications/SqlDelightNotificationIgnoreStore.kt`
- `utilities/src/commonMain/sqldelight/com/github/karlsabo/notifications/IgnoredNotificationThreads.sq`
- new SQLDelight migration, likely `utilities/src/commonMain/sqldelight/com/github/karlsabo/notifications/3.sqm`
- `utilities/src/commonTest/kotlin/com/github/karlsabo/notifications/SqlDelightNotificationIgnoreStoreTest.kt`
- migration tests in `utilities/src/jvmTest/kotlin/com/github/karlsabo/notifications/NotificationDatabaseMigrationTest.kt` and `utilities/src/macosArm64Test/kotlin/com/github/karlsabo/notifications/NotificationDatabaseMigrationTest.kt`

**Scope:** Add a nullable `notification_updated_at_epoch_ms` (or equivalent) to persisted ignored-thread rows and expose rows instead of only `Set<String>` where Eng Hub needs reason-aware filtering.

**Notes:** Existing rows will not have a real GitHub `updated_at`. Treat legacy `DONE` rows as stale so GitHub-returned notifications can be re-evaluated rather than silently hidden forever. `UNSUBSCRIBED` rows remain thread-wide hides regardless of watermark.

### 2. Save the current notification snapshot when a user marks a notification Done

**Acceptance criteria:** Given a visible notification `thread-1` with `updatedAt=2026-05-29T10:00:00Z`, when the user clicks Done, then `gitHubApi.markNotificationAsDone("thread-1")` is called once and the local DONE record stores `notification_updated_at=2026-05-29T10:00:00Z` only after the GitHub call succeeds.

**Expected edits:**

- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/state/NotificationUiState.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModel.kt`
- `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubNotificationPersistenceViewModelTest.kt`

**Scope:** Add `updatedAt` to `NotificationUiState` and pass it through all manual Done persistence paths.

**Notes:** Keep the existing failure behavior: if GitHub mark-done fails, restore visibility and do not persist local DONE.

### 3. Show new GitHub activity on a previously done thread

**Acceptance criteria:** Given the local store has `thread-1` marked DONE with `notification_updated_at=2026-05-29T10:00:00Z`, when GitHub later returns `thread-1` with `updatedAt=2026-05-29T10:05:00Z` for an open pull request that is not auto-approvable, then Eng Hub shows the notification instead of filtering it from the UI.

**Expected edits:**

- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModel.kt`
- `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubNotificationPersistenceViewModelTest.kt`

**Scope:** Replace bare `hiddenThreadIds` filtering with reason-aware filtering: hide `DONE` records only when the stored ignored watermark is at least as new as the GitHub notification `updatedAt`; hide `UNSUBSCRIBED` records regardless of `updatedAt`.

**Notes:** This is the likely root cause of “same notification ID with a new GitHub update was blanket ignored.” The fix applies to `DONE`; unsubscribe intentionally remains a permanent local hide for that thread.

### 4. Re-mark automatically handled revived notifications as done in GitHub

**Acceptance criteria:** Given the local store has `thread-2` marked DONE with `notification_updated_at=2026-05-29T10:00:00Z`, when GitHub returns `thread-2` with `updatedAt=2026-05-29T10:05:00Z` for a merged pull request, then Eng Hub calls `gitHubApi.markNotificationAsDone("thread-2")` again, updates the stored watermark to `10:05:00Z`, and does not show the notification.

**Expected edits:**

- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModel.kt`
- `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubNotificationPersistenceViewModelTest.kt`
- possibly `utilities/src/commonMain/kotlin/com/github/karlsabo/github/GitHubNotificationService.kt` if action metadata needs to carry the processed snapshot timestamp

**Scope:** Ensure snapshot-aware filtering happens before automatic cleanup decisions, not before the app can observe newer activity.

**Notes:** This keeps closed/merged PR noise out of the UI while still making the GitHub-side Done call for fresh activity.

### 5. Keep unsubscribed threads hidden across future GitHub activity

**Acceptance criteria:** Given the local store has `thread-3` marked `UNSUBSCRIBED`, when GitHub later returns `thread-3` with a newer `updatedAt`, then Eng Hub continues to hide the notification and does not show it in the UI.

**Expected edits:**

- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModel.kt`
- `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubNotificationPersistenceViewModelTest.kt`

**Scope:** Preserve thread-wide hide behavior for `UNSUBSCRIBED` while making `DONE` snapshot-aware.

**Notes:** This is intentionally different from `DONE`: unsubscribe should behave like GitHub unsubscribe, meaning the user does not want future notifications for that thread.

## Proposed sequence

1. Add the store watermark model and migration.
2. Thread `Notification.updatedAt` into `NotificationUiState` and manual DONE persistence.
3. Replace local filtering with reason-aware filtering: snapshot-aware for `DONE`, thread-wide for `UNSUBSCRIBED`.
4. Add automatic revived-thread cleanup coverage.
5. Add unsubscribe regression coverage so future activity remains hidden for unsubscribed threads.

## Resolved decisions

- `UNSUBSCRIBED` should behave like GitHub unsubscribe: hide all future newer activity on that thread.
- Legacy ignored rows with no notification watermark should be treated as stale for `DONE` so they are reprocessed.
