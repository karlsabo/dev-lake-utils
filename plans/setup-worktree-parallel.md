# Setup worktrees in parallel from notifications

**Goal**: Let a user start setup for more than one pull-request notification at the same time without every Setup button switching to the global `Setting up...` state.

**Context**:

- The root `README.md` TODO explicitly calls out “Allow running multiple set up commands at a time from the notification view” and questions whether all setup buttons need to be disabled during setup.
- `eng-hub/README.md` lists “Per-item setup progress instead of one global `Setting up...` state” as a known gap.
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModel.kt` exposes one global `checkoutInProgressStateFlow: StateFlow<Boolean>` and sets it to `true` for the whole duration of `checkoutAndOpen(repoFullName, branch)`.
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/screen/EngHubScreen.kt` collects that single Boolean and passes it to both `PullRequestPanel` and `NotificationPanel`.
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/NotificationPanel.kt` passes the same Boolean to every `NotificationItem`.
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/NotificationItem.kt` disables each notification Setup button and changes its text to `Setting up...` whenever that global Boolean is true.
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreePanel.kt` already uses per-worktree in-flight state via `openingWorktreePaths`, so there is a nearby pattern for per-item progress.
- `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModelTest.kt` already has concurrency coverage for existing worktree setup (`openingExistingWorktreeTracksProgressForSelectedWorktreeOnly`, `concurrentExistingWorktreeCompletionsClearAllProgress`, and `concurrentDuplicateOpenAttemptsStartOneSetupJob`), but not for `checkoutAndOpen` from PR/notification rows.

## Questions for Karl to answer

Please answer inline under each question. These decisions affect the story split and acceptance criteria.

1. **Scope:** should this change apply only to the Notifications pane, or should the Pull Requests pane also stop using global setup progress since it is currently wired to the same Boolean?
    - Answer: yes all panes, lets reuse the same code for all of this logic. Then the UI should behave fine based on this backend style logic

2. **Progress identity:** should “same setup” be keyed by `(repoFullName, headRef)`, by notification thread id, or by the resolved local `worktreePath` after `ensureWorktree` returns?
    - Answer: Local worktree path

3. **Duplicate clicks:** if the same notification’s Setup button is clicked again while it is already setting up, should the second click be ignored/debounced so only one setup job runs for that repo+branch?
    - Answer: ignore it if it's already getting set up. If we're in a different UI such as the PR vs notification, then popup an error and add a listener to the progress of setup so that we can disable the UI button there too

4. **Cross-pane duplicate:** if the same PR appears in both Pull Requests and Notifications, and setup is started in one pane, should the matching row in the other pane also show `Setting up...`?
    - Answer: That would be idea, but that connection could be complicated to maintain and get slow. Is this a reasonable thing to implement? Lets chat more

5. **Same repo, different branches:** do we want `ensureRepository` / `ensureWorktree` for different branches in the same repo to run truly concurrently, or should git worktree creation be serialized per repo while setup commands run concurrently after worktrees exist?
    - Answer: ensureRepository should be serial, with some mutex per repo path. Then the same with ensureWorktree, mutex per worktree path. We want to be able to spin up multiple worktrees in parallel for the same repository

6. **Setup command concurrency:** are configured `setupCommands` expected to be safe when multiple worktrees for the same repo run them at the same time?
    - Answer: Yes, setup commands are safe

7. **Error reporting:** if two concurrent setups fail, is the current global error dialog with the latest/last error acceptable for this slice, or do we need per-item failure status?
    - Answer: Lets always pop up an error per failure, so two errors in this case

8. **Button interactions:** while one row is setting up, should non-setup actions on that same notification (`Open`, `Approve`, `Review`, `Done`, `Unsubscribe`) remain enabled unless their own action is in progress?
    - Answer:Yes, they should remain enabled, we can always open in the browser

9. **Completion behavior:** after a setup finishes, should the row simply return to `Setup`, or should it show a transient success/done state?
    - Answer: Setup is good for now

10. **Concurrency limit:** should there be any max number of simultaneous setup jobs, or should every clicked Setup start immediately?
    - Answer: No, every click should start up immediately

11. **Poll refresh behavior:** if the notification list refreshes while a setup is running, should progress survive as long as the refreshed item has the same repo+branch?
    - Answer: Yes, why would it not survive?

12. **Setup with no configured commands:** if a repo has no `setupCommands`, should the per-item progress still cover clone/worktree creation and then disappear when those complete?
    - Answer: Yes it should still cover worktree stuff

## Follow-up questions for Karl

These came out of the answers above.

13. **All panes includes Worktrees pane?** When setup starts from a notification or PR row, and the matching local worktree is visible in the Worktrees pane, should that Worktrees row also show `Setting up...` / disable its Open action for the same local worktree path? Conversely, if setup starts from the Worktrees pane, should matching PR/notification Setup buttons be disabled?
    - Answer: Yes, this would be ideal. If it's too difficult, when you click setup it's fine to popup an error saying setup is already in progress.

14. **Predicting local worktree path before setup:** `GitWorktreeService.buildWorktreePath(repoPath, branch)` already defines the local path for new worktrees, but `EngHubViewModel` currently only gets the final path after `ensureWorktree`. Should the implementation expose/reuse that path-building logic so progress can be keyed by local worktree path immediately when the click happens?
    - Answer: I had assumed `ensureWorktree` would properly defened setting up the same worktree in parallel. So we could return a future and then all callsites can properly wait on that future.

15. **Duplicate setup UX across panes:** If shared in-flight path state disables all matching buttons quickly, a duplicate click from another pane should only be possible during a race or stale UI frame. Do you still want an error popup for that duplicate, or should it silently ignore like same-button double-clicks?
    - Answer: If `ensureWorktree` can return a future, we can simply update the UI state, so race conditions are avoided in the business logic layer, not in the UI view model

16. **Multiple failure dialogs:** Showing one popup per concurrent failure requires replacing the single `actionError: String?` state with an error queue or list. Should that be part of this setup-parallel work, or a separate PR/story?
    - Answer: Different PR/story

17. **Worktree path collisions:** Existing path construction sanitizes branch names, so branches like `feature/a` and `feature-a` can map to the same local worktree path. Since you chose local worktree path as identity, those would be treated as the same setup. Is accepting that existing collision behavior OK for this feature?
    - Answer: Yes, that's ok, git will already say that something is in use. so we only want a single filesystem path to avoid confusion for the user.

18. **Mutex location/lifetime:** Is it enough for the per-repo and per-worktree mutexes to live inside one `EngHubViewModel` instance, or do you need locking across multiple app windows/processes too?
    - Answer: Nope, not in the view model. We want things in the business logic and if we can use futures or channels or some other concurrency library that allows the UI to get notified upon completion.

19. **Waiting state while blocked on repo mutex:** If two different branches for the same repo are clicked, the second setup may wait for `ensureRepository` on that repo to finish before it can create its worktree. Should its button show `Setting up...` during that wait?
    - Answer: Yes, it should. And Ideally the business logic can return a future, chanel, or some other concurrency primitive that lets the UI know what's happening.

## Second follow-up questions for Karl

I do have more. Your answers point to a business-logic coordinator rather than more ViewModel state. One pushback: making `ensureWorktree` return a future is probably the wrong abstraction by itself because the user-visible operation is “setup”, which includes repository clone/reuse, worktree create/reuse, and configured setup commands. A coordinator can still return an awaitable handle, but it should represent the whole setup operation.

20. **Coordinator abstraction:** are you okay with introducing a business-logic `WorktreeSetupCoordinator` / `WorktreeSetupService` that owns in-flight setup state, repo/worktree mutexes, and shared completion futures, instead of changing `GitWorktreeApi.ensureWorktree` itself to return a future?
    - Answer: Yes, a coordinator sounds like a good abstraction

21. **Future result scope:** should the shared awaitable result represent only clone + worktree creation, or the full setup operation including configured `setupCommands`?
    - Answer: Do what makes the most sense. We can do a future per worktree and setup, but a coordinator that wraps it all up into a nice utility should handle all the complexity of multiple futures.

22. **Progress detail:** when you said the UI should know “what’s happening”, do you want only a Boolean per local worktree path for now, or staged status such as `Waiting for repo`, `Creating worktree`, `Running setup commands`, `Failed`?
    - Answer: Status would be great

23. **Same awaitable result for duplicate callers:** if two UI callsites request the same local worktree path while the first setup is running, should both receive/await the exact same in-flight result and same success/failure result?
    - Answer: Yes they should get the same awaitable result, why build more?

24. **Failure fan-out for duplicate callers:** if duplicate callers share the same setup result and that setup fails, should the UI show one error for the underlying failed setup or one error per caller waiting on it? My recommendation: one error per underlying setup.
    - Answer: One per setup would be ideal

25. **Existing Worktrees pane behavior:** `openLocalWorktree(repoRootPath, worktreePath)` currently runs configured setup commands for an existing worktree but does not call `ensureRepository` or `ensureWorktree`. Should the new coordinator include an “existing worktree setup” path so Worktrees, PRs, and Notifications all publish progress to the same in-flight state by `worktreePath`?
    - Answer: Yes, using shared logic is ideal.

26. **Git worktree add concurrency risk:** you answered that different worktrees for the same repo should be created in parallel, with only a mutex per worktree path. If git itself fails because concurrent `git worktree add` operations contend on repo metadata, should we surface that failure as-is, retry, or fall back to serializing worktree creation per repo?
    - Answer: Let’s fail fast.

27. **Where business logic lives:** should the coordinator live in `eng-hub` because it needs `EngHubConfig` and setup commands, or in `utilities` beside `GitWorktreeService` with callbacks/hooks for app-specific setup commands?
    - Answer: I want it in the utilities project. it's more business logic and could be used by other projects

28. **API shape:** do you prefer the coordinator expose a `StateFlow<Set<String>>` of in-progress worktree paths plus a `setup(...): WorktreeSetupHandle` call, or a single flow of events per setup request? My recommendation: `StateFlow<Set<String>>` is enough for this story; staged event streams can come later.
    - Answer: I would prefer some enums or closed classes instead of strings. So StateFlow with enum values.

## Third follow-up questions for Karl

This should be the last useful batch. The remaining ambiguity is API shape and status semantics.

29. **Utilities coordinator inputs:** since `utilities` should not depend on `EngHubConfig`, should the coordinator API take plain setup inputs like `repoPath`, `cloneUrl`, `branch`, `worktreePath`, `setupCommands`, and `setupShell` from Eng Hub?
    - Answer: Yes that seems reasonable, we can also stuff it into a data class so there aren't amillion parameters

30. **Status state shape:** a plain enum is not enough because the UI needs to know which local worktree path each status belongs to. Are you good with a closed model like `StateFlow<Map<WorktreePath, WorktreeSetupStatus>>`, where `WorktreeSetupStatus` is an enum/sealed class (`WaitingForRepository`, `CreatingWorktree`, `RunningSetupCommands`, etc.)?
    - Answer: Yep, I'm good with that.

31. **Future API shape:** do you want the coordinator to expose `suspend fun setup(...): WorktreeSetupResult` and internally share in-flight results for duplicate calls, or do you specifically want callers to receive a `WorktreeSetupHandle` immediately?
    - Answer: Get an awaitable handle immediately, I do not want the UI thread to ever get blocked.

32. **Cancellation semantics:** if one UI caller awaiting a shared setup is cancelled/disposed, should the underlying setup keep running as long as the app is alive?
    - Answer: Yeah, that's fine for now. keep it running.

33. **Failure status lifetime:** when setup fails, should the worktree status disappear immediately after the failure result/error is emitted, or should a `Failed` status remain in the map until the user retries/dismisses it? My recommendation for this slice: remove it when done and rely on the existing/global error behavior until the error-queue story.
    - Answer: remove it when done and rely on the existing/global error behavior until the error-queue story.

34. **Existing worktree setup request:** for the Worktrees pane, should the coordinator have a separate entry point for existing worktrees that skips `ensureRepository` and `ensureWorktree` and only runs configured setup commands while publishing the same status by `worktreePath`?
    - Answer: The cooridnator logic should be hidden away from callers, so yes, the coordinator can skip steps but that's transparent to the caller, they simply get the status result.

35. **No setup commands status:** if there are no configured setup commands, should status stop after `ensureWorktree` completes, or should there be a brief `NoSetupCommands`/`Complete` status? My recommendation: stop after `ensureWorktree`; no transient success state.
    - Answer: Yeah, just stop

36. **Branch/path prediction source:** to key PR/notification rows by local path before `ensureWorktree`, should we move/expose `GitWorktreeService.buildWorktreePath(repoPath, branch)` through `GitWorktreeApi`, or create a small utility value/function used by both the coordinator and service?
    - Answer: I'm fine with having `buildWorktreePath` as the utility function.

## Resolved decisions

- The global `checkoutInProgress: Boolean` is the wrong shape. Replace it with shared setup status keyed by local worktree path.
- The shared setup logic belongs in `utilities`, not in `EngHubViewModel`.
- Introduce a coordinator abstraction that owns in-flight setup operations, path-keyed status, duplicate request sharing, and repo/worktree locking.
- Coordinator callers should receive a `WorktreeSetupHandle` immediately. Cancelling one awaiting UI caller should not cancel the underlying setup.
- The coordinator should expose a closed status model, likely `StateFlow<Map<WorktreePath, WorktreeSetupStatus>>`.
- The status model should support stages, at least: waiting for repository, creating/reusing worktree, running setup commands. Completed setup can simply disappear from the status map.
- Duplicate requests for the same local worktree path should share the same in-flight setup handle/result and should not run duplicate git/setup work.
- `ensureRepository` should be serialized per repo path.
- Worktree creation should be serialized per worktree path, but different worktree paths for the same repo may run in parallel after repo ensure completes. If git itself rejects parallel worktree operations, fail fast for now.
- Setup commands are assumed safe to run concurrently across different worktrees.
- PR, Notification, and Worktrees panes should all consume the same path-keyed setup status when practical.
- The Worktrees pane should use the same coordinator for existing worktree setup, with the coordinator skipping clone/worktree steps internally.
- Error queueing for one popup per concurrent setup failure is a separate PR/story. Until then, keep the existing global/latest error behavior.
- No transient success state is needed. After completion, the row can return to `Setup` / `Open`.
- `GitWorktreeService.buildWorktreePath(repoPath, branch)` should become/reuse a shared utility function so Eng Hub can compute the status key before `ensureWorktree` returns.

## Acceptance tests

1. Given two setup requests for the same local worktree path and the first setup is still running, when the second request is submitted, then both callers observe the same in-flight setup result and the underlying repository, worktree, and setup-command work runs once.
2. Given two setup requests for different local worktree paths in the same repository and repository ensure is required, when both requests are submitted, then repository ensure runs serially while both worktree setups are tracked independently and may proceed concurrently after repository ensure completes.
3. Given two pull-request notifications with different local worktree paths and the first setup is still running, when the user clicks Setup on the second notification, then the second setup starts immediately and only rows whose local worktree paths are in the coordinator status map show setup progress.
4. Given the same PR appears in both the Pull Requests and Notifications panes, when setup starts from one pane, then the matching row in the other pane reflects setup progress for the same local worktree path after it observes the shared status state.
5. Given an existing worktree row is setting up from the Worktrees pane, when matching PR/notification rows are visible or become visible, then they use the same local worktree path status and do not start duplicate setup work for that path.
6. Given a setup operation moves through repository, worktree, and setup-command stages, when a visible row represents that local worktree path, then the row displays the current setup stage instead of relying on one global `Setting up...` Boolean.
7. Given two setup operations fail concurrently, when both failures are reported, then the user can see both failure messages. This is intentionally split into a later error-queue story.

## Stories

### 1. Add a shared worktree setup coordinator in utilities - Done

**Done:** 2026 05 14

**Acceptance criteria:** Given two setup requests for the same local worktree path and the first setup is still running, when the second request is submitted, then both callers observe the same in-flight setup result and the underlying repository, worktree, and setup-command work runs once.

**Expected edits:**

- `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitWorktreeService.kt`
- `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitWorktreeApi.kt` if the path builder needs to move behind the interface
- `utilities/src/commonMain/kotlin/com/github/karlsabo/git/WorktreePath.kt` or similar new shared path utility
- `utilities/src/commonMain/kotlin/com/github/karlsabo/git/WorktreeSetupCoordinator.kt` or similar new coordinator
- `utilities/src/commonTest/kotlin/com/github/karlsabo/git/WorktreeSetupCoordinatorTest.kt`

**Scope:**

- Add a coordinator in `utilities` that accepts a setup request data class instead of a long parameter list.
- Request inputs should be plain utilities-level data: repo path, clone URL when needed, branch when needed, local worktree path, setup shell, and setup commands or a setup command runner callback.
- Return a `WorktreeSetupHandle` immediately.
- Publish `StateFlow<Map<WorktreePath, WorktreeSetupStatus>>` or equivalent closed model keyed by local worktree path.
- Share the same in-flight handle/awaitable result for duplicate requests with the same local worktree path.
- Keep the underlying setup running if one UI awaiter is cancelled.

**Out of scope:**

- Eng Hub UI integration.
- Multiple queued error dialogs.
- Cross-process locking.

**Notes:**

- `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitWorktreeService.kt` currently owns `GitWorktreeService.buildWorktreePath(repoPath, branch)`. Reuse or move this as the shared key computation helper.
- `utilities/utilities.gradle.kts` already has `kotlinx.coroutines.core`, so `CompletableDeferred`, `StateFlow`, and `Mutex` are available.
- The coordinator should represent full setup from the app user's perspective: repo clone/reuse, worktree create/reuse, and configured setup commands.

### 2. Allow different worktrees to set up in parallel while serializing repository ensure

**Done:** 2026 05 14

**Acceptance criteria:** Given two setup requests for different local worktree paths in the same repository and repository ensure is required, when both requests are submitted, then repository ensure runs serially while both worktree setups are tracked independently and may proceed concurrently after repository ensure completes.

**Expected edits:**

- `utilities/src/commonMain/kotlin/com/github/karlsabo/git/WorktreeSetupCoordinator.kt`
- `utilities/src/commonTest/kotlin/com/github/karlsabo/git/WorktreeSetupCoordinatorTest.kt`

**Scope:**

- Add a per-repo-path mutex around repository ensure.
- Add a per-worktree-path mutex or in-flight map around worktree setup for a single local path.
- Allow different local worktree paths for the same repo to proceed in parallel once the repo ensure section is done.
- Publish separate statuses for each local worktree path while any request is waiting on the repo mutex.
- Fail fast if git rejects parallel worktree creation for different paths.

**Out of scope:**

- Retrying git worktree failures.
- Falling back to per-repo serialization for all worktree creation.
- A global concurrency limit.

**Notes:**

- This is the concurrency policy Karl chose: repo ensure serial, same worktree path de-duped, different worktree paths parallel.
- Use tests with blocking fakes to prove there is never more than one concurrent repository ensure for the same repo path and that two different worktree setup operations can both reach setup-command execution before either completes.

### 3. Route PR and notification Setup actions through the coordinator

**Done:** 2026 05 14

**Acceptance criteria:** Given two pull-request notifications with different local worktree paths and the first setup is still running, when the user clicks Setup on the second notification, then the second setup starts immediately and only rows whose local worktree paths are in the coordinator status map show setup progress.

**Expected edits:**

- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHubComponent.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModel.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/screen/EngHubScreen.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/NotificationPanel.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/NotificationItem.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/PullRequestPanel.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/PullRequestItem.kt`
- `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModelTest.kt`
- `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/EngHubDependenciesTest.kt`

**Scope:**

- Inject/provide the coordinator from Eng Hub dependencies.
- Replace `checkoutInProgressStateFlow: StateFlow<Boolean>` with coordinator-backed status keyed by local worktree path.
- Compute repo path from `EngHubConfig.repositoriesBaseDir` and repo name as `checkoutAndOpen` does today.
- Compute local worktree path before starting setup using the shared path utility.
- Keep non-setup notification actions enabled unless their existing `actingOnThreadIds` state disables them.
- Keep setup progress alive across notification polling refreshes because refreshed rows recompute the same local worktree path.

**Out of scope:**

- Worktrees pane integration.
- Error queueing for multiple concurrent failures.
- Success state after setup completes.

**Notes:**

- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModel.kt` currently sets one global `checkoutInProgress` Boolean around `checkoutAndOpen`; this story removes that global user-visible coupling for PR and notification rows.
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/NotificationItem.kt` and `PullRequestItem.kt` currently receive one Boolean and therefore disable every Setup button together.
- The current setup command lookup lives in `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/WorktreeSetupCommands.kt`; Eng Hub should adapt configured commands into the utilities coordinator request without making utilities depend on `EngHubConfig`.

### 4. Share setup progress across PR and notification rows for the same worktree path

**Acceptance criteria:** Given the same PR appears in both the Pull Requests and Notifications panes, when setup starts from one pane, then the matching row in the other pane reflects setup progress for the same local worktree path after it observes the shared status state.

**Expected edits:**

- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModel.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/screen/EngHubScreen.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/NotificationPanel.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/NotificationItem.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/PullRequestPanel.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/PullRequestItem.kt`
- `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModelTest.kt`

**Scope:**

- Derive the same local worktree path key for PR rows and notification rows that point to the same repo and branch.
- Disable/show progress on all visible matching rows from the shared coordinator status map.
- Treat stale-frame duplicate clicks as duplicate coordinator requests, not as new setup jobs.

**Out of scope:**

- Expensive cross-list matching beyond local worktree path derivation.
- Showing historical success/failure status after setup completes.

**Notes:**

- This should be cheap: both panes can independently compute the same local path key and look it up in the same status map. No complex listener graph between panes is needed.
- Karl said cross-pane progress would be ideal if it is reasonable; path-keyed status makes it reasonable.

### 5. Route existing Worktrees pane setup through the coordinator

**Acceptance criteria:** Given an existing worktree row is setting up from the Worktrees pane, when matching PR/notification rows are visible or become visible, then they use the same local worktree path status and do not start duplicate setup work for that path.

**Expected edits:**

- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModel.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreePanel.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/screen/EngHubScreen.kt`
- `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModelTest.kt`
- `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreePanelTest.kt` if component logic changes

**Scope:**

- Replace or bridge `openingLocalWorktreePathsStateFlow` with coordinator-backed status for existing worktree setup.
- Existing worktree requests should skip repository clone and worktree creation internally while still running configured setup commands.
- Keep duplicate existing-worktree setup clicks from starting duplicate commands.
- Preserve current behavior where the Worktrees row shows setup/open progress only for the selected worktree, not every worktree.

**Out of scope:**

- Changing archive progress state.
- Adding PR metadata to Worktrees rows.
- Error queueing.

**Notes:**

- `EngHubViewModel.openLocalWorktree(repoRootPath, worktreePath)` currently has separate in-flight path tracking and runs `runConfiguredSetupForWorktree` directly.
- Existing tests in `EngHubViewModelTest.kt` around `openingExistingWorktreeTracksProgressForSelectedWorktreeOnly`, `concurrentExistingWorktreeCompletionsClearAllProgress`, and `concurrentDuplicateOpenAttemptsStartOneSetupJob` should either be updated or reused as regression coverage.

### 6. Display staged setup status in visible rows

**Acceptance criteria:** Given a setup operation moves through repository, worktree, and setup-command stages, when a visible row represents that local worktree path, then the row displays the current setup stage instead of relying on one global `Setting up...` Boolean.

**Expected edits:**

- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/NotificationItem.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/PullRequestItem.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreePanel.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/screen/EngHubScreen.kt`

**Scope:**

- Map coordinator statuses to short user-facing row labels, for example `Waiting for repo...`, `Creating worktree...`, and `Running setup...`.
- Disable only the setup/open action for rows whose local path has an active status.
- Return to the normal `Setup` or `Open` label when status disappears after completion.

**Out of scope:**

- Persisting status after completion.
- A success toast/history.
- Per-row failure display.

**Notes:**

- This can be implemented in the same PR as Stories 3/5 if small, but it has its own acceptance test because staged status is separate from allowing parallel setup.

### 7. Queue setup errors so concurrent failures are all visible

**Acceptance criteria:** Given two setup operations fail concurrently, when both failures are reported, then the user can see both failure messages.

**Expected edits:**

- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModel.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/screen/EngHubScreen.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/ErrorDialog.kt`
- `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModelTest.kt`

**Scope:**

- Replace or supplement `actionError: MutableStateFlow<String?>` with an error queue/list.
- Show one dismissible error at a time while preserving later errors.
- Ensure one underlying failed setup produces one queued error even if duplicate callers shared its awaitable result.

**Out of scope:**

- Per-row failure badges.
- Retrying failed setup from the error dialog.

**Notes:**

- Karl explicitly split multiple failure dialogs into a different PR/story, so this should come after the coordinator and UI progress stories.
