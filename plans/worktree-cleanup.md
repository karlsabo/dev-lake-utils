# Worktree Config Cleanup Plan

**Goal**: Remove the transitional Worktrees config migration code now that `localRepositories` is the canonical setup-command model.

**Context**:

- `plans/worktree-02.md` is complete and explicitly made `localRepositories` the single Eng Hub source of truth; `worktreeSetupCommands` was only kept as legacy input for migration.
- `EngHubConfig` still carries `worktreeSetupCommands`, startup migration, custom config serializers, and normalization logic even though runtime behavior should now be driven by `localRepositories` (`eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHubConfig.kt:25`, `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHubConfig.kt:34`, `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHubConfig.kt:37`, `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHubConfig.kt:50`, `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHubConfig.kt:103`, `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHubConfig.kt:147`).
- Startup still detects a migrated config and writes it back through `EngHubConfigWriter` before creating the component (`eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHubDependencies.kt:21`).
- Setup execution still falls back to `config.worktreeSetupCommands` if no unified entry exists or the unified entry has no commands (`eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/WorktreeSetupCommands.kt:14`, `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/WorktreeSetupCommands.kt:17`).
- Config tests still assert legacy behavior, including string-style local repository entries, `worktreeSetupCommands` normalization, overlap precedence, and deduplication (`eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/EngHubConfigTest.kt:104`, `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/EngHubConfigTest.kt:126`, `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/EngHubConfigTest.kt:176`, `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/EngHubConfigTest.kt:226`).
- View-model setup tests still use `worktreeSetupCommands` for normal setup/progress/error paths, so removing the legacy field requires converting those tests to configure `LocalRepositoryConfig.setupCommands` directly (`eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModelTest.kt:530`, `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModelTest.kt:632`, `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModelTest.kt:659`, `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModelTest.kt:692`, `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModelTest.kt:733`, `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModelTest.kt:778`).
- `lenientJson` encodes default values globally, so a simpler no-custom-serializer config may either encode empty `setupCommands` lists or use small field-level serialization annotations instead of full custom serializers (`utilities/src/commonMain/kotlin/com/github/karlsabo/tools/JsonParsingUtils.kt:12`, `utilities/src/commonMain/kotlin/com/github/karlsabo/tools/JsonParsingUtils.kt:16`).

## Target Config Shape

Keep only the unified object form:

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

The cleanup intentionally stops accepting `worktreeSetupCommands` and string entries in `localRepositories`.

## Acceptance Tests

1. **Decode canonical repository config**
    - Given `eng-hub-config.json` contains `localRepositories` entries with `path` and `setupCommands`, when Eng Hub loads the config, then setup commands are available on the matching `LocalRepositoryConfig` objects.

2. **Save canonical repository config**
    - Given I add `/workspace/new-local-repo` from the Worktrees view, when Eng Hub saves the config, then the saved config contains a `localRepositories` object for that path and does not contain `worktreeSetupCommands`.

3. **Run setup from canonical repository entry**
    - Given `/workspace/example-service` is configured as a `localRepositories` object with setup commands, when I open one of its worktrees, then Eng Hub runs those commands in the selected worktree path.

4. **Start without migration side effects**
    - Given Eng Hub loads a canonical config, when startup dependency loading completes, then it creates the view model with that same config and does not save a migrated config.

## Stories

### 1. Simplify Eng Hub Config Serialization (Done)

**Status:** Done 2026 05 12.

**Acceptance criteria:** Given `eng-hub-config.json` contains only object-shaped `localRepositories`, when Eng Hub loads and saves config, then the config round-trips through regular Kotlin serialization without custom `EngHubConfigSerializer` or `LocalRepositoryConfigSerializer` code.

**Expected edits:** `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHubConfig.kt`, `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/EngHubConfigTest.kt`.

**Scope:** Replace `@Serializable(with = EngHubConfigSerializer::class)` and `@Serializable(with = LocalRepositoryConfigSerializer::class)` with plain `@Serializable` data classes. Remove `EngHubConfigSerializer`, `EngHubConfigSurrogate`, `LocalRepositoryConfigSerializer`, and `LocalRepositoryConfigSurrogate`. Keep tests that prove canonical object-shaped config decodes and setup fields default sensibly. Remove tests for string local repository entries and legacy setup-command normalization.

**Notes:** This story deliberately drops support for `localRepositories: ["..."]`. If the saved JSON should avoid `"setupCommands": []`, use a small field annotation on `LocalRepositoryConfig.setupCommands`; do not keep the custom serializer just for that formatting preference.

### 2. Remove Legacy Worktree Setup Commands - Done

**Status:** Done 2026 05 12.

**Acceptance criteria:** Given `/workspace/example-service` is a `localRepositories` object with setup commands, when I open an existing worktree or run `checkoutAndOpen("example-org/example-service", "feature/worktree-loading")`, then Eng Hub runs setup commands only from the matching local repository entry.

**Expected edits:** `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHubConfig.kt`, `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/WorktreeSetupCommands.kt`, `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModelTest.kt`.

**Scope:** Delete `EngHubConfig.worktreeSetupCommands`, delete the legacy fallback branch in `runConfiguredWorktreeSetup`, and update all setup/progress/error tests to use `LocalRepositoryConfig(path = repoRoot, setupCommands = ...)`. Keep normalized path matching for repository paths because the unified tests already rely on trailing-slash tolerance.

**Notes:** The helper `createLocalRepositoryViewModel` should stop accepting `worktreeSetupCommands` and should prefer `localRepositoryConfigs` for tests that need setup commands. The explicit legacy fallback test should be removed.

### 3. Remove Startup Migration Plumbing

**Acceptance criteria:** Given Eng Hub loads a canonical config, when `loadEngHubDependencies` runs, then it passes that config directly to `createEngHubComponent` and does not call `configWriter.save`.

**Expected edits:** `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHubConfig.kt`, `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHubDependencies.kt`, `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/EngHubDependenciesTest.kt`.

**Scope:** Delete `EngHubConfig.migratedForStartup` and remove the migration save branch from `loadEngHubDependencies`. Update dependency tests so they assert ordinary dependency wiring only, not migration persistence.

**Notes:** This story should land after the legacy setup-command field is gone so no production code can still construct a meaningful migrated config. `EngHubConfigWriter` remains useful for view-model saves such as Add Repository and should not be removed as part of this story.

## Remaining Questions

- Before implementing this cleanup, confirm the active `eng-hub-config.json` has already been rewritten into the unified `localRepositories` object shape. After this plan lands, old files with only `worktreeSetupCommands` will no longer be upgraded automatically.
