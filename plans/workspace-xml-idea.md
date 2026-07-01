# Workspace XML handling for `idea-tool.kts`

**Goal:** Make `scripts/idea-tool.kts` seed useful IntelliJ `workspace.xml` settings into worktrees while avoiding copied project identity, session state, shelves, and other volatile IDE state.

**Context:**

- `README.md:21-22` already tracks the desired behavior: keep variables such as `GOPRIVATE` instead of skipping `workspace.xml` wholesale.
- `scripts/idea-tool.kts:91-101` copies `.idea` files unless `isVolatileIdeaPath` rejects them; `scripts/idea-tool.kts:222` currently marks `workspace.xml` volatile, so the source workspace is never copied.
- `scripts/idea-tool.kts:107-126` and `scripts/idea-tool.kts:332-333` currently create or patch the target `workspace.xml` only to enforce Go readonly settings. That preserves `GOFLAGS=-mod=readonly` but loses source environment entries such as `GOPRIVATE`.
- `eng-hub/README.md:171-175` documents the valid reason for the old exclusion: `workspace.xml` can contain project identity, window/session state, shelves, and personal state from another checkout.
- The checked local examples confirm both sides of the trade-off:
  - `/Users/karl.sabo/Klaviyo/Repos/k-repo/.idea/workspace.xml:2500-2505` has `VgoProject` with `GOFLAGS=-mod=readonly` and `GOPRIVATE=github.com/klaviyo`, which should be preserved for worktrees.
  - `/Users/karl.sabo/Klaviyo/Repos/app/.idea/workspace.xml:125`, `/Users/karl.sabo/Klaviyo/Repos/k-repo/.idea/workspace.xml:183`, and `/Users/karl.sabo/Klaviyo/Repos/fender/.idea/workspace.xml:40` contain `ProjectId`, which should not be copied as-is between worktrees.
  - `/Users/karl.sabo/Klaviyo/Repos/app/.idea/workspace.xml:6`, `/Users/karl.sabo/Klaviyo/Repos/k-repo/.idea/workspace.xml:6`, and `/Users/karl.sabo/Klaviyo/Repos/fender/.idea/workspace.xml:6` contain `ChangeListManager`, another worktree-local section.
  - `/Users/karl.sabo/Klaviyo/Repos/app/.idea/workspace.xml:182`, `/Users/karl.sabo/Klaviyo/Repos/k-repo/.idea/workspace.xml:272`, and `/Users/karl.sabo/Klaviyo/Repos/fender/.idea/workspace.xml:83` contain `RunManager`; `k-repo` in particular keeps many Go run configurations there, so excluding all of `workspace.xml` removes useful project behavior from worktrees.
  - `/Users/karl.sabo/Klaviyo/Repos/app/.idea/.gitignore:1-3`, `/Users/karl.sabo/Klaviyo/Repos/k-repo/.idea/.gitignore:1-3`, and `/Users/karl.sabo/Klaviyo/Repos/fender/.idea/.gitignore:1-3` all ignore `workspace.xml`, so the helper is the practical way to seed these local settings.

## Proposed approach

Treat `workspace.xml` as a special file, not as a normal copied file and not as a fully volatile file.

Use a **sanitized-copy** strategy rather than a narrow allowlist. For this tool, the source and target are usually same-user, same-machine worktrees, and the bigger observed failure mode is broken workspaces from missing IntelliJ state. Copying most components while removing known-bad local identity/session sections is the better trade-off here.

Why not a strict allowlist?

- **Pros:** safer against newly introduced IntelliJ session components; easier to prove we are not copying local identity.
- **Cons:** creates a long whack-a-mole loop where worktrees are broken until each useful component is rediscovered and re-added.

Why sanitized copy?

- **Pros:** preserves useful IDE behavior up front, including Go environment, run configurations, and repo/plugin settings that are hard to enumerate ahead of time.
- **Cons:** may copy some mildly stale UI state until a denylist entry is added.

Decision: use sanitized copy because this is a local worktree bootstrap tool, not a shared committed project template. Keep the denylist explicit and easy to extend.

Initial sections to remove from the copied source workspace:

- `ProjectId`
- `ChangeListManager`
- `TaskManager`
- `VcsManagerConfiguration`
- `Vcs.Log.Tabs.Properties`
- `GitHubPullRequestSearchHistory`, `GitHubPullRequestState`, `GithubPullRequestsUISettings`
- UI/session state that has proven noisy or identity-like, starting with `ProjectViewState`, `PropertiesComponent`, `RecentsManager`, `XDebuggerManager`, and `ReactDesignerToolWindowState`

Important sections to preserve unless they cause concrete problems:

- `VgoProject`: Go module environment, including `GOFLAGS`, `GOPRIVATE`, and automatic dependency download settings.
- `RunManager`: reusable run configurations that IntelliJ stores in `workspace.xml` for these repos.
- Repo/plugin settings such as `GOROOT`, `GoLibraries`, and `FlaskConsoleOptions` should be kept for now, then denied later only if they cause observed worktree breakage.

Refresh behavior:

- For a new target worktree, write a sanitized copy of the source `workspace.xml` after source-root-to-target-root rewriting.
- For an existing target worktree, merge sanitized source components into the target workspace while preserving target-local denylisted components.
- Merge `RunManager` configurations by stable identity rather than replacing the whole component, so target-only run configurations can survive refresh.
- Continue enforcing `GOFLAGS` so the final `VgoProject` always includes `-mod=readonly`; do not drop other source environment entries such as `GOPRIVATE`.
- Preserve the existing unchanged-file optimization: if the computed target bytes are identical, do not rewrite the file.

## Acceptance tests

1. **Go environment is preserved for a new worktree**
   - Given a source `.idea/workspace.xml` with `VgoProject` entries `GOFLAGS=-mod=mod` and `GOPRIVATE=github.com/klaviyo`, when `idea-tool.kts` initializes an empty target `.idea`, then the target `workspace.xml` contains `GOPRIVATE=github.com/klaviyo` and `GOFLAGS` includes `-mod=readonly`.

2. **Source project identity is not copied into a new worktree**
   - Given a source `.idea/workspace.xml` with `ProjectId`, `ChangeListManager`, and `TaskManager`, when `idea-tool.kts` initializes an empty target `.idea`, then the target `workspace.xml` does not contain those source components.

3. **Workspace run configurations are available in a new worktree**
   - Given a source `.idea/workspace.xml` with a `RunManager` configuration named `go build github.com/klaviyo/k-repo/go/apis/health_checker/cmd/health_checker`, when `idea-tool.kts` initializes an empty target `.idea`, then that run configuration appears in the target worktree's `workspace.xml`.

4. **Refreshing an existing worktree merges source settings without clobbering target-local state**
   - Given a target `.idea/workspace.xml` with its own `ChangeListManager`, its own `RunManager` configuration, a conflicting `RunManager` configuration with the same identity as the source, and a stale `VgoProject` missing `GOPRIVATE`, when `idea-tool.kts` refreshes it from a source workspace containing `GOPRIVATE`, a different `RunManager` configuration, and the conflicting source run configuration, then the target `VgoProject` is updated, the non-conflicting source and target run configurations both exist, the conflicting target run configuration wins, and the target `ChangeListManager` remains unchanged.

5. **Workspace root paths are rewritten during sanitized copy**
   - Given a source `workspace.xml` preserved component containing the source repository root path, when `idea-tool.kts` writes the target `workspace.xml`, then that path is rewritten to the target repository root.

6. **Docs explain the workspace policy**
   - Given a developer reads the Eng Hub IntelliJ helper docs, when they look for `workspace.xml` behavior, then the docs say which workspace components are copied, which state is intentionally local, and why `GOPRIVATE` is preserved.

## Stories

### 1. Preserve Go workspace environment from the source template

**Acceptance criteria:** Given a source `.idea/workspace.xml` with `VgoProject` entries `GOFLAGS=-mod=mod` and `GOPRIVATE=github.com/klaviyo`, when `idea-tool.kts` initializes an empty target `.idea`, then the target `workspace.xml` contains `GOPRIVATE=github.com/klaviyo` and `GOFLAGS` includes `-mod=readonly`.

**Expected edits:**

- `scripts/idea-tool.kts`
- Add script-level test fixtures or a small temp-dir test harness if no existing test harness is practical.

**Scope:**

- Stop treating `workspace.xml` as a simple top-level volatile skip for the `VgoProject` case.
- Read `VgoProject` from the source workspace when present.
- Apply the existing readonly `GOFLAGS` policy to the copied component.
- If the source has no `VgoProject`, keep the current behavior of creating a minimal readonly component.

**Out of scope:**

- Copying `RunManager`.
- Cleaning old volatile components out of existing worktrees.

**Notes:**

- Current loss point is `scripts/idea-tool.kts:222`, where `workspace.xml` is skipped before source content can be inspected.
- Keep this as the tracer bullet: it solves the explicit `GOPRIVATE` problem without deciding every workspace component up front.

### 2. Do not copy source-local workspace identity/session sections

**Acceptance criteria:** Given a source `.idea/workspace.xml` with `ProjectId`, `ChangeListManager`, and `TaskManager`, when `idea-tool.kts` initializes an empty target `.idea`, then the target `workspace.xml` does not contain those source components.

**Expected edits:**

- `scripts/idea-tool.kts`
- Test fixture for a source workspace containing both allowed and forbidden components.

**Scope:**

- Introduce explicit workspace component filtering.
- Prefer sanitized copy with an explicit denylist over a narrow allowlist.
- Ensure the new target workspace excludes known source-local identity/session components while keeping useful workspace settings.

**Out of scope:**

- Removing target-local components from an existing target workspace.
- Deciding every possible IntelliJ workspace component up front.

**Notes:**

- This preserves the reason documented in `eng-hub/README.md:171-175`; the fix should not regress into blindly copying the whole source workspace.

### 3. Copy reusable workspace run configurations

**Acceptance criteria:** Given a source `.idea/workspace.xml` with a `RunManager` configuration named `go build github.com/klaviyo/k-repo/go/apis/health_checker/cmd/health_checker`, when `idea-tool.kts` initializes an empty target `.idea`, then that run configuration appears in the target worktree's `workspace.xml`.

**Expected edits:**

- `scripts/idea-tool.kts`
- Workspace fixture with `RunManager`.

**Scope:**

- Preserve `RunManager` during sanitized workspace copy.
- Preserve run configuration content after root-path rewriting.
- Keep source-local components excluded.

**Out of scope:**

- Migrating run configurations from `workspace.xml` into `.idea/runConfigurations`.
- Perfectly classifying every IntelliJ run configuration type.

**Notes:**

- `/Users/karl.sabo/Klaviyo/Repos/k-repo/.idea/workspace.xml:272` shows `RunManager` in the file that is currently skipped; observed local data showed `k-repo` has many Go configurations there, which is likely part of the worktree pain.
- Use the later merge story to avoid wholesale `RunManager` replacement on refresh.

### 4. Merge sanitized source workspace components into existing target workspaces

**Acceptance criteria:** Given a target `.idea/workspace.xml` with its own `ChangeListManager`, its own `RunManager` configuration, a conflicting `RunManager` configuration with the same identity as the source, and a stale `VgoProject` missing `GOPRIVATE`, when `idea-tool.kts` refreshes it from a source workspace containing `GOPRIVATE`, a different `RunManager` configuration, and the conflicting source run configuration, then the target `VgoProject` is updated, the non-conflicting source and target run configurations both exist, the conflicting target run configuration wins, and the target `ChangeListManager` remains unchanged.

**Expected edits:**

- `scripts/idea-tool.kts`
- Test fixture with separate source and existing target workspaces.

**Scope:**

- Merge sanitized source components into the target workspace.
- Preserve target components that are intentionally local or not present in the sanitized source.
- Merge `RunManager` configurations by stable identity, likely `(type, name, folderName)`, rather than replacing the full component.
- Keep the unchanged-file optimization.

**Out of scope:**

- Deleting target-local stale state copied by old helper versions.
- Sophisticated conflict resolution when source and target have the same run configuration identity but different content; target wins for this first pass.

**Notes:**

- This keeps repeated setup commands safe before `idea ./`, matching the current refresh contract in `eng-hub/README.md:177-178`.

### 5. Rewrite source root paths inside sanitized workspace components

**Acceptance criteria:** Given a source `workspace.xml` preserved component containing the source repository root path, when `idea-tool.kts` writes the target `workspace.xml`, then that path is rewritten to the target repository root.

**Expected edits:**

- `scripts/idea-tool.kts`
- Fixture with an absolute source repo path inside a preserved component.

**Scope:**

- Reuse the existing exact string replacement semantics from normal copied UTF-8 files.
- Apply rewriting to sanitized source workspace content before it is written or merged.

**Out of scope:**

- Fuzzy path rewriting.
- Rewriting unrelated user-home paths such as SDK locations.

**Notes:**

- Current normal file behavior lives in `scripts/idea-tool.kts:231-322`; workspace handling should not invent a different path rewrite policy.

### 6. Document the new `workspace.xml` policy

**Acceptance criteria:** Given a developer reads the Eng Hub IntelliJ helper docs, when they look for `workspace.xml` behavior, then the docs say which workspace components are copied, which state is intentionally local, and why `GOPRIVATE` is preserved.

**Expected edits:**

- `eng-hub/README.md`
- Maybe `README.md` TODO cleanup after implementation lands.

**Scope:**

- Update the helper description from "does not copy workspace.xml" to "copies a sanitized workspace.xml".
- Document removed sections, preserved sections, and intentionally local target state.
- Keep the warning about shelves, datasource local state, and project identity.

**Out of scope:**

- User-facing UI changes in Eng Hub.

## Answered questions

- **Should `RunManager` be replaced wholesale on refresh, or should configurations be merged by name to preserve target-only temporary configs?**
  - Merge by run configuration identity rather than replacing the whole `RunManager` component.
  - Use a simple stable key first: `(type, name, folderName)`. If IntelliJ stores a better explicit ID for some configuration types, use it where present, but do not overbuild this.
  - Target wins when source and target have the same key. Most worktrees are new, so conflicts should be rare, and this avoids surprising users who changed a target worktree config.
  - Copy source configurations with `temporary="true"`; temporary configs are still useful in a fresh worktree.
  - Before comparing or writing, rewrite exact source repository root paths to the target repository root so copied configurations do not point back at the root checkout.

- **Should any other workspace components be allowlisted now (`GOROOT`, `GoLibraries`, `FlaskConsoleOptions`), or should they wait for concrete failing examples?**
  - Do not use a narrow allowlist. Keep most source workspace components and selectively remove known-bad identity/session sections.
  - The case for an allowlist is safety: less chance of copying unknown local IDE state, easier to reason about project identity, fewer surprises from future IntelliJ plugin components.
  - The case against an allowlist is stronger here: the source and target are same-user worktrees, `workspace.xml` is ignored by all three checked repos, and the current failure is broken worktrees from missing useful workspace state. An allowlist creates a repeated rediscovery loop.
  - Decision: sanitized copy with a denylist. Keep `GOROOT`, `GoLibraries`, `FlaskConsoleOptions`, and similar repo/plugin settings for now. Add a denylist entry later only when there is concrete breakage.

- **Do we want a cleanup mode for old worktrees that already copied full source `workspace.xml`, or is "avoid copying going forward" enough?**
  - Avoid copying bad state going forward is enough for this plan.
  - Do not add cleanup mode now. If stale copied source identity becomes a recurring issue, make it a separate story with dry-run output before deleting anything.

- **Should source and target run configurations with the same `(type, name, folderName)` key be treated as "source always wins", or should target win when the target version differs?**
  - Target wins. Most worktrees are new, so this rarely matters, but preserving target-local user edits is less surprising when it does.

- **Should `temporary="true"` run configurations from the source be copied, or should the sanitizer drop temporary source run configs?**
  - Copy temporary source run configurations. They are still useful in a fresh worktree.

- **Is stable pretty-printed XML acceptable if it changes formatting, or should the implementation preserve original workspace formatting as much as practical?**
  - Stable pretty-printed XML is acceptable. Use an XML library if that makes parsing and output correct.

## New questions

- Should `Git.Settings`, `Git.Merge.Settings`, and `Git.Rebase.Settings` be denylisted? In the sampled repos they mostly store recent branch/base choices from the source checkout, which seems more like local session state than useful project setup.
- Should cache/telemetry-like components such as `EmbeddingIndexingInfo`, `SharedIndexes`, and `NextEditCompletionFeaturesState` be denylisted now, or kept under the sanitized-copy policy until they cause real trouble?
- Should the denylist be only explicit component names, or should it also drop broad patterns such as names ending in `State`, `History`, or `Info`? I recommend explicit names only; broad patterns would delete useful unknowns and drift back toward an allowlist.
- If the source `workspace.xml` is malformed XML, should the helper fail loudly, or should it fall back to creating the minimal readonly `VgoProject` workspace?
