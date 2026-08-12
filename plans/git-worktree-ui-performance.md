# Git worktree UI progressive loading

**Status:** Draft — ready for review.

**Goal:** Make repository expansion feel immediate by opening the worktree section at once, showing visible progress, rendering locally discovered worktrees before stack analysis finishes, and enriching the rows asynchronously without contacting or refreshing remotes.

## Context and product decisions

- Scope is the Eng Hub worktree display path. Worktree creation, validation, setup, archive, and explicit rebase behavior are out of scope; their existing remote behavior is unchanged.
- Today expansion does not set `isExpanded` until `listLocalWorktreeUiStates` completes, so the toggle appears to hang. That helper synchronously lists worktrees, infers parent branches, and checks whether every inferred child needs a rebase (`eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/LocalRepositoryController.kt`, `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/LocalWorktreeStateMappers.kt`).
- Stack inference currently resolves a remote default-branch ref and invokes `fetchRemoteBestEffort` before ancestry analysis. This can perform network I/O and mutate remote-tracking refs merely because the user expanded a repository (`utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitWorktreeService.kt`). Display-time stack analysis will use only refs and commit data already present in the local repository.
- `LocalRepositoryUiState` currently has only `isExpanded` and a final worktree list. It cannot distinguish “expanded and loading” from an empty repository, nor basic rows from stack-enriched rows (`eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/state/LocalRepositoryUiState.kt`). Add explicit loading state rather than inferring progress from an empty list.
- The existing repository row renders children only when the repository is expanded and the list is non-empty; it has no loading affordance (`eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeRepositoryRows.kt`).
- Progressive loading has two useful boundaries: first run local worktree discovery and render all returned worktree rows; then infer local parent relationships and rebase status in background work and merge those fields into the existing rows. `git worktree list` returns one snapshot, so “as we discover them” means rows appear after that fast local command rather than one row per subprocess result.
- Expansion must never wait for stack inference. Tests should prove ordering with controllable suspended fake operations rather than assert a machine-dependent millisecond threshold. This gives a deterministic fitness function: expanded/loading state is published before discovery returns, and basic rows are published before enrichment returns.
- A collapse or newer refresh invalidates older background results. Late discovery/enrichment must not reopen a collapsed repository or overwrite newer worktree data.
- Loading failures should stop the spinner and retain any rows already discovered. Initial discovery failure continues to use the existing action-error path; enrichment remains best-effort and should not replace usable rows with an error.
- Polling and post-action refreshes should use the same staged display pipeline where practical so they do not reintroduce blocking stack analysis. Poll refreshes must preserve current rows while refreshing rather than flash an empty list or spinner on every interval.

## Acceptance tests

1. **Expand immediately with visible progress**
   - Given a configured collapsed repository whose local worktree discovery is still running, when the user expands it, then the repository opens immediately and shows a loading spinner before discovery completes.

2. **Show worktrees before stack analysis finishes**
   - Given local discovery returns `main` and `feature/stacked-pr` while stack enrichment remains suspended, when the repository is expanded, then both worktree rows are visible without nesting/rebase metadata and the UI still indicates background loading.

3. **Apply locally derived stack information progressively**
   - Given basic worktree rows are visible and local ancestry analysis determines `feature/stacked-pr` is based on `main` and needs rebasing, when enrichment completes, then the existing rows update to show the hierarchy/rebase state and the loading spinner disappears.

4. **Never contact a remote while loading display metadata**
   - Given a repository has only its existing local refs and its configured remote is unavailable, when worktree display enrichment runs, then it completes from local information without fetch or remote-query commands and without changing remote-tracking refs.

5. **Ignore stale asynchronous results**
   - Given worktree loading is in progress, when the user collapses the repository before loading completes, then late discovery or enrichment results do not reopen it or replace data from a later refresh.

6. **Keep discovered rows when enrichment fails**
   - Given basic rows are visible, when local stack or rebase analysis fails, then the spinner stops and the basic rows remain usable without inferred hierarchy/rebase metadata.

## Stories

### 1. Expand immediately with a loading indicator

**Status:** Done.

**Acceptance criteria:** Given a configured collapsed repository whose local worktree discovery is still running, when the user expands it, then the repository opens immediately and shows a loading spinner before discovery completes.

**Expected edits:**
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/state/LocalRepositoryUiState.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/LocalRepositoryController.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeRepositoryRows.kt`
- `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubLocalRepositoryViewModelTest.kt`
- `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreePanelTest.kt`

**Scope:** Publish expanded/loading state synchronously, render an accessible indeterminate progress indicator, clear loading on initial discovery failure, and preserve existing collapse behavior. Do not yet split basic discovery from enrichment.

**Notes:** Model loading explicitly on the repository UI state. The test should suspend fake discovery and assert expanded/loading state before releasing it; avoid timing benchmarks in unit tests.

### 2. Render basic worktree rows before stack enrichment

**Status:** Done.

**Acceptance criteria:** Given local discovery returns `main` and `feature/stacked-pr` while stack enrichment remains suspended, when the repository is expanded, then both worktree rows are visible without nesting/rebase metadata and the UI still indicates background loading.

**Expected edits:**
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/LocalWorktreeStateMappers.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/LocalRepositoryController.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/state/LocalRepositoryUiState.kt`
- `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubLocalRepositoryViewModelTest.kt`
- `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModelTestFixtures.kt`

**Scope:** Split the current all-at-once mapper into fast local discovery/basic mapping and asynchronous enrichment. Publish the complete basic worktree snapshot first. Keep the spinner active until enrichment completes. Apply the staged path to expansion first as the tracer bullet.

**Notes:** Keep worktree identity path-based when merging later metadata. Do not launch one coroutine per worktree without bounds; the expensive ancestry checks share one repository and should run in a structured background job.

### 3. Update visible rows with background stack and rebase metadata

**Status:** Done.

**Acceptance criteria:** Given basic worktree rows are visible and local ancestry analysis determines `feature/stacked-pr` is based on `main` and needs rebasing, when enrichment completes, then the existing rows update to show the hierarchy/rebase state and the loading spinner disappears.

**Expected edits:**
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/LocalRepositoryController.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/LocalWorktreeStateMappers.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/state/LocalRepositoryUiState.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeRowNesting.kt`
- `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubLocalRepositoryViewModelTest.kt`
- `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/state/LocalRepositoryUiStateTest.kt`

**Scope:** Merge enrichment into currently displayed rows and finish loading. Preserve row actions and dirty/root/base-commit fields while metadata changes. Extend staged refresh to polling and post-action refresh call sites so they do not block on enrichment.

**Notes:** Parent inference must complete before rebase checks because the latter depend on inferred parent-child pairs. The UI may receive one parent-map update followed by rebase updates, or one combined enrichment update; prefer the simpler implementation unless profiling proves finer granularity matters.

### 4. Make display-time stack inference local-only

**Status:** Done.

**Acceptance criteria:** Given a repository has only its existing local refs and its configured remote is unavailable, when worktree display enrichment runs, then it completes from local information without fetch or remote-query commands and without changing remote-tracking refs.

**Expected edits:**
- `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitWorktreeService.kt`
- `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitWorktreeApi.kt`
- `utilities/src/commonTest/kotlin/com/github/karlsabo/git/GitWorktreeServiceHierarchyTest.kt`
- `utilities/src/commonTest/kotlin/com/github/karlsabo/git/FakeGitCommandApi.kt`

**Scope:** Remove fetch/network behavior from parent inference used by display. Use visible local worktree branches and already-present local refs only. Preserve remote checks used by worktree creation and other non-display workflows.

**Notes:** Separate default-branch resolution by purpose if the existing resolver is shared: display inference gets a local-only resolver, while creation keeps its current semantics. Merely moving fetch to another background thread does not satisfy this story.

### 5. Discard stale discovery and enrichment results

**Acceptance criteria:** Given worktree loading is in progress, when the user collapses the repository before loading completes, then late discovery or enrichment results do not reopen it or replace data from a later refresh.

**Expected edits:**
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/LocalRepositoryController.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/LocalRepositoryExpansionTracker.kt`
- `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubLocalRepositoryViewModelTest.kt`

**Scope:** Add per-repository generation/job ownership, cancel obsolete enrichment where possible, and guard every state commit. Cover collapse and overlapping refreshes.

**Notes:** Cancellation alone is insufficient because Git subprocess calls may finish after cancellation; compare a generation/token before committing state.

### 6. Preserve basic rows when enrichment fails

**Acceptance criteria:** Given basic rows are visible, when local stack or rebase analysis fails, then the spinner stops and the basic rows remain usable without inferred hierarchy/rebase metadata.

**Expected edits:**
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/LocalRepositoryController.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/LocalWorktreeStateMappers.kt`
- `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubLocalRepositoryViewModelTest.kt`

**Scope:** Define terminal failure handling for enrichment, retain discovered fields, avoid an action-error toast for best-effort metadata, and ensure loading always terminates for the current generation.

**Notes:** Log enrichment failures with repository context. Initial worktree discovery failure remains actionable because no rows can be shown; enrichment failure is degraded display metadata, not a failed user action.

## Sequence and trade-offs

1. Story 1 makes the current wait visible but does not improve time-to-rows.
2. Story 2 is the main perceived-performance tracer bullet: basic rows no longer wait for stack analysis.
3. Story 3 restores full hierarchy/rebase behavior progressively and removes blocking enrichment from all display refresh paths.
4. Story 4 guarantees display cannot regress due to network latency. It is separate because it changes the shared git service boundary and needs focused command-level tests.
5. Story 5 makes the asynchronous design race-safe.
6. Story 6 closes the degraded-mode behavior.

Stories 2–5 touch the same orchestration and could conflict if developed concurrently; implement them sequentially. The plan favors deterministic ordering tests over a fixed latency SLA because CI timing is noisy. If production telemetry is desired later, add elapsed-time logging around discovery and enrichment rather than encoding a brittle `100 ms` test.

## Build acceptance criterion

`./gradlew clean build` passes after every story.
