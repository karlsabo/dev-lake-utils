# Stable oldest-first notification ordering

**Goal:** Keep Eng Hub's Notifications pane in a deterministic oldest-to-newest order across refreshes.

## Context

- The Notifications pane renders the supplied list without reordering it: `LazyColumn.items(notifications, key = { it.notificationThreadId })` in `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/NotificationPanel.kt`.
- Polling processes notifications concurrently with `flatMapMerge(concurrency = 16)` and collects them with `toList()` in `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/NotificationPolling.kt`. There is no final sort, so processing completion order can become display order and change between refreshes.
- `NotificationUiState.updatedAtEpochMs` is populated from GitHub's notification `updated_at` field by `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/state/NotificationUiState.kt` and `utilities/src/commonMain/kotlin/com/github/karlsabo/github/Notification.kt`. `notificationThreadId` comes from GitHub's stable thread `id`.
- Proposed ordering: ascending `updatedAtEpochMs`, then ascending `notificationThreadId` as a deterministic tie-breaker for equal timestamps. Sort the collected UI states before publishing each poll result; retain concurrent processing rather than making enrichment sequential.
- This is a small, reversible presentation-order change. It does not change notification fetching, filtering, actions, or refresh scheduling.
- One story, one acceptance test, and one PR.

## Open Questions

1. **Oldest timestamp:** Does “oldest” mean the oldest GitHub notification `updated_at` timestamp?
   - Recommendation: Yes. It is the notification activity timestamp already modeled by the app; GitHub's notification object does not provide a separate creation timestamp here. This means “oldest last activity” appears first.
   - Answer: awaiting confirmation.
2. **Legitimate movement on update:** When GitHub changes an existing thread's `updated_at`, should that thread move to its newly sorted position on the next refresh?
   - Recommendation: Yes. Guarantee deterministic ordering for the current timestamps, not permanently frozen rows; otherwise a newly updated old thread could remain incorrectly positioned.
   - Answer: awaiting confirmation.

## Acceptance Test

**Oldest notifications remain first across refreshes**

Given these visible notifications:

| Title | Thread ID | GitHub `updated_at` |
|---|---|---|
| Old alert | `thread-300` | `2026-05-29T09:00:00Z` |
| Same-time B | `thread-200` | `2026-05-29T11:00:00Z` |
| Same-time A | `thread-100` | `2026-05-29T11:00:00Z` |
| New alert | `thread-400` | `2026-05-29T13:00:00Z` |

When two refreshes process the same notifications in different completion orders, then both refreshed Notifications lists display this exact top-to-bottom order: **Old alert, Same-time A, Same-time B, New alert**. The equal-timestamp rows use thread ID order, and no row's position depends on API input order or processing completion order.

## Stories

### 1. Display notifications oldest first with deterministic refresh ordering

**Acceptance criteria:** Given the four notifications in the acceptance test above, when two polling refreshes produce them in different processing completion orders, then each visible result is ordered by `updated_at` ascending with `notificationThreadId` ascending for ties: `thread-300`, `thread-100`, `thread-200`, `thread-400`.

**Expected edits:**
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/NotificationPolling.kt` — apply the final ordering to each successfully collected notification list, after enrichment produces `NotificationUiState` values and before the result is exposed to the UI.
- `eng-hub/src/jvmTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/NotificationPollingJvmTest.kt` — add the refresh-order acceptance test through `viewModel.notifications`, controlling completion order so the test fails against the current unsorted pipeline.
- `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubNotificationPersistenceViewModelTest.kt` — only if needed, extend `NotificationPersistenceGitHubApi` with deterministic response gates/delays for the polling test; keep production behavior unchanged.

**Scope:**
- In: fixed ascending timestamp order, thread-ID tie-breaking, preservation of concurrent notification processing, and regression coverage at the polling result boundary consumed by the pane.
- Out: a sort-direction setting, grouping, pinning, changes to GitHub pagination/filtering, row-position preservation when timestamps actually change, scroll-position changes, and unrelated UI work.

**Notes:**
- The defect is in result ordering, not Compose row identity: `NotificationPanel.kt` already keys rows by thread ID, so no UI production edit is expected.
- Keep the comparator total and deterministic: `updatedAtEpochMs` ascending, then `notificationThreadId` ascending. Sorting only by timestamp leaves equal-timestamp rows vulnerable to the original unstable input order.
- Prefer sorting once at the poll-result boundary over sorting separately during rendering. This makes the state exposed to every consumer consistently ordered while leaving `flatMapMerge` concurrency intact.
- Build the test around explicit gates rather than sleeps or assumed dispatcher scheduling. Verify two different completion orders produce the same visible order; a test using only an already-sorted API response would pass without the fix.
- Run `./gradlew :eng-hub:jvmTest` for focused feedback, then `./gradlew clean build` from the repository root before considering the story complete.

## Milestone

**Oldest-first notification ordering verified:** the acceptance test passes across differing refresh completion orders, including equal timestamps, and the full repository build passes.
