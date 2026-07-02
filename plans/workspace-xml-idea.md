# Robust `.idea` seeding for `idea-tool.kts`

**Goal:** Make `scripts/idea-tool.kts` seed useful IntelliJ `.idea` settings into new worktrees without copying project identity/session state, shelves, or overwriting existing worktree IDE state.

**Context:**

- `README.md:21-22` tracks the original symptom: we should keep variables such as `GOPRIVATE` instead of skipping `workspace.xml` wholesale.
- `scripts/idea-tool.kts:91-101` currently walks source `.idea` and copies files unless `isVolatileIdeaPath` rejects them.
- `scripts/idea-tool.kts:222-229` currently blanket-skips `workspace.xml`, `shelf/`, `dataSources/`, `dataSources.xml`, `dataSources.local.xml`, `tasks.xml`, and `usage.statistics.xml`. This caused broken worktree setup because useful files/sections were skipped without per-file thought.
- `scripts/idea-tool.kts:107-126` and `scripts/idea-tool.kts:332-333` currently create or patch target `workspace.xml` only to enforce Go readonly settings. That preserves `GOFLAGS=-mod=readonly` but loses source environment entries such as `GOPRIVATE`.
- `eng-hub/README.md:171-175` documents the valid reason for caution: `workspace.xml` can contain project identity, window/session state, shelves, and personal state from another checkout.
- Local repo evidence:
  - `/Users/karl.sabo/Klaviyo/Repos/k-repo/.idea/workspace.xml:2500-2505` has `VgoProject` with `GOFLAGS=-mod=readonly` and `GOPRIVATE=github.com/klaviyo`; this is useful worktree setup and should be seeded when target `workspace.xml` is missing.
  - `/Users/karl.sabo/Klaviyo/Repos/app/.idea/workspace.xml:125`, `/Users/karl.sabo/Klaviyo/Repos/k-repo/.idea/workspace.xml:183`, and `/Users/karl.sabo/Klaviyo/Repos/fender/.idea/workspace.xml:40` contain `ProjectId`; this should not be copied as-is between worktrees.
  - `/Users/karl.sabo/Klaviyo/Repos/app/.idea/workspace.xml:6`, `/Users/karl.sabo/Klaviyo/Repos/k-repo/.idea/workspace.xml:6`, and `/Users/karl.sabo/Klaviyo/Repos/fender/.idea/workspace.xml:6` contain `ChangeListManager`; this is worktree-local state.
  - `/Users/karl.sabo/Klaviyo/Repos/app/.idea/workspace.xml:182`, `/Users/karl.sabo/Klaviyo/Repos/k-repo/.idea/workspace.xml:272`, and `/Users/karl.sabo/Klaviyo/Repos/fender/.idea/workspace.xml:83` contain `RunManager`; `k-repo` keeps many Go run configurations there, so skipping all of `workspace.xml` removes useful IDE behavior.
  - `/Users/karl.sabo/Klaviyo/Repos/app/.idea/.gitignore:1-3`, `/Users/karl.sabo/Klaviyo/Repos/k-repo/.idea/.gitignore:1-3`, and `/Users/karl.sabo/Klaviyo/Repos/fender/.idea/.gitignore:1-3` ignore `workspace.xml`, so the helper is the practical place to seed it.
  - `/Users/karl.sabo/Klaviyo/Repos/app/.idea/dataSources.xml` and `/Users/karl.sabo/Klaviyo/Repos/k-repo/.idea/dataSources.xml` contain datasource definitions that can make new worktrees more usable.
  - `/Users/karl.sabo/Klaviyo/Repos/app/.idea/dataSources.local.xml` and `/Users/karl.sabo/Klaviyo/Repos/k-repo/.idea/dataSources.local.xml` contain datasource local metadata; same-user/same-machine first-run seeding is acceptable, but existing target state must win.
  - `/Users/karl.sabo/Klaviyo/Repos/k-repo/.idea/shelf/Uncommitted_changes_before_Update_at_4_27_26,_12_08 PM_[Changes]/shelved.patch` contains actual shelved code changes; shelves must not be copied into another worktree.

## Core behavior

This helper should be described and implemented as a **seeding** tool, not a refresh tool.

Rules:

1. **Target-first:** if the target file or symlink exists, do nothing to it and move to the next source path. Do not overwrite, merge, parse, format, repair, replace, or validate existing target files.
2. **Directory recursion:** if the target directory exists, recurse into it and copy only missing child files. Never overwrite existing child files.
3. **Missing target:** if the target path is missing, apply that path's explicit policy and copy/sanitize from source when appropriate.
4. **Type conflicts:** if source and target types conflict at a relative path, skip that path and continue.
5. **Symlinks:** never follow symlinks. If a source symlink is copied to a missing target path, preserve it as a symlink and do not rewrite the symlink target.
6. **Directories:** create parent directories only on demand for copied files/symlinks. Do not create empty source directories just because they exist.
7. **Attributes:** preserve source file attributes where possible, matching the current `COPY_ATTRIBUTES` intent.
8. **Path rewriting:** for copied regular UTF-8 text files, keep the existing exact source-root-to-target-root replacement behavior. Do not rewrite binary files or symlink targets.
9. **XML parsing:** only parse `workspace.xml`, because only `workspace.xml` needs section sanitization. Other XML files are copied as text/binary with root-path rewriting if applicable; malformed XML outside `workspace.xml` is irrelevant.
10. **No success summary:** do not print a normal success summary. Setup-command noise is not useful. Errors should still be clear.
11. **No force mode:** do not add `--force` in this plan. Users can delete specific target paths when they intentionally want to reseed them.
12. **Validation stays:** if target `.idea` exists as a file instead of a directory, keep the current validation failure.
13. **No synthetic workspace from scratch:** if source `workspace.xml` is missing, do not invent one. Copy the rest of `.idea` and continue.

## `workspace.xml` policy

`workspace.xml` is a special first-copy hybrid file:

- If target `workspace.xml` exists, skip it entirely, even if it is malformed.
- If target `workspace.xml` is missing and source `workspace.xml` is missing, do nothing for workspace seeding.
- If target `workspace.xml` is missing and source `workspace.xml` exists, parse source with an XML library, sanitize components, rewrite source root paths in copied content, and write target `workspace.xml`.
- If source `workspace.xml` is malformed while target `workspace.xml` is missing, fail loudly with a clear error.
- Use an XML library for mutation/output. Stable pretty-printed XML is acceptable. Component order does not matter.
- Remove raw string XML construction such as the current minimal generated workspace/component snippets in `scripts/idea-tool.kts:112-116` and `scripts/idea-tool.kts:204-211`.
- If the copied source `workspace.xml` has `VgoProject`, ensure that existing component includes `GOFLAGS=-mod=readonly`; do not drop other source environment entries such as `GOPRIVATE`.
- Do not synthesize an entire missing `VgoProject` component. The helper seeds from source and sanitizes what exists; it does not invent workspace XML from scratch.
- If an existing source `VgoProject` needs child XML elements to enforce `GOFLAGS=-mod=readonly`, creating those child elements with the XML library is acceptable. Raw string XML construction is not.

Denylist these source `workspace.xml` components during first-copy sanitization:

- `ProjectId`
- `ChangeListManager`
- `TaskManager`
- `VcsManagerConfiguration`
- `Vcs.Log.Tabs.Properties`
- `Git.Settings`
- `Git.Merge.Settings`
- `Git.Rebase.Settings`
- `GitHubPullRequestSearchHistory`
- `GitHubPullRequestState`
- `GithubPullRequestsUISettings`
- `EmbeddingIndexingInfo`
- `SharedIndexes`
- `NextEditCompletionFeaturesState`
- `HighlightingSettingsPerFile`
- `ChangesViewManager`
- `ProjectViewState`
- `PropertiesComponent`
- `RecentsManager`
- `XDebuggerManager`
- `ReactDesignerToolWindowState`
- `FileTemplateManagerImpl`
- `ProjectColorInfo`
- `TypeScriptGeneratedFilesManager`
- `XSLT-Support.FileAssociations.UIState`
- `KubernetesApiPersistence`

Preserve useful/non-denied source `workspace.xml` components, including:

- `VgoProject` with Go environment such as `GOFLAGS` and `GOPRIVATE`
- `RunManager`, including configurations marked `temporary="true"`
- `GOROOT`
- `GoLibraries`
- `FlaskConsoleOptions`
- `KubernetesApiProvider`
- Other components not explicitly denied; use explicit component names only, not broad patterns such as suffixes ending in `State`, `History`, or `Info`.

## File policy matrix

This matrix replaces the broad `volatileIdeaTopLevelPaths` skip set with explicit decisions.

| Path | Policy when target is missing | Policy when target exists | Why |
| --- | --- | --- | --- |
| `workspace.xml` | Sanitized first copy from source; root-path rewrite; enforce readonly `GOFLAGS` only inside existing source `VgoProject`; fail if source XML is malformed. If source is missing, do nothing. | Preserve target unchanged. | Seeds useful Go/run configuration settings without copying identity/session state or mutating established worktrees. |
| `shelf/` | Never copy. | Preserve target unchanged. | Shelves contain actual code patches from another checkout. |
| `dataSources.xml` | Copy from source with root-path rewriting. | Preserve target unchanged. | Seeds datasource definitions for new worktrees. |
| `dataSources.local.xml` | Copy from source with root-path rewriting. | Preserve target unchanged. | Same-user/same-machine first-run seeding may need the companion local file. Existing target state wins. |
| `dataSources/` | Recurse and copy missing files. | Preserve existing files; copy missing children. | Datasource caches/history match copied datasource UUIDs and can improve first-run IDE behavior. Never overwrite existing cache files. |
| `tasks.xml` | Copy from source if present. | Preserve target unchanged. | Default missing-target copy rule applies. |
| `usage.statistics.xml` | Never copy. | Preserve target unchanged. | Telemetry/usage state is not project setup. |
| `.gitignore` | Copy from source with root-path rewriting if applicable. | Preserve target unchanged. | IntelliJ's generated ignore file should be seeded when missing, even if it ignores files this helper seeds. |
| Unknown file under `.idea` | Copy from source with root-path rewriting if applicable. | Preserve target unchanged. | Avoid broken worktrees from missing future plugin/project settings. |
| Unknown directory under `.idea` | Recurse and copy missing files unless path is explicitly never-copy. | Preserve existing files; copy missing children. | Consistent target-missing seeding at file granularity; never overwrite existing files. |

For observed non-special `.idea` files (`*.iml`, `misc.xml`, `modules.xml`, `vcs.xml`, `go.imports.xml`, `prettier.xml`, `tinygoSettings.xml`, `pySourceRootDetection.xml`, `sqldialects.xml`, code style files, inspection profiles, run configurations, JS/TS linter/compiler settings), use normal target-missing copy with exact root-path rewriting. Existing target files are always preserved.

## Acceptance tests

1. **Go environment is preserved for a new worktree**
   - Given source `.idea/workspace.xml` has an existing `VgoProject` with `GOFLAGS=-mod=mod` and `GOPRIVATE=github.com/klaviyo`, and target `.idea/workspace.xml` is missing, when `idea-tool.kts` runs, then target `workspace.xml` contains `GOPRIVATE=github.com/klaviyo` and `GOFLAGS` includes `-mod=readonly`.

2. **Source project identity is not copied into a new worktree**
   - Given source `.idea/workspace.xml` has `ProjectId`, `ChangeListManager`, and `TaskManager`, and target `.idea/workspace.xml` is missing, when `idea-tool.kts` runs, then target `workspace.xml` does not contain those source components.

3. **Workspace run configurations are seeded for a new worktree**
   - Given source `.idea/workspace.xml` has a `RunManager` configuration named `go build github.com/klaviyo/k-repo/go/apis/health_checker/cmd/health_checker`, and target `.idea/workspace.xml` is missing, when `idea-tool.kts` runs, then that run configuration appears in target `workspace.xml`.

4. **Existing target files are never overwritten**
   - Given target `.idea` already has `workspace.xml`, `misc.xml`, `dataSources.xml`, `dataSources.local.xml`, `dataSources/existing-cache.xml`, and `runConfigurations/App_HTTP.xml`, when `idea-tool.kts` runs from a source `.idea` with different contents for each path plus `dataSources/new-cache.xml`, then every existing target file remains byte-for-byte unchanged and `dataSources/new-cache.xml` is copied.

5. **Root paths are rewritten for copied regular text files**
   - Given a source `.idea` regular text file contains the source repository root path and the corresponding target path is missing, when `idea-tool.kts` writes the target file, then that path is rewritten to the target repository root.

6. **Malformed source workspace fails only when workspace seeding is needed**
   - Given target `.idea/workspace.xml` is missing and source `.idea/workspace.xml` is malformed XML, when `idea-tool.kts` runs, then it exits non-zero with a clear error. Given target `.idea/workspace.xml` already exists, when the same malformed source exists, then target workspace is skipped without parsing.

7. **Datasource state seeds new worktrees**
   - Given source `.idea` has `dataSources.xml`, `dataSources.local.xml`, and `dataSources/`, and target is missing those paths, when `idea-tool.kts` runs, then target gets all three paths copied from source with root-path rewriting where applicable.

8. **Never-copy paths are not copied**
   - Given source `.idea` has `shelf/` and `usage.statistics.xml`, and target is missing those paths, when `idea-tool.kts` runs, then neither path is copied.

9. **Explicit policy table governs special paths**
   - Given a maintainer reads `scripts/idea-tool.kts`, when they look for `.idea` copy behavior, then the code has explicit path policies covering `workspace.xml`, `shelf/`, `dataSources.xml`, `dataSources.local.xml`, `dataSources/`, `tasks.xml`, `usage.statistics.xml`, unknown files, and unknown directories.

10. **Docs explain seeding behavior**
   - Given a developer reads the Eng Hub IntelliJ helper docs, when they look for `.idea` setup behavior, then the docs explain target-first seeding, sanitized `workspace.xml`, never-copy paths, datasource seeding, and how to delete specific target paths to reseed them.

## Stories

### 1. Seed `workspace.xml` Go environment without copying project identity

**Acceptance criteria:** Given source `.idea/workspace.xml` has an existing `VgoProject` with `GOFLAGS=-mod=mod` and `GOPRIVATE=github.com/klaviyo`, plus denied identity/session components such as `ProjectId`, `ChangeListManager`, and `TaskManager`, and target `.idea/workspace.xml` is missing, when `idea-tool.kts` runs, then target `workspace.xml` contains `GOPRIVATE=github.com/klaviyo`, `GOFLAGS` includes `-mod=readonly`, and denied source components are absent.

**Expected edits:**

- `scripts/idea-tool.kts`
- Script-level fixtures or a temp-dir test harness for source/target `.idea` trees.

**Scope:**

- Stop treating `workspace.xml` as a blanket volatile skip.
- Implement sanitized first-copy behavior for missing target `workspace.xml`.
- Parse and write `workspace.xml` with an XML library.
- Remove raw string construction of synthetic workspace XML.
- Enforce readonly `GOFLAGS` only inside an existing source `VgoProject`; do not synthesize an entire missing `VgoProject` component.
- Preserve useful non-denied components such as `RunManager`, `GOROOT`, `GoLibraries`, `FlaskConsoleOptions`, and `KubernetesApiProvider`.

**Out of scope:**

- Mutating an existing target `workspace.xml`.
- Adding a force/refresh mode.
- Cleanup mode for old worktrees.

**Notes:**

- Current loss point is `scripts/idea-tool.kts:222`, where `workspace.xml` is skipped before source content can be inspected.
- Current synthetic XML lives in `scripts/idea-tool.kts:112-116` and `scripts/idea-tool.kts:204-211`; replace that with XML-library mutation or remove it.
- `/Users/karl.sabo/Klaviyo/Repos/k-repo/.idea/workspace.xml:2500-2505` is the concrete `GOPRIVATE` example.

### 2. Preserve existing target `.idea` files and seed only missing children

**Acceptance criteria:** Given target `.idea` already has `workspace.xml`, `misc.xml`, `dataSources.xml`, `dataSources.local.xml`, `dataSources/existing-cache.xml`, and `runConfigurations/App_HTTP.xml`, when `idea-tool.kts` runs from a source `.idea` with different contents for those paths plus `dataSources/new-cache.xml`, then every existing target file remains byte-for-byte unchanged and `dataSources/new-cache.xml` is copied.

**Expected edits:**

- `scripts/idea-tool.kts`
- Test fixture with existing target files and an existing target directory missing one child file.

**Scope:**

- Change the helper from overwrite-refresh semantics to target-missing-only seeding semantics.
- If a target file or symlink exists, skip it without parsing or formatting it.
- If a target directory exists, recurse into it and copy only missing child files.
- Skip source/target type conflicts and continue.
- Create parent directories only when needed for copied files/symlinks.
- Exit successfully when nothing was copied because all target files already existed.

**Out of scope:**

- Force overwrite.
- Merging existing target files.
- Repairing malformed existing target files.

**Notes:**

- This is the dominant rule: if target exists, move on.

### 3. Copy datasource files and caches only where missing

**Acceptance criteria:** Given source `.idea` has `dataSources.xml`, `dataSources.local.xml`, and `dataSources/`, and target is missing those paths, when `idea-tool.kts` runs, then target gets all three paths copied from source with root-path rewriting where applicable.

**Expected edits:**

- `scripts/idea-tool.kts`
- Fixtures for datasource files/directories.

**Scope:**

- Remove blanket skips for datasource paths.
- Copy `dataSources.xml`, `dataSources.local.xml`, and missing files under `dataSources/` when corresponding target files are missing.
- Preserve existing target datasource files/caches.
- Do not parse datasource XML; treat it as regular copied text/binary with root-path rewriting where applicable.

**Out of scope:**

- Merging datasource definitions when target already exists.
- Sanitizing datasource local metadata beyond root-path rewriting.

**Notes:**

- App and k-repo datasource files are useful first-run setup; existing target state still wins.

### 4. Encode `.idea` path policies explicitly

**Acceptance criteria:** Given a maintainer reads `scripts/idea-tool.kts`, when they look for `.idea` copy behavior, then the code has explicit path policies covering `workspace.xml`, `shelf/`, `dataSources.xml`, `dataSources.local.xml`, `dataSources/`, `tasks.xml`, `usage.statistics.xml`, unknown files, and unknown directories. Given source `.idea` has `shelf/` and `usage.statistics.xml`, when target is missing those paths, then neither path is copied.

**Expected edits:**

- `scripts/idea-tool.kts`
- Tests that exercise at least one path for each policy kind.

**Scope:**

- Replace `volatileIdeaTopLevelPaths` with named policy decisions.
- Make target-missing copy, sanitized first copy, recursive missing-child copy, and never-copy explicit.
- Mark `shelf/` and `usage.statistics.xml` as never-copy.
- Mark `tasks.xml` as normal target-missing copy.
- Use explicit path/component names only; no broad pattern skip rules.
- Copy `.idea/.gitignore` when target is missing.

**Out of scope:**

- Runtime policy configuration.
- Plugin-driven policy discovery.

**Notes:**

- This is the code-level expression of "put deep thought into every file" and prevents the old blanket-skip problem from returning under another name.

### 5. Preserve symlinks and file attributes during missing-file copy

**Acceptance criteria:** Given source `.idea` contains a symlink, a regular file with non-default attributes, and target paths are missing, when `idea-tool.kts` runs, then the target symlink is created as a symlink without following or rewriting its target, and copied regular files preserve attributes where possible.

**Expected edits:**

- `scripts/idea-tool.kts`
- Fixtures for symlinks and file attributes where practical on the local platform.

**Scope:**

- Never follow source symlinks.
- Preserve source symlinks as symlinks only when target path is missing.
- Do not rewrite symlink targets.
- Preserve source file attributes where possible for copied files.

**Out of scope:**

- Cross-platform perfection for every file attribute.
- Following symlinks recursively.

### 6. Rewrite source roots for copied regular text files

**Acceptance criteria:** Given a source `.idea` regular UTF-8 text file contains the source repository root path and the corresponding target path is missing, when `idea-tool.kts` writes the target file, then that path is rewritten to the target repository root.

**Expected edits:**

- `scripts/idea-tool.kts`
- Fixture with an absolute source repo path inside a copied `.idea` text file and inside a sanitized `workspace.xml` component.

**Scope:**

- Reuse existing exact string replacement semantics from normal copied UTF-8 files.
- Apply rewriting to regular copied text files and sanitized `workspace.xml` content before writing.
- Do not parse XML files other than `workspace.xml`.
- Do not rewrite symlink targets.

**Out of scope:**

- Fuzzy path rewriting.
- Rewriting unrelated user-home paths such as SDK locations.

**Notes:**

- Current normal file behavior lives in `scripts/idea-tool.kts:231-322`; keep the same policy.

### 7. Document target-first `.idea` seeding

**Acceptance criteria:** Given a developer reads the Eng Hub IntelliJ helper docs, when they look for `.idea` setup behavior, then the docs explain target-first seeding, sanitized `workspace.xml`, never-copy paths, datasource seeding, and how to delete specific target paths to reseed them.

**Expected edits:**

- `eng-hub/README.md`
- Maybe `README.md` TODO cleanup after implementation lands.
- `scripts/idea-tool.kts` usage text.

**Scope:**

- Replace refresh language with seed language.
- Document that existing target files are never overwritten or repaired.
- Document how to reseed a file: close IntelliJ, delete the specific target `.idea` path, rerun the helper.
- Document `workspace.xml` sanitization and denied components at a high level.
- Document never-copy paths: `shelf/` and `usage.statistics.xml`.
- Document datasource paths copy when missing.
- Document that there is no `--force` mode.

**Out of scope:**

- User-facing UI changes in Eng Hub.
