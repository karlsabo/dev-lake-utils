# Unified Worktree Repository Config Plan

**Goal**: Make `localRepositories` the single Eng Hub source of truth for Worktrees, including each repository path and its setup commands, and migrate existing configs that still use `worktreeSetupCommands`.

**Context**:

- The previous Worktree Repository Panel plan is complete, but the production config at `<app-config-dir>/eng-hub-config.json` currently has no `localRepositories` field and does have four repository roots under `worktreeSetupCommands`: `/workspace/example-service`, `/workspace/example-web`, `/workspace/example-worker`, and `/workspace/example-infra`.
- `EngHubConfig` currently stores repository identity and setup commands in two different shapes: `localRepositories: List<String>` and `worktreeSetupCommands: Map<String, List<String>>` (`eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHubConfig.kt:22`, `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHubConfig.kt:23`).
- The Worktrees state is initialized only from `config.localRepositories`, so a config with only setup-command repositories produces an empty Worktrees list (`eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModel.kt:139`).
- The worktree poller also reads only `currentConfig.localRepositories`, so repositories that only exist as setup-command keys are never refreshed in the background (`eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModel.kt:499`).
- The Worktrees panel renders `No repositories configured` whenever the view-model list is empty, which is the visible symptom for the current production config (`eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreePanel.kt:104`).
- Repository rows are already sorted by folder name once a list of paths reaches `toLocalRepositoryUiStates`, so the unified model should preserve that ordering behavior (`eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/state/LocalRepositoryUiState.kt:24`).
- Opening a worktree currently looks up commands by exact repo root path in `worktreeSetupCommands`, so the setup lookup must move to the matching `localRepositories` entry (`eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/WorktreeSetupCommands.kt:14`).

## Target Config Shape

Use `localRepositories` as a list of repository objects:

```json
{
  "localRepositories": [
    {
      "path": "/workspace/example-service",
      "setupCommands": [
        "direnv allow",
        "direnv exec . idea ./"
      ]
    }
  ],
  "setupShell": "/bin/zsh"
}
```

`setupShell` remains global. `worktreeSetupCommands` becomes legacy input for migration only and should not be used by runtime Worktrees behavior after migration.

## Acceptance Tests

1. **Read unified local repository config**
    - Given `eng-hub-config.json` contains `localRepositories` entries with `path` and `setupCommands`, when Eng Hub loads the config, then the decoded config contains the repository paths and commands on the same local repository objects.

2. **Render unified repositories**
    - Given the config contains local repository objects for `/workspace/example-service`, `/workspace/example-web`, `/workspace/example-worker`, and `/workspace/example-infra`, when I open the Worktrees view, then I see repository rows ordered `example-infra`, `example-service`, `example-web`, `example-worker` instead of `No repositories configured`.

3. **Normalize existing setup-command config in memory**
    - Given `<app-config-dir>/eng-hub-config.json` has no `localRepositories` field and has `worktreeSetupCommands` keys for `/workspace/example-service`, `/workspace/example-web`, `/workspace/example-worker`, and `/workspace/example-infra`, when Eng Hub loads the config, then the in-memory config has four `localRepositories` objects containing those paths and setup commands.

4. **Expand unified repository**
    - Given `/workspace/example-web` is a `localRepositories` object and git reports worktrees for `main` and `feature/worktree-loading`, when I expand `example-web`, then Eng Hub shows both worktrees with current dirty status from that local repository entry.

5. **Poll unified repositories**
    - Given `/workspace/example-worker` is a `localRepositories` object and a linked worktree is created outside Eng Hub, when the worktree poll interval elapses, then the `example-worker` repository state includes the new worktree without refreshing PRs or notifications.

6. **Add repository as unified entry**
    - Given the config already has a `localRepositories` object for `/workspace/example-web`, when I use Add Repository to pick `/workspace/new-local-repo`, then Eng Hub saves a new local repository object with that path and an empty setup command list without modifying the existing `example-web` setup commands.

7. **Open worktree with unified setup commands**
    - Given `/workspace/example-service` is a `localRepositories` object with setup commands and its repository row shows worktree `/workspace/example-service-feature-worktree-loading`, when I choose `Open`, then Eng Hub runs the setup commands from that local repository object in the selected worktree path.

8. **Set up PR worktree with unified setup commands**
    - Given `/workspace/example-service` is a `localRepositories` object with setup commands, when the existing `checkoutAndOpen("example-org/example-service", "feature/worktree-loading")` setup flow runs, then Eng Hub creates or reuses the branch worktree and runs the setup commands from that local repository object.

9. **Persist migrated setup-command config**
    - Given Eng Hub normalized legacy `worktreeSetupCommands` into `localRepositories`, when startup dependency loading completes, then `eng-hub-config.json` is saved with the unified `localRepositories` object shape and without relying on `worktreeSetupCommands` for runtime behavior.

## Stories

### 1. Introduce Unified Local Repository Config Entries - Done

**Done:** 2026 05 11

**Acceptance criteria:** Given `eng-hub-config.json` contains `localRepositories` entries with `path` and `setupCommands`, when Eng Hub loads the config, then the decoded config contains the repository paths and commands on the same local repository objects.

**Expected edits:** `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHubConfig.kt`, `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/EngHubConfigTest.kt`, and any narrow compile-through updates needed where `EngHubConfig.localRepositories` is currently treated as `List<String>`.

**Scope:** Add a serializable local repository entry model such as `LocalRepositoryConfig(path: String, setupCommands: List<String> = emptyList())` and make `EngHubConfig.localRepositories` decode and encode as `List<LocalRepositoryConfig>`. Keep `setupShell` global. This story does not add new Worktrees UI behavior, Add Repository behavior, setup execution behavior, or legacy `worktreeSetupCommands` migration.

**Notes:** This is only the schema foundation, but it must keep the codebase compiling after the type change. Backward compatibility with the already-implemented `localRepositories: List<String>` shape is required: decode string entries as legacy input and normalize them to entries with empty `setupCommands`. Add small helper APIs on `EngHubConfig` or `LocalRepositoryConfig` if needed so follow-up stories can consistently read normalized paths without reimplementing list traversal.

### 2. Render And Expand Repositories From Unified Local Repository Entries - Done

**Done:** 2026 05 11

**Acceptance criteria:** Given the config contains local repository objects for `/workspace/example-service`, `/workspace/example-web`, `/workspace/example-worker`, and `/workspace/example-infra`, when I open the Worktrees view, then I see repository rows ordered `example-infra`, `example-service`, `example-web`, `example-worker` instead of `No repositories configured`.

**Expected edits:** `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModel.kt`, `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/state/LocalRepositoryUiState.kt`, and focused tests in `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModelTest.kt` or `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/state/LocalRepositoryUiStateTest.kt`.

**Scope:** Initialize `localRepositoriesStateFlow` from the unified repository entries and keep the existing folder-name sorting behavior. Update expansion only as needed to use the same repository `path` exposed in UI state. This story does not change polling, adding, setup execution, or migration.

**Notes:** Preserve the exact `path` from each config entry in `LocalRepositoryUiState.path`, because later actions use that path to list worktrees and find setup commands. Expansion already receives `repoRootPath` from the row in `EngHubViewModel.toggleLocalRepositoryExpansion`, so this should mostly be a regression test that the object-backed row path still drives `GitWorktreeApi.listWorktrees(repoRootPath)`.

### 3. Normalize Legacy Worktree Setup Commands In Memory - Done

**Done:** 2026 05 11

**Acceptance criteria:** Given `<app-config-dir>/eng-hub-config.json` has no `localRepositories` field and has `worktreeSetupCommands` keys for `/workspace/example-service`, `/workspace/example-web`, `/workspace/example-worker`, and `/workspace/example-infra`, when Eng Hub loads the config, then the in-memory config has four `localRepositories` objects containing those paths and setup commands.

**Expected edits:** `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHubConfig.kt` and tests in `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/EngHubConfigTest.kt`.

**Scope:** Add config normalization that converts legacy `worktreeSetupCommands` into `localRepositories` entries in memory. Preserve each exact repository root string as `path`, copy the associated command list into `setupCommands`, and deduplicate by normalized path. This story does not save the migrated config back to disk.

**Notes:** Use representative placeholder paths in the test so it proves the current broken config shape is handled without encoding machine-specific fixtures. If both unified `localRepositories` and legacy `worktreeSetupCommands` are present, prefer the unified local repository entry and only fill missing setup commands from the legacy map when the matching entry has no commands.

### 4. Poll Unified Local Repository Entries - Done

**Done:** 2026 05 11

**Acceptance criteria:** Given `/workspace/example-worker` is a `localRepositories` object and a linked worktree is created outside Eng Hub, when the worktree poll interval elapses, then the `example-worker` repository state includes the new worktree without refreshing PRs or notifications.

**Expected edits:** `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModel.kt` and `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModelTest.kt`.

**Scope:** Update background worktree polling to iterate over unified `localRepositories` entries and use each entry's `path`. Keep the separate worktree poll interval, keep collapsed rows collapsed while refreshing cached child worktrees, and verify the poller does not trigger PR or notification refreshes.

**Notes:** This is separate from render/expand because polling has different timing and side-effect risks. After migration, `localRepositories` is the only runtime list that should drive Worktrees.

### 5. Add Repositories As Unified Local Repository Entries - Done

**Done:** 2026 05 11

**Acceptance criteria:** Given the config already has a `localRepositories` object for `/workspace/example-web`, when I use Add Repository to pick `/workspace/new-local-repo`, then Eng Hub saves a new local repository object with that path and an empty setup command list without modifying the existing `example-web` setup commands.

**Expected edits:** `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModel.kt`, `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHubConfig.kt` if helper APIs are added there, and add-repository tests in `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModelTest.kt`.

**Scope:** Update `addLocalRepository` to append `LocalRepositoryConfig(path = rootPath, setupCommands = emptyList())`, detect duplicates against existing local repository entry paths, save the unified config, and rebuild UI state without dropping existing setup commands. This story does not add UI for editing setup commands.

**Notes:** Current duplicate detection checks strings in `currentConfig.localRepositories` (`EngHubViewModel.kt:254`). After the schema change, it should compare normalized entry paths. Adding a repository should not create or update legacy `worktreeSetupCommands`.

### 6. Run Worktree Open Setup Commands From The Local Repository Entry

**Acceptance criteria:** Given `/workspace/example-service` is a `localRepositories` object with setup commands and its repository row shows worktree `/workspace/example-service-feature-worktree-loading`, when I choose `Open`, then Eng Hub runs the setup commands from that local repository object in the selected worktree path.

**Expected edits:** `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/WorktreeSetupCommands.kt`, `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModel.kt`, and tests in `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModelTest.kt` or a new focused `WorktreeSetupCommandsTest`.

**Scope:** Replace setup command lookup from `config.worktreeSetupCommands[repoPath]` with lookup of the `localRepositories` entry whose normalized `path` matches `repoPath`, then run that entry's `setupCommands` in the selected worktree path using the existing `setupShell` script behavior. Preserve current failure reporting through `actionError`.

**Notes:** This story is what makes the unified config operational rather than just visible. It should prove that a migrated `example-service` entry still runs `direnv allow` and `direnv exec . idea ./` when a worktree is opened. Land this before startup persistence removes the legacy map from saved config.

### 7. Run PR And Notification Setup Commands From The Local Repository Entry

**Acceptance criteria:** Given `/workspace/example-service` is a `localRepositories` object with setup commands, when the existing `checkoutAndOpen("example-org/example-service", "feature/worktree-loading")` setup flow runs, then Eng Hub creates or reuses the branch worktree and runs the setup commands from that local repository object.

**Expected edits:** `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModel.kt`, `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/WorktreeSetupCommands.kt` if the setup helper needs a reusable lookup API, and tests in `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModelTest.kt` or `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/EngHubDependenciesTest.kt`.

**Scope:** Ensure the existing PR/notification setup flow, `checkoutAndOpen`, uses the unified local repository entry setup commands after it resolves `repoPath` from `repositoriesBaseDir` and repository name. Preserve repository cloning, worktree creation, setup-shell execution, and existing action error behavior.

**Notes:** This closes the regression risk where Worktree `Open` works but notification `Setup` silently stops running commands because it used the old `worktreeSetupCommands` map. The UI has pull request and notification entry points, but both route through `checkoutAndOpen`; keep the acceptance test focused on that shared behavior unless separate UI-entry tests are explicitly needed.

### 8. Persist Migrated Config On Startup

**Acceptance criteria:** Given Eng Hub normalized legacy `worktreeSetupCommands` into `localRepositories`, when startup dependency loading completes, then `eng-hub-config.json` is saved with the unified `localRepositories` object shape and without relying on `worktreeSetupCommands` for runtime behavior.

**Expected edits:** `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHubConfig.kt`, `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHubDependencies.kt`, and dependency-loading tests in `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/EngHubDependenciesTest.kt`.

**Scope:** Wire startup loading to save the normalized config when migration changed it. The saved config should contain unified `localRepositories` entries and should not retain `worktreeSetupCommands` unless a transitional serializer requires temporarily omitting default/empty legacy data. This story does not change UI behavior beyond using the already-normalized config.

**Notes:** This is deliberately last among the migration stories so all runtime behavior has already stopped depending on `worktreeSetupCommands`.

## Remaining Questions

None.
