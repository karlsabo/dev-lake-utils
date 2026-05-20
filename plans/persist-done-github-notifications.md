# Persist ignored GitHub notifications locally

**Goal**: Notifications that the user or Eng Hub decides to ignore should stay hidden across polls and app restarts even when GitHub returns them from the notifications API.

**Context**:

- GitHub does not provide the app-level hidden state Eng Hub needs. The API exposes read/unread and subscription state, but Eng Hub needs to remember: "this thread should no longer be shown here." That local decision is broader than GitHub's read/done/subscribed vocabulary.
- The Eng Hub notification poll calls `gitHubApi.listNotifications()` and filters out only `hiddenThreadIds`; those IDs are loaded from `NotificationSubscriptionStore.listUnsubscribedThreadIds()` at ViewModel construction. Evidence: `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModel.kt` (`loadHiddenThreadIds`, `hiddenThreadIds`, `polledNotifications`, `notifications`).
- Manual Done currently only updates in-memory state and calls GitHub; it does not write local storage. Evidence: `EngHubViewModel.markNotificationDone(notificationThreadId: String)` in `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModel.kt`.
- Approve and Submit Review also call `gitHubApi.markNotificationAsDone(notificationThreadId)` and update only in-memory hidden IDs. Evidence: `EngHubViewModel.approvePullRequest` and `EngHubViewModel.submitReview` in `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModel.kt`.
- Automatic notification processing marks merged/closed PR notifications and selected auto-approved PR notifications as done, but the ViewModel only suppresses those for the current poll result. Evidence: `GitHubNotificationService.processPullRequestNotification` and `NotificationAction.MarkedAsDone` in `utilities/src/commonMain/kotlin/com/github/karlsabo/github/GitHubNotificationService.kt`; `polledNotifications` drops notifications when `processed.actions` contains `MarkedAsDone` in `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModel.kt`.
- The GitHub REST notifications call intentionally uses `all=true`, so read notifications are returned too. Evidence: `GitHubRestApi.listNotifications` in `utilities/src/commonMain/kotlin/com/github/karlsabo/github/GitHubRestApi.kt`.
- Existing local persistence is unsubscribe-specific: `NotificationSubscriptionStore` exposes only `listUnsubscribedThreadIds` and `saveUnsubscribedThread`. Evidence: `utilities/src/commonMain/kotlin/com/github/karlsabo/notifications/NotificationSubscriptionStore.kt` and `utilities/src/commonMain/kotlin/com/github/karlsabo/notifications/SqlDelightNotificationSubscriptionStore.kt`.
- Existing SQLDelight schema has an `unsubscribed_notification_threads` table and an unused-looking `ignored_at_epoch_ms` column, but `unsubscribed_at_epoch_ms` is still `NOT NULL`. Evidence: `utilities/src/commonMain/sqldelight/com/github/karlsabo/notifications/UnsubscribedNotificationThreads.sq` and migration test `utilities/src/jvmTest/kotlin/com/github/karlsabo/notifications/NotificationDatabaseMigrationTest.kt`.
- UI Done callback currently passes only the thread ID, not the full notification metadata. Evidence: `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/NotificationItem.kt`, `NotificationPanel.kt`, and `EngHubScreen.kt`.

## Framing

Eng Hub should not try to reconstruct GitHub's "done" state. It should record a local ignore decision:

> Eng Hub records notification threads the user or app has decided to hide permanently, because GitHub's API does not provide a durable filter for that app-level decision.

This means unsubscribe and done are both reasons for the same local concept: an ignored notification thread.

## Resolved decisions

- Evolve the existing `NotificationSubscriptionStore` / `SqlDelightNotificationSubscriptionStore` into a generic ignore store; do not introduce a separate parallel persistence path. This should be a modest in-place refactor that preserves the existing unsubscribe behavior and adds support for done/ignored notifications.
- Persist ignored notification threads for every path that should make a thread disappear from Eng Hub:
    - explicit Done
    - Unsubscribe
    - Approve
    - Submit Review
    - automatic closed/merged PR cleanup
    - automatic appfile approval
- Store the ignore reason for debugging and future behavior. Initial reasons:
    - `UNSUBSCRIBED`
    - `DONE`
- Store ignored-notification metadata in the same debugging shape as the current unsubscribe records: `thread_id`, `repository_full_name`, `subject_type`, plus `ignore_reason` and `ignored_at_epoch_ms`.
- Replace or migrate away from the unsubscribe-specific table shape. Prefer a generic table such as `ignored_notification_threads` over adding a dedicated `done_notification_threads` table.
- If a user-triggered ignore path succeeds in GitHub but fails local persistence, restore the notification in the UI and show an action error because durable hiding is not guaranteed.
- Automatic poll cleanup persistence failures should be logged and retried on a later poll, not shown as user-visible action errors.
- Ignored notification records should be kept indefinitely for this feature.
- Pruning is deferred. Tracking TODO added to `README.md`: "Add a database pruning background task that runs at startup and then every hour to prune old DB entries."
- Split approve, submit-review, automatic merged/closed cleanup, and automatic appfile auto-approval into separate stories.

## Acceptance tests

### A. Explicit Done stays hidden after restart

Given GitHub returns notification thread `thread-1` for `test-org/test-repo`, when I click Done for `thread-1`, then Eng Hub hides it immediately, records it locally as ignored with reason `DONE`, and after restarting Eng Hub while GitHub still returns `thread-1`, the notification list does not show `thread-1`.

### B. Unsubscribe stays hidden through the generic ignore store

Given GitHub returns notification thread `thread-unsubscribed` for `test-org/test-repo`, when I unsubscribe from `thread-unsubscribed`, then Eng Hub records it locally as ignored with reason `UNSUBSCRIBED`, and after restarting Eng Hub while GitHub still returns `thread-unsubscribed`, the notification list does not show `thread-unsubscribed`.

### C. Approve action ignore state stays hidden after restart

Given GitHub returns pull request notification thread `thread-2` for PR `#22`, when I click Approve in Eng Hub and the PR approval and notification mark-done calls succeed, then Eng Hub records `thread-2` locally as ignored with reason `DONE` and after restarting Eng Hub while GitHub still returns `thread-2`, the notification list does not show `thread-2`.

### D. Submit Review action ignore state stays hidden after restart

Given GitHub returns pull request notification thread `thread-3` for PR `#23`, when I submit a review from Eng Hub and the review submission and notification mark-done calls succeed, then Eng Hub records `thread-3` locally as ignored with reason `DONE` and after restarting Eng Hub while GitHub still returns `thread-3`, the notification list does not show `thread-3`.

### E. Automatic merged/closed PR cleanup stays hidden after restart

Given GitHub returns pull request notification thread `thread-4` for PR `#24` that is already merged or closed, when Eng Hub polls notifications and marks `thread-4` done automatically, then Eng Hub records `thread-4` locally as ignored with reason `DONE` and after restarting Eng Hub while GitHub still returns `thread-4`, the notification list does not show `thread-4`.

### F. Automatic appfile approval stays hidden after restart

Given GitHub returns pull request notification thread `thread-5` for an open PR titled `Updating appfile demo service`, when Eng Hub auto-approves the PR and marks `thread-5` done automatically, then Eng Hub records `thread-5` locally as ignored with reason `DONE` and after restarting Eng Hub while GitHub still returns `thread-5`, the notification list does not show `thread-5`.

### G. Failed user-triggered local persistence does not silently lose the ignore action

Given GitHub accepts marking notification `thread-6` done but the local ignore-store write fails, when I click Done for `thread-6`, then Eng Hub shows an action error and restores `thread-6` in the notification list because durable local hiding was not recorded.

### H. Failed automatic local persistence logs and retries later

Given Eng Hub automatically marks notification `thread-7` done in GitHub but the local ignore-store write fails, when the poll completes, then Eng Hub logs the persistence failure and does not show a user-facing action error, so a later poll can retry processing if GitHub still returns `thread-7`.

## Stories

### 1. Introduce generic ignored-notification persistence and preserve unsubscribe behavior - Done

**Done:** 2026 05 20

**Acceptance criteria:** Given GitHub returns notification thread `thread-unsubscribed` for `test-org/test-repo`, when I unsubscribe from `thread-unsubscribed`, then Eng Hub records it locally as ignored with reason `UNSUBSCRIBED`, and after restarting Eng Hub while GitHub still returns `thread-unsubscribed`, the notification list does not show `thread-unsubscribed`.

**Expected edits:**

- `utilities/src/commonMain/kotlin/com/github/karlsabo/notifications/NotificationSubscriptionStore.kt` — refactor the existing store interface in place into the generic ignore abstraction, likely renamed to `NotificationIgnoreStore`; expose APIs such as `listIgnoredThreadIds()` and `saveIgnoredThread(...)` with an ignore reason.
- `utilities/src/commonMain/kotlin/com/github/karlsabo/notifications/SqlDelightNotificationSubscriptionStore.kt` — refactor the existing SQLDelight implementation in place into the generic ignore-store implementation, likely renamed to `SqlDelightNotificationIgnoreStore`; preserve unsubscribe behavior and add done/ignored thread support.
- `utilities/src/commonMain/sqldelight/com/github/karlsabo/notifications/UnsubscribedNotificationThreads.sq` or a new `.sq` file in the same directory — define `ignored_notification_threads` with `thread_id`, `repository_full_name`, `subject_type`, `ignore_reason`, `ignored_at_epoch_ms`.
- SQLDelight migration under `utilities/src/commonMain/sqldelight/com/github/karlsabo/notifications/` — migrate existing `unsubscribed_notification_threads` rows into `ignored_notification_threads` with reason `UNSUBSCRIBED`.
- `utilities/src/commonTest/kotlin/com/github/karlsabo/notifications/SqlDelightNotificationSubscriptionStoreTest.kt` — rename/update tests for generic ignore persistence and unsubscribe preservation.
- `utilities/src/jvmTest/kotlin/com/github/karlsabo/notifications/NotificationDatabaseMigrationTest.kt` — verify migration preserves existing unsubscribe rows as ignored rows and creates the generic ignored table.
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHubComponent.kt` — provide `NotificationIgnoreStore`.
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModel.kt` — load hidden IDs from `listIgnoredThreadIds()` and persist unsubscribe as reason `UNSUBSCRIBED`.
- `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubNotificationPersistenceViewModelTest.kt` — cover restart-style filtering from persisted ignored IDs.

**Scope:**

- In: generic ignore table/store, migration from unsubscribe-specific rows, current unsubscribe success path, startup filtering from ignored IDs.
- Out: explicit Done, approve, submit-review, automatic cleanup, automatic appfile approval, pruning old records, UI for viewing/restoring ignored records.

**Notes:**

- This is the data-model foundation story. It should reuse the existing unsubscribe persistence path as much as practical while changing the vocabulary from subscription-specific persistence to generic local ignore persistence.
- The old table can be kept as a compatibility artifact if SQLDelight migration constraints make dropping awkward, but new code should use `ignored_notification_threads`.

### 2. Persist explicit Done as an ignored notification and hide it on startup - Done

**Done:** 2026 05 20

**Acceptance criteria:** Given GitHub returns notification thread `thread-1` for `test-org/test-repo`, when I click Done for `thread-1`, then Eng Hub hides it immediately, records it locally as ignored with reason `DONE`, and after restarting Eng Hub while GitHub still returns `thread-1`, the notification list does not show `thread-1`.

**Expected edits:**

- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModel.kt` — persist explicit Done with reason `DONE` and notification metadata.
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/NotificationItem.kt` — pass full `NotificationUiState` to Done action if needed for metadata.
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/NotificationPanel.kt` — update Done callback signature.
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/screen/EngHubScreen.kt` — update Done callback wiring.
- `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubNotificationPersistenceViewModelTest.kt` — cover explicit Done persistence and restart filtering.

**Scope:**

- In: explicit Done success path using the generic ignore store.
- Out: approve, submit-review, automatic cleanup, automatic appfile approval, pruning old records.

**Notes:**

- Keep GitHub API wrapper focused on GitHub calls; local ignore persistence belongs above it in Eng Hub notification orchestration.

### 3. Restore explicit Done notification and show an error when local ignore persistence fails - Done

**Done:** 2026 05 20

**Acceptance criteria:** Given GitHub accepts marking notification `thread-6` done but the local ignore-store write fails, when I click Done for `thread-6`, then Eng Hub shows an action error and restores `thread-6` in the notification list because durable local hiding was not recorded.

**Expected edits:**

- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModel.kt`
- `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubNotificationPersistenceViewModelTest.kt`

**Scope:**

- In: user-visible error and in-memory hidden-set rollback for explicit Done local persistence failure.
- Out: retry queue, automatic DB repair, automatic poll failure handling.

**Notes:**

- This story assumes Story 2 added the explicit Done ignore persistence path.
- Preserve existing behavior for GitHub mark-done failure: restore visibility and show the mark-done error.

### 4. Persist Approve action as ignored - Done

**Done:** 2026 05 20

**Acceptance criteria:** Given GitHub returns pull request notification thread `thread-2` for PR `#22`, when I click Approve in Eng Hub and the PR approval and notification mark-done calls succeed, then Eng Hub records `thread-2` locally as ignored with reason `DONE` and after restarting Eng Hub while GitHub still returns `thread-2`, the notification list does not show `thread-2`.

**Expected edits:**

- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModel.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/NotificationItem.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/NotificationPanel.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/screen/EngHubScreen.kt`
- `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubNotificationPersistenceViewModelTest.kt`

**Scope:**

- In: persist an ignored record with reason `DONE` after successful PR approval and successful notification mark-done.
- Out: submit-review, automatic cleanup, automatic appfile approval.

**Notes:**

- Existing approve callback passes `notificationThreadId` and `apiUrl`; it likely needs `NotificationUiState` so the store can save repository and subject metadata.
- Ordering should be: approve PR succeeds, GitHub mark-done succeeds, local ignore save succeeds, then durable hiding is guaranteed.

### 5. Persist Submit Review action as ignored - Done

**Done:** 2026 05 20

**Acceptance criteria:** Given GitHub returns pull request notification thread `thread-3` for PR `#23`, when I submit a review from Eng Hub and the review submission and notification mark-done calls succeed, then Eng Hub records `thread-3` locally as ignored with reason `DONE` and after restarting Eng Hub while GitHub still returns `thread-3`, the notification list does not show `thread-3`.

**Expected edits:**

- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModel.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/NotificationItem.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/NotificationPanel.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/screen/EngHubScreen.kt`
- `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubNotificationPersistenceViewModelTest.kt`

**Scope:**

- In: persist an ignored record with reason `DONE` after successful review submission and successful notification mark-done.
- Out: approve, automatic cleanup, automatic appfile approval.

**Notes:**

- This should reuse the same internal helper introduced for explicit Done / Approve rather than duplicating persistence ordering.

### 6. Persist automatic merged/closed PR cleanup as ignored

**Acceptance criteria:** Given GitHub returns pull request notification thread `thread-4` for PR `#24` that is already merged or closed, when Eng Hub polls notifications and marks `thread-4` done automatically, then Eng Hub records `thread-4` locally as ignored with reason `DONE` and after restarting Eng Hub while GitHub still returns `thread-4`, the notification list does not show `thread-4`.

**Expected edits:**

- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModel.kt`
- `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubNotificationPersistenceViewModelTest.kt`
- Possibly `utilities/src/commonMain/kotlin/com/github/karlsabo/github/GitHubNotificationService.kt` only if the service result does not expose enough information.

**Scope:**

- In: when notification processing marks a merged/closed PR notification done, persist that notification as ignored with reason `DONE` before suppressing it from emitted UI state.
- Out: automatic appfile approval, user-triggered approve/review/done actions.

**Notes:**

- The ViewModel already sees both the original `Notification` and the `NotificationProcessingResult`; it should be able to persist without injecting storage into `GitHubNotificationService`.
- If local ignore persistence fails during polling, log and retry on a later poll; do not show an action error.

### 7. Persist automatic appfile approval as ignored

**Acceptance criteria:** Given GitHub returns pull request notification thread `thread-5` for an open PR titled `Updating appfile demo service`, when Eng Hub auto-approves the PR and marks `thread-5` done automatically, then Eng Hub records `thread-5` locally as ignored with reason `DONE` and after restarting Eng Hub while GitHub still returns `thread-5`, the notification list does not show `thread-5`.

**Expected edits:**

- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModel.kt`
- `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubNotificationPersistenceViewModelTest.kt`
- Possibly `utilities/src/commonMain/kotlin/com/github/karlsabo/github/GitHubNotificationService.kt` only if the service result cannot distinguish auto-approval from closed/merged cleanup.

**Scope:**

- In: persist ignored records with reason `DONE` when existing auto-approval processing marks an appfile notification done.
- Out: changing the appfile auto-approve predicate, user-triggered actions, merged/closed cleanup.

**Notes:**

- `GitHubNotificationService` currently reports actions including `ApprovedPullRequest` and `MarkedAsDone`; the ViewModel can use those actions to identify this path if the result remains sufficiently explicit.
- If local ignore persistence fails during polling, log and retry on a later poll; do not show an action error.

### 8. Log automatic local ignore-persistence failures without user-facing action errors

**Acceptance criteria:** Given Eng Hub automatically marks notification `thread-7` done in GitHub but the local ignore-store write fails, when the poll completes, then Eng Hub logs the persistence failure and does not show a user-facing action error, so a later poll can retry processing if GitHub still returns `thread-7`.

**Expected edits:**

- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModel.kt`
- `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubNotificationPersistenceViewModelTest.kt`

**Scope:**

- In: automatic poll ignore-persistence failure behavior only.
- Out: user-triggered failure behavior, retry queue, database repair.

**Notes:**

- This story is separate because user-triggered failures intentionally show an action error, while background polling failures should not.
