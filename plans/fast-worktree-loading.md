# Fast worktree loading

**Goal:** Show locally discovered worktrees promptly even when local status or remote metadata is slow, then update each row without blocking other repositories.

## Context

- Source: `README.md:19-20`. Scope is the Eng Hub Worktrees view on startup, manual expansion, adding a repository, periodic polling, and post-mutation refresh; not existing-branch discovery or checkout.
- **Decision — first paint:** Publish branch/path rows as soon as local `git worktree list` finishes, without waiting for any `git status`. Dirty state is initially unknown; controls requiring a definite clean/dirty state must wait. This prioritizes usable discovery over immediate status-dependent controls. Confirmed by user.
- **Decision — remote freshness:** Keep the current server-authoritative default-branch lookup on each refresh, but move it off the local-row critical path and decouple it from polling. Consider a bounded timeout only if tests show background work accumulating or starving refreshes. This avoids subtly changing origin actions when a cached HEAD is stale. Confirmed by user.
- **Decision — loading:** Stop the worktree-list spinner once local branch/path rows appear. Show separate metadata progress only if it proves useful; do not suggest that undiscovered rows are still being loaded. Confirmed by user.
- **Decision — unknown dirty status:** Show `Checking status…` on the row, and disable status-dependent actions until the local check resolves. Unknown must not be represented as clean. Confirmed by user.
- **Decision — offline refresh:** Keep last known parent/default-branch metadata for the same path and branch when a later lookup is blocked or fails, without presenting it as freshly verified. New rows start without remote-dependent actions. Confirmed by user.
- Configured repositories start expanded and poll immediately on a background IO coroutine (`eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModel.kt`, `ConfigDrivenPolling.kt`). Polling loops over repositories serially and waits for enrichment before visiting the next (`viewmodel/LocalRepositoryController.kt`).
- Expansion and refresh already publish basic rows before enrichment, but read `originUrl` before listing/publishing; add-repository likewise reads origin before publishing resolved worktrees (`viewmodel/LocalRepositoryController.kt`). `originUrl` reads local Git configuration, not the network (`utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitWorktreeService.kt`, `GitOriginUrlResolver`). Move it off the first-paint path regardless.
- `GitWorktreeLister.listWorktrees` combines `git worktree list` with sequential `git status` calls for each checkout (`utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitWorktreeService.kt`). `GitWorktreeDiscoveryApi.listWorktrees` currently exposes only the combined result (`utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitWorktreeApi.kt`). `LocalWorktreeUiState.isDirty` is boolean (`eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/state/LocalRepositoryUiState.kt`); unknown status must not be silently presented as clean.
- Enrichment computes parent branches, rebase needs, and origin-dependent actions (`eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/LocalWorktreeStateMappers.kt`). `inferOriginDefaultBranch` asks the server first (`utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitWorktreeService.kt`, `GitDefaultBranchRefResolver`). `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/LocalWorktreeStateMappersTest.kt` asserts server HEAD wins over stale local HEAD.
- `LocalRepositoryExpansionTracker` and `LocalRepositoryRefreshTracker` use request tokens; refreshed rows retain prior enrichment when path/branch matches (`viewmodel/LocalRepositoryExpansionTracker.kt`, `LocalRepositoryController.kt`, `LocalWorktreeStateMappers.kt`). Rows already render while `isLoading` is true (`component/WorktreeRepositoryRows.kt`). Existing concurrency tests live in `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubLocalRepositoryViewModelTest.kt` and `LocalRepositoryTrackerTest.kt`.
- This is planning only. No timing measurements or code changes have been made. Validate with controlled blocked-local-status and blocked-remote tests, then measure real first-paint latency before setting a numerical budget.

## Acceptance tests (one per story)

1. Given `api` has worktrees `main` and `feature/login`, when local status for `main` blocks, then both branch/path rows appear after `git worktree list` completes, each shows `Checking status…`, and the worktree-list spinner stops.
2. Given visible `feature/login` has unknown dirty status, when its local status returns dirty, then its row shows dirty status and status-dependent controls become available according to the existing dirty-worktree rules.
3. Given configured `api` has local `feature/login`, when server default-branch lookup blocks, then its row appears with local status without waiting for the server.
4. Given configured `api` and `web`, when `api`'s remote lookup blocks, then `web`'s `feature/nav` appears without waiting for `api`.
5. Given visible `feature/login` without origin metadata, when the server reports default branch `main`, then its origin-dependent controls update without replacing the row.
6. Given `feature/login` is replaced by `feature/logout` while metadata is in flight, when the old lookup completes, then `feature/logout` remains visible without metadata from `feature/login`.

## Stories

### 1. Display branch rows before local status completes

**Status:** Done — merged in [PR #7](https://github.com/karlsabo/dev-lake-utils/pull/7).

**Acceptance criteria:** Given `api` has `main` and `feature/login`, when `git status` for `main` blocks, then both worktree branch/path rows are visible after `git worktree list` completes, each shows `Checking status…` with status-dependent actions disabled, and the worktree-list spinner stops.

**Expected edits:** `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitWorktreeApi.kt`, `GitWorktreeService.kt`; `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/state/LocalRepositoryUiState.kt`, `viewmodel/LocalRepositoryController.kt`, `LocalRepositoryExpansionTracker.kt`, `component/WorktreeRepositoryRows.kt`; corresponding service, view-model, and component tests under `utilities/src/commonTest/` and `eng-hub/src/commonTest/`.

**Scope:** Startup, expansion, add, poll, and post-mutation refresh all use an entries-only discovery path. Publish unknown status explicitly and stop discovery loading on publish. No parallel status scans or origin metadata in this PR.

**Notes:** Add a local entries-only API instead of changing `listWorktrees` semantics for other callers; `GitWorktreeLister.listWorktreeEntries` already exists internally. For add-repository, `resolveRepositoryRoot` currently returns status-enriched `RepositoryWorktrees` (`utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitWorktreeService.kt`), so its first-paint path must also be split. Keep the existing `listWorktrees` contract for non-view clients. Treat unknown as neither clean nor dirty; show `Checking status…` and disable controls that require a definite status (check archive/update/rebase/merge menus in `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeWorktreeRows.kt`, `WorktreeActionMenu.kt`, and `WorktreeShortcuts.kt`). Do not wait for status or local origin identity before publishing rows.

### 2. Fill in dirty status after branch discovery

**Status:** Done — merged in [PR #8](https://github.com/karlsabo/dev-lake-utils/pull/8).

**Acceptance criteria:** Given visible `feature/login` has unknown dirty status, when its local status returns dirty, then the row shows dirty status and the existing dirty-worktree action rules apply.

**Expected edits:** `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitWorktreeApi.kt`, `GitWorktreeService.kt`; `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/state/LocalRepositoryUiState.kt`, `viewmodel/LocalRepositoryController.kt`, `LocalRepositoryExpansionTracker.kt`, `LocalWorktreeStateMappers.kt`; worktree service/view-model/component tests under `utilities/src/commonTest/` and `eng-hub/src/commonTest/`.

**Scope:** Asynchronous local status hydration and correct merging by normalized path plus branch; preserve existing semantics when status commands fail (currently treated as dirty in `GitWorktreeLister`). Excludes changing status polling interval.

**Notes:** Run status work on view-model-owned IO jobs. Do not mark the entire repository as loading again. Recheck request ownership before applying late status; a checkout may have been removed or replaced. Story 1's unknown state must remain safe if this job never completes.

### 3. Keep remote lookup off the local-row path

**Status:** Done — merged in [PR #10](https://github.com/karlsabo/dev-lake-utils/pull/10).

**Acceptance criteria:** Given configured `api` has local `feature/login`, when server default-branch lookup hangs, then its row appears with local status without waiting for that lookup.

**Expected edits:** `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/LocalRepositoryController.kt`, `LocalRepositoryExpansionTracker.kt`, possibly `EngHubViewModel.kt`; tests in `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubLocalRepositoryViewModelTest.kt`.

**Scope:** All five entry paths named in Context. Separate local discovery from origin URL/identity and remote enrichment; keep the current server-HEAD precedence. No changes to existing-branch discovery or Git fetch.

**Notes:** Expansion/refresh already publish before enrichment; decouple their job lifetimes and make add-repository follow the same rule. Use view-model-owned IO coroutines, not unmanaged threads. Polling must not wait for a blocked metadata call. Avoid repeated unbounded lookups while offline.

### 4. Load independent repositories concurrently

**Status:** Done — merged in [PR #11](https://github.com/karlsabo/dev-lake-utils/pull/11).

**Acceptance criteria:** Given configured `api` and `web`, when `api`'s remote lookup blocks, then `web`'s local `feature/nav` appears before `api`'s lookup completes.

**Expected edits:** `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/LocalRepositoryController.kt`, `ConfigDrivenPolling.kt` if polling ownership changes, and `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubLocalRepositoryViewModelTest.kt`.

**Scope:** Independent per-repository scheduling with bounded overlap; not parallelizing each Git command or spawning an unbounded job on every poll.

**Notes:** Preserve cancellation on view-model disposal/config change; coalesce per-repo refreshes. Keep updates keyed by normalized repo path. In a blocked-local-discovery test as well, decide whether cross-repo independence requires each repository's discovery to run separately (recommended).

### 5. Apply late origin metadata to visible rows

**Status:** Done — merged in [PR #12](https://github.com/karlsabo/dev-lake-utils/pull/12).

**Acceptance criteria:** Given visible `feature/login` without origin metadata, when server HEAD resolves to `main`, then `feature/login` gains its applicable origin-dependent action without disappearing.

**Expected edits:** `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/LocalWorktreeStateMappers.kt`, `LocalRepositoryController.kt`, `LocalRepositoryExpansionTracker.kt`, and tests in `EngHubLocalRepositoryViewModelTest.kt` and `LocalWorktreeStateMappersTest.kt`.

**Scope:** Merge background metadata into current rows. Excludes changing action semantics or default-branch source.

**Notes:** Use the existing `withEnrichmentFrom` path/branch check. Integrate with status hydration without overwriting its newer `isDirty` value; preserve server-authoritative HEAD behavior. During an offline refresh retain previously known metadata only for matching path and branch, without a fresh-verification claim; new rows must not inherit unrelated metadata.

### 6. Discard stale background results

**Status:** Done — merged in [PR #13](https://github.com/karlsabo/dev-lake-utils/pull/13).

**Acceptance criteria:** Given `feature/login` has been replaced by `feature/logout` while metadata is in flight, when the older lookup returns, then `feature/logout` remains visible without `feature/login`'s metadata.

**Expected edits:** `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/LocalRepositoryExpansionTracker.kt`, `LocalRepositoryController.kt`, `LocalWorktreeStateMappers.kt`; tests in `LocalRepositoryTrackerTest.kt` and `EngHubLocalRepositoryViewModelTest.kt`.

**Scope:** Stale status, origin, and enrichment responses after refresh/reconfiguration/collapse; excludes retaining removed worktrees.

**Notes:** Keep request-token checks plus path/branch matching. If earlier stories and existing tests already prove this acceptance test, remove this separate story rather than shipping a test-only PR.

## Sequence and trade-offs

Start with Story 1 (the agreed first-paint guarantee); follow with status hydration (2), remote decoupling (3), and cross-repository scheduling (4). Validate late enrichment and stale-result behavior with Stories 5–6 only where preceding work does not already demonstrate them. The entries-only API and explicit unknown status add surface area but avoid presenting unchecked worktrees as clean. Structured background jobs improve perceived latency at the cost of scheduling complexity; bound per-repo overlap and validate request ownership. A blocked remote should never prevent local rows or a second repo from appearing; a blocked local status should never prevent branch/path rows from appearing. Run repository-required `./gradlew clean build` after implementation PRs, not for this doc-only plan.
