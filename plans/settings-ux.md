# Eng Hub settings UX

**Status:** Final — approved and ready for implementation one story/PR at a time.

**Goal:** Let Eng Hub users find a settings pane from an extensible searchable menu, edit all Eng Hub and GitHub configuration without save/cancel/apply controls, and recover into settings when configuration is missing or unusable.

## Context and agreed product decisions

- Scope is **Eng Hub only**. This plan does not add settings to other applications in the repository.
- `EngHubConfig` contains `organizationIds`, `pollIntervalMs`, `worktreePollIntervalMs`, `repositoriesBaseDir`, `gitHubAuthor`, `planningMarkdownDir`, `localRepositories`, and `setupShell`; each local repository contains a `path` and ordered `setupCommands` (`eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHubConfig.kt`). Every field will be editable.
- Eng Hub configuration is JSON at the platform-specific `DevLakeUtils/eng-hub-config.json` path. Existing `loadEngHubConfig` and `saveEngHubConfig` functions are the persistence boundary (`eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHubConfig.kt`).
- GitHub configuration is explicitly in scope. The shared `github-config.json` contains `tokenPath`; that path points to a second JSON file containing `githubToken` (`utilities/src/commonMain/kotlin/com/github/karlsabo/github/config/GitHubConfig.kt`, `eng-hub/README.md`). Settings edits both the secret-file path and token contents. The token is masked and is persisted only as `GitHubSecret` JSON in the referenced secret file, never in `eng-hub-config.json` or `github-config.json`. Eng Hub creates or replaces the secret file when a token is committed for its path. Because `github-config.json` is shared, these edits also affect the repository's other tools that load it.
- Add an in-window three-dots action. Clicking it opens a lightweight popup with a search field at the top and filterable action rows below. `Settings` is the first action, but the popup must be structured to support more actions later.
- Settings is not a modal dialog. It is a first-class pane alongside Pull Requests, Notifications, and Worktrees. Its sidebar button uses a gear icon and is anchored at the bottom-left. Choosing `Settings` in the three-dots popup navigates to this same pane.
- The existing sidebar renders every `EngHubPane` in one top-spaced column, so bottom anchoring and the searchable action popup require changes to the screen shell (`eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/screen/EngHubScreen.kt`, `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/screen/EngHubPane.kt`).
- There will be no save, cancel, apply, or success-confirmation UI. Draft controls update immediately. Text edits validate and auto-persist after 750 ms without input; discrete add/remove/reorder and picker actions commit immediately. A valid pending edit flushes immediately when the user changes panes, closes the window, or exits the application. Invalid draft text remains unsaved; navigation and exit are never blocked by a confirmation. Runtime configuration changes react only after valid persistence succeeds.
- Runtime consumers currently have mixed configuration lifecycles. Pull-request and notification polling capture startup config, while some local repository paths consult `EngHubViewModelState.currentConfig` (`eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModel.kt`, `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/LocalRepositoryController.kt`). A committed configuration replaces stale polling/runtime behavior without cancelling already-started worktree/setup operations. Finite setup/worktree operations live in a longer-lived action scope; obsolete polling jobs are cancelled and restarted from committed configuration. An operation may finish after its repository is removed from settings, in which case its result no longer appears in the UI. Settings selection and draft state survive runtime refresh.
- Polling intervals are displayed and edited as whole seconds, then converted to milliseconds for `EngHubConfig` persistence.
- `repositoriesBaseDir`, `planningMarkdownDir`, and each local repository path have editable text plus a directory picker. Paths may be typed even if they do not yet exist. The GitHub secret path needs equivalent file-path selection. Eng Hub currently has only a common `DirectoryPicker` abstraction backed by Swing on JVM, so secret selection will require a file-picker extension or sibling abstraction (`eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/DirectoryPicker.kt`, `eng-hub/src/jvmMain/kotlin/com/github/karlsabo/devlake/enghub/SwingDirectoryPicker.kt`).
- Organization IDs use repeatable rows rather than comma-separated text.
- Local repositories support add, edit, and remove. Setup commands support add, edit, remove, and ordering. Repository ordering is omitted because no runtime behavior currently gives it meaning. Removing a repository changes configuration only and never touches its files/worktrees on disk. Removal auto-persists immediately and shows a short-lived inline Undo action that restores the full repository entry and ordered setup commands.
- When configuration is absent, the application opens directly into settings rather than showing the current terminal bootstrap error. There is no cancel path; the user configures the application through auto-persisted edits.
- Config loading first tries `.json`, then `.json.bak` if the primary cannot be decoded. A save writes and decode-verifies `.json.new`, moves the valid current `.json` to `.json.bak`, then moves `.json.new` to `.json`. If the final move fails, Eng Hub restores `.json.bak` to `.json` best-effort and reports the failure; an invalid primary never replaces a valid backup. This applies independently to `eng-hub-config.json` and `github-config.json`. If neither primary nor backup is valid, Eng Hub behaves as a fresh instance in Settings; it does not attempt field-level salvage from malformed JSON. Secret writes use the same two-stage process, but unreadable secret content is backed up and never auto-loaded; the user must enter a new token.
- Startup currently loads Eng Hub and resolved GitHub API configuration as one operation. On failure, it creates a default Eng Hub config only when absent and shows an error dialog that exits the app (`eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHub.kt`, `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHubDependencies.kt`). Supporting editable incomplete configuration requires separating settings-file loading from runtime dependency construction.
- Syntactically valid partial configuration persists even when feature-required fields are incomplete, so first-launch progress survives closing the app. Persistence validation rejects non-positive/non-whole-second polling intervals, blank collection rows, duplicate organization IDs, duplicate normalized repository paths, and failed file writes. Feature readiness is separate from persistence validity.
- Notifications requires a readable secret containing a nonblank token. Pull Requests requires that token plus a nonblank GitHub author; an empty organization list is allowed and produces an explanatory empty state. Worktrees requires valid local/repository settings, and a nonblank setup shell is required only before running setup commands. Optional directory paths may be blank and typed paths need not exist.
- All pane buttons remain visible on incomplete/fresh configuration. A pane that is not ready is disabled and has a tooltip naming the settings that must be completed.
- Persistence failures and invalid draft values need actionable errors even though successful saves need no indication. Silently losing an auto-save would be unacceptable.
- Action search is case-insensitive. Exact prefix/substring matches rank first; otherwise the query is compared with each title word using Levenshtein distance and matches at distance 2 or less are included. For example, `setings` finds `Settings`. Static keywords may be added per action later. Opening the popup focuses search; typing filters matches; Up/Down changes the highlighted action; Enter invokes it; Escape closes the popup and restores focus to the trigger. Mouse selection remains available.
- GitHub secret writes are security-sensitive. On POSIX, newly created/replaced secrets use owner read/write permissions only (`0600`) and preserve restrictive existing permissions where possible; inability to enforce restrictive permissions fails the write with an actionable error. Windows uses the user's default ACL without Unix-mode emulation.

## Agreed UX shape

### Global action popup

```text
| ================ |
| Search actions…  |
| Settings         |
| ---------------- |
```

- Opened by an in-window three-dots button.
- Search filters available actions.
- Selecting `Settings` closes the popup and navigates to the Settings pane.
- The menu model should not hard-code layout around settings alone; adding another action later should be a localized change.

### Settings pane

- Gear button anchored to the bottom of the left sidebar.
- Sections:
  - **GitHub access:** editable secret-file path and a masked token field backed by the loaded token. There is no reveal control. Token contents are excluded from logs, errors, semantics/content descriptions, and screenshot fixtures. The token is written only when changed or when its destination must be created. Changing only the secret path copies the current token to the new path using two-stage persistence before updating `github-config.json`, so the config never points to a missing secret.
  - **GitHub activity:** organization IDs, GitHub author, pull-request/notification polling interval.
  - **Repositories:** repositories base directory, worktree polling interval, local repository entries, and each repository's setup commands.
  - **Planning and setup:** planning markdown directory and setup shell.
- Collection fields use structured repeatable rows.
- Directory fields support text entry and Browse.
- No form-level save/cancel/apply controls.
- No success toast/banner/label.
- Invalid edits and persistence failures remain visible inline and do not replace the last valid persisted/runtime configuration.


## Acceptance tests

**Approved.** Each acceptance test below is one observable behavior and maps to one story/PR.

**Build acceptance criterion:** `./gradlew clean build` passes.

1. **Find Settings through the action popup**
   - Given Eng Hub is open, when the user opens the three-dots popup and searches for `setings`, then fuzzy matching keeps `Settings` visible; when the user selects it, the popup closes and the Settings pane is selected.

2. **Open Settings from the bottom gear**
   - Given the Pull Requests pane is selected, when the user clicks the bottom-anchored gear, then the Settings pane is selected.

3. **Display loaded configuration**
   - Given valid Eng Hub and GitHub configuration with representative values for every field, when the Settings pane opens, then every value is shown in its corresponding control without rewriting either config.

4. **Auto-persist a general setting**
   - Given `gitHubAuthor` is `octocat`, when the user changes it to `hubot` and crosses the agreed commit boundary, then `eng-hub-config.json` contains `hubot` without a save action.

5. **Apply a committed setting to runtime behavior**
   - Given the running app uses a 600-second polling interval, when the user commits 300 seconds, then obsolete polling stops and replacement polling uses `300000` milliseconds without restarting the application.

6. **Reject an invalid polling interval**
   - Given the persisted interval is 600 seconds, when the user enters `0`, then the field shows an actionable error and persisted/runtime configuration remains at 600 seconds.

7. **Add an organization ID**
   - Given organization ID `acme`, when the user adds `widgets`, then the auto-persisted config contains `acme` and `widgets`.

8. **Remove an organization ID**
   - Given organization IDs `acme` and `example`, when the user removes `example`, then the auto-persisted config contains only `acme`.

9. **Edit setup-command ordering**
   - Given `/workspace/api` has `direnv allow`, when the user adds `cp .env.example .env` before it, then the auto-persisted repository config contains both commands in that order.

10. **Add a configured repository**
   - Given settings does not contain `/workspace/web`, when the user adds it, then the auto-persisted config includes `/workspace/web`.

11. **Remove a configured repository**
    - Given settings contains `/workspace/old`, when the user removes it, then the auto-persisted config no longer includes `/workspace/old`.

12. **Choose a directory**
    - Given the repositories base directory control is empty, when the user chooses `/workspace` through Browse, then the field and auto-persisted config contain `/workspace`.

13. **Replace the GitHub token**
    - Given Settings loaded an existing masked token, when the user replaces it with `github_pat_new` and the debounce elapses, then the referenced secret JSON contains `github_pat_new` while both non-secret config files contain no token.

14. **Protect a created GitHub secret**
    - Given Eng Hub is running on POSIX and the selected secret does not exist, when the token is committed, then the created secret file has mode `0600`.

15. **Create GitHub access files**
    - Given `github-config.json` and the selected secret path do not exist, when the user enters the secret path and a token, then Eng Hub creates valid `GitHubConfig` and `GitHubSecret` files and enables panes whose readiness requirements are met.

16. **Start without Eng Hub configuration**
    - Given `eng-hub-config.json` does not exist, when Eng Hub launches, then it opens the Settings pane with default draft values and does not show the terminal bootstrap error.

17. **Recover from invalid Eng Hub configuration**
    - Given `eng-hub-config.json` is invalid and `eng-hub-config.json.bak` is valid, when Eng Hub launches, then it loads the backup values and exposes them in Settings.

18. **Report auto-save failure**
    - Given a valid persisted value and a storage failure, when the user commits a replacement value, then settings shows an actionable persistence error and runtime dependencies continue using the previously persisted value.

19. **Flush a pending edit on navigation**
    - Given `gitHubAuthor` has a pending valid edit to `hubot`, when the user changes panes before the 750 ms debounce elapses, then `hubot` is persisted before navigation completes.

20. **Undo repository removal**
    - Given `/workspace/api` has two ordered setup commands, when the user removes it and selects Undo, then the auto-persisted config again contains `/workspace/api` with both commands in their original order and no repository files were deleted.

21. **Invoke an action by keyboard**
    - Given the action popup is open, when the user types `setings`, presses Down to highlight `Settings`, and presses Enter, then Settings opens.

22. **Dismiss the action popup by keyboard**
    - Given the action popup is open from the three-dots trigger, when the user presses Escape, then the popup closes and focus returns to the trigger.

23. **Persist the worktree polling interval**
    - Given the worktree polling interval is 120 seconds, when the user changes it to 60 and leaves it idle for 750 ms, then `worktreePollIntervalMs` is persisted as `60000`.

24. **Persist the setup shell**
    - Given the setup shell is `/bin/zsh`, when the user changes it to `/bin/bash` and leaves it idle for 750 ms, then the config contains `/bin/bash` for subsequent setup commands.

25. **Edit a configured repository path**
    - Given Settings contains repository `/workspace/old`, when the user changes its path to `/workspace/new` through Browse, then the auto-persisted repository entry uses `/workspace/new` and retains its setup commands.

26. **Edit a setup command**
    - Given `/workspace/api` has setup command `direnv allow`, when the user changes it to `direnv allow .` and leaves it idle for 750 ms, then the auto-persisted command is `direnv allow .`.

27. **Remove a setup command**
    - Given `/workspace/api` has setup commands `cp .env.example .env` and `direnv allow`, when the user removes `cp .env.example .env`, then the auto-persisted repository retains only `direnv allow`.

## Stories

Implement these in order. Each story is one ticket, one acceptance test, and one PR. Later stories may build on files introduced earlier, but every story below includes its own product context and boundaries.

### 1. Find Settings through the fuzzy action popup

**Status:** Done

**Acceptance criteria:** Given Eng Hub is open, when the user opens the three-dots popup and searches for `setings`, then fuzzy matching keeps `Settings` visible; when the user selects it, the popup closes and the Settings pane is selected.

**Expected edits:**
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/screen/EngHubScreen.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/screen/EngHubPane.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/screen/EngHubScreenPanes.kt`
- New `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/EngHubActionPopup.kt`
- New `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/EngHubAction.kt`
- New tests under `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/component/` and `screen/`

**Scope:** Add a placeholder Settings pane plus the three-dots trigger, extensible action model, search field, fuzzy filtering, mouse selection, and Settings navigation. Exact prefix/substring matches rank first; otherwise compare the query to each title word case-insensitively with Levenshtein distance at most 2. Keyboard controls are out.

**Notes:** Keep action metadata separate from rendering so later actions are localized additions. Story 2 adds the direct gear route to the same pane.

### 2. Open Settings from the bottom gear

**Status:** Done

**Acceptance criteria:** Given the Pull Requests pane is selected, when the user clicks the bottom-anchored gear, then the Settings pane is selected.

**Expected edits:**
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/screen/EngHubPane.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/screen/EngHubScreen.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/screen/EngHubScreenPanes.kt`
- New or existing screen tests under `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/screen/`

**Scope:** Add Settings as a first-class pane and anchor its gear at the bottom of the sidebar. Do not implement configuration controls or persistence.

**Notes:** The gear and action-popup Settings item must select the same `EngHubPane.Settings` destination.

### 3. Display all loaded Eng Hub and GitHub settings

**Status:** Done

**Acceptance criteria:** Given valid Eng Hub and GitHub configuration with representative values for every field, when the Settings pane opens, then every value is shown in its corresponding control without rewriting either config.

**Expected edits:**
- New `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/screen/EngHubSettingsScreen.kt`
- New `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/state/EngHubSettingsUiState.kt`
- New `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubSettingsViewModel.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHubDependencies.kt`
- `utilities/src/commonMain/kotlin/com/github/karlsabo/github/config/GitHubConfig.kt`
- New settings tests under `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/`

**Scope:** Load and render controls for every `EngHubConfig` field, every `LocalRepositoryConfig` path/command, GitHub `tokenPath`, and the masked token. No control writes files yet. Do not add a reveal button or put token contents in semantics, logs, errors, or screenshot fixtures.

**Notes:** Runtime loading currently resolves raw GitHub config directly into `GitHubApiRestConfig`; Settings also needs the serializable `GitHubConfig` and `GitHubSecret` values. Keep draft settings ownership outside replaceable polling state.

### 4. Auto-persist a general setting

**Acceptance criteria:** Given `gitHubAuthor` is `octocat`, when the user changes it to `hubot` and leaves the field idle for 750 ms, then `eng-hub-config.json` contains `hubot` without a save action.

**Expected edits:**
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubSettingsViewModel.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/state/EngHubSettingsUiState.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHubConfig.kt`
- Settings view-model tests under `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/`

**Scope:** Establish immediate draft updates, 750 ms text debounce, validation-before-write, and auto-persistence through `gitHubAuthor`. No save/cancel/apply controls and no success indication. Collection actions and runtime refresh are out.

**Notes:** Persist syntactically valid partial configuration even when unrelated feature-readiness fields are incomplete. Serialize writes so an older debounce cannot overwrite a newer draft.

### 5. Refresh polling after a committed interval change

**Acceptance criteria:** Given the running app polls every 600 seconds, when the user commits 300 seconds, then obsolete polling stops and replacement polling uses `300000` milliseconds without restarting Eng Hub.

**Expected edits:**
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHub.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHubDependencies.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModel.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/NotificationPolling.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/PullRequestPolling.kt`
- Dependency/polling tests under `eng-hub/src/commonTest/` and `eng-hub/src/jvmTest/`

**Scope:** Make config-driven polling replaceable/reactive after successful persistence. Stop obsolete infinite pollers while finite setup/worktree operations continue in a longer-lived action scope. Preserve Settings selection and draft state.

**Notes:** Do not retain entire old view models merely to preserve finite jobs; that would leak their polling loops. Whole seconds are converted to milliseconds only at the config boundary.

### 6. Reject an invalid polling interval

**Acceptance criteria:** Given the persisted interval is 600 seconds, when the user enters `0`, then the field shows an actionable error and persisted/runtime configuration remains at 600 seconds.

**Expected edits:**
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/state/EngHubSettingsUiState.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubSettingsViewModel.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/screen/EngHubSettingsScreen.kt`
- Settings validation tests under `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/`

**Scope:** Validate both polling controls as positive whole seconds and retain invalid text as a visible draft without persisting or refreshing runtime behavior. Other validation rules are out unless needed by existing controls.

**Notes:** Persistence validity and pane readiness are separate. Invalid text must not be silently coerced.

### 7. Add an organization ID

**Acceptance criteria:** Given organization ID `acme`, when the user adds `widgets`, then the auto-persisted config contains `acme` and `widgets`.

**Expected edits:**
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/screen/EngHubSettingsScreen.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/state/EngHubSettingsUiState.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubSettingsViewModel.kt`
- Organization editor tests under `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/`

**Scope:** Structured organization rows and immediate add persistence. Reject blank or duplicate IDs inline. Removing IDs is out.

**Notes:** At least one organization is not required; an empty list is valid and later yields an explanatory Pull Requests empty state.

### 8. Remove an organization ID

**Acceptance criteria:** Given organization IDs `acme` and `example`, when the user removes `example`, then the auto-persisted config contains only `acme`.

**Expected edits:**
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/screen/EngHubSettingsScreen.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/state/EngHubSettingsUiState.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubSettingsViewModel.kt`
- Organization editor tests under `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/`

**Scope:** Remove one organization row and persist the remaining ordered values. Adding and reordering are out.

**Notes:** Removing the final organization is allowed.

### 9. Add and position a setup command

**Acceptance criteria:** Given `/workspace/api` has `direnv allow`, when the user adds `cp .env.example .env` before it, then the auto-persisted repository config contains both commands in that order.

**Expected edits:**
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/screen/EngHubSettingsScreen.kt`
- New setup-command editor composable under `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/`
- Settings state/view-model files
- Setup-command editor tests under `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/`

**Scope:** Add nonblank setup commands and control their order within an existing repository. Repository ordering is out. The editor may expose edit/remove controls, but this acceptance slice proves add plus ordering only.

**Notes:** Preserve command strings exactly; they may contain shell quoting and Eng Hub placeholders.

### 10. Add a configured local repository

**Acceptance criteria:** Given Settings does not contain `/workspace/web`, when the user adds it, then the auto-persisted config includes `/workspace/web`.

**Expected edits:**
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/screen/EngHubSettingsScreen.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/state/EngHubSettingsUiState.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubSettingsViewModel.kt`
- Local repository editor tests under `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/`

**Scope:** Add one repository path with an empty command list and persist immediately. Reject blank and duplicate normalized paths. Do not inspect, clone, create, or mutate the directory on disk.

**Notes:** Normalize only for duplicate comparison; preserve the user's configured path representation unless existing config conventions require normalization.

### 11. Remove a configured local repository

**Acceptance criteria:** Given Settings contains `/workspace/old`, when the user removes it, then the auto-persisted config no longer includes `/workspace/old`.

**Expected edits:**
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/screen/EngHubSettingsScreen.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/state/EngHubSettingsUiState.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubSettingsViewModel.kt`
- Local repository editor tests under `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/`

**Scope:** Remove the complete config entry and persist immediately. Never delete repository files or worktrees. The short-lived Undo affordance is added separately in story 20.

**Notes:** A finite worktree/setup action already running for the removed repository may finish in its long-lived action scope; its result no longer appears in current UI state.

### 12. Choose a repositories base directory

**Acceptance criteria:** Given the repositories base directory control is empty, when the user chooses `/workspace` through Browse, then the field and auto-persisted config contain `/workspace`.

**Expected edits:**
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/screen/EngHubSettingsScreen.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubSettingsViewModel.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/DirectoryPicker.kt`
- `eng-hub/src/jvmMain/kotlin/com/github/karlsabo/devlake/enghub/SwingDirectoryPicker.kt`
- Directory-control tests under `eng-hub/src/commonTest/`

**Scope:** Wire editable text plus Browse for directory-valued controls, using `repositoriesBaseDir` as the acceptance example. The same reusable control covers `planningMarkdownDir` and local repository paths. Typed paths may be blank where allowed and need not exist.

**Notes:** Picker selection is a discrete action and persists immediately rather than waiting 750 ms.

### 13. Replace the GitHub token

**Acceptance criteria:** Given Settings loaded an existing masked token, when the user replaces it with `github_pat_new` and the debounce elapses, then the referenced secret JSON contains `github_pat_new` while both non-secret config files contain no token.

**Expected edits:**
- `utilities/src/commonMain/kotlin/com/github/karlsabo/github/config/GitHubConfig.kt`
- New `utilities/src/commonMain/kotlin/com/github/karlsabo/github/config/GitHubConfigStore.kt`
- Settings screen/state/view-model files
- GitHub config tests under `utilities/src/commonTest/` and settings tests under `eng-hub/src/commonTest/`

**Scope:** Masked token replacement and secret-only persistence. No reveal control. Exclude token values from logs, errors, semantics/content descriptions, and test screenshots. Secret path changes and new-file permissions are separate stories.

**Notes:** Do not rewrite an unchanged loaded token. GitHub API clients refresh only after both secret and non-secret config are valid and persisted.

### 14. Protect a newly created GitHub secret

**Acceptance criteria:** Given Eng Hub is running on POSIX and the selected secret does not exist, when the token is committed, then the created secret file has mode `0600`.

**Expected edits:**
- Common secret-writer abstraction under `utilities/src/commonMain/kotlin/com/github/karlsabo/github/config/`
- POSIX/JVM implementation under `utilities/src/jvmMain/kotlin/com/github/karlsabo/github/config/`
- Platform-aware tests under `utilities/src/jvmTest/`
- Settings error propagation tests under `eng-hub/src/commonTest/`

**Scope:** Owner-only POSIX permissions for created/replaced secrets and preservation of restrictive existing permissions where possible. Windows relies on the user's default ACL without Unix-mode emulation. OS keychain integration is out.

**Notes:** Apply permission checks before treating the secret write as committed or updating `github-config.json`.

### 15. Create GitHub access files from Settings

**Acceptance criteria:** Given `github-config.json` and the selected secret path do not exist, when the user enters the secret path and a token, then Eng Hub creates valid `GitHubConfig` and `GitHubSecret` files and enables panes whose readiness requirements are met.

**Expected edits:**
- `utilities/src/commonMain/kotlin/com/github/karlsabo/github/config/GitHubConfig.kt`
- `utilities/src/commonMain/kotlin/com/github/karlsabo/github/config/GitHubConfigStore.kt`
- New file-picker abstraction under `eng-hub/src/commonMain/` with JVM implementation under `eng-hub/src/jvmMain/`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHubDependencies.kt`
- Settings and dependency tests

**Scope:** Editable secret path, file selection, first-time secret/config creation, and feature readiness refresh. Notifications requires a readable nonblank token; Pull Requests additionally requires nonblank `gitHubAuthor`; Worktrees depends only on local settings. Disabled panes remain visible and explain missing requirements in a tooltip.

**Notes:** Write the secret successfully before pointing `github-config.json` to it. Changing an existing path copies the loaded token to the new path first. POSIX permission enforcement is provided by story 14.

### 16. Open Settings when Eng Hub config is missing

**Acceptance criteria:** Given `eng-hub-config.json` does not exist, when Eng Hub launches, then it opens the Settings pane with default draft values and does not show the terminal bootstrap error.

**Expected edits:**
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHub.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHubDependencies.kt`
- Startup tests under `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/`
- `eng-hub/README.md`

**Scope:** Separate settings-file loading from runtime dependency construction and support incomplete first-launch state. Persist each syntactically valid partial edit; do not write defaults merely because the app launched. Other panes stay visible with readiness tooltips.

**Notes:** Reuse the normal Settings pane; do not build a separate onboarding form or add cancel/save buttons.

### 17. Recover Eng Hub config from its backup

**Acceptance criteria:** Given `eng-hub-config.json` is invalid and `eng-hub-config.json.bak` is valid, when Eng Hub launches, then it loads the backup values and exposes them in Settings.

**Expected edits:**
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHubConfig.kt`
- Potential shared recoverable JSON persistence under `utilities/src/commonMain/kotlin/com/github/karlsabo/tools/`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHub.kt`
- Recovery tests using temporary files
- `eng-hub/README.md`

**Scope:** Load primary, fall back to `.json.bak` on decode failure, and expose recovered values. If neither is valid, use defaults and open Settings. Do not salvage individual fields from malformed JSON.

**Notes:** Saving uses `.json.new`, decode verification, valid-primary-to-backup rotation, and final promotion. If promotion fails, restore the backup best-effort and report failure. The same store pattern is reused for GitHub config; secret backups are never auto-loaded.

### 18. Report an auto-save failure

**Acceptance criteria:** Given a valid persisted value and a storage failure, when the user commits a replacement value, then Settings shows an actionable persistence error and runtime behavior continues using the previously persisted value.

**Expected edits:**
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/screen/EngHubSettingsScreen.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/state/EngHubSettingsUiState.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubSettingsViewModel.kt`
- Recoverable persistence code under `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHubConfig.kt` and/or `utilities/src/commonMain/kotlin/com/github/karlsabo/tools/`
- Failure tests under `eng-hub/src/commonTest/` and `utilities/src/commonTest/` as ownership requires

**Scope:** File-write/rotation failure reporting and last-valid runtime retention. Keep the user's draft visible for correction or retry. Successful saves remain visually quiet.

**Notes:** Never refresh runtime from an unpersisted draft. Do not leak token content in GitHub-related errors.

### 19. Flush a pending edit during navigation

**Acceptance criteria:** Given `gitHubAuthor` has a pending valid edit to `hubot`, when the user changes panes before the 750 ms debounce elapses, then `hubot` is persisted before navigation completes.

**Expected edits:**
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubSettingsViewModel.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/screen/EngHubScreen.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHub.kt`
- Debounce/lifecycle tests under `eng-hub/src/commonTest/`

**Scope:** Flush valid pending text on pane navigation. Use the same flush path for window close/application exit. Invalid drafts remain unsaved, and navigation/exit is never blocked by confirmation.

**Notes:** Ensure debounce cancellation cannot issue a second stale write after the flush.

### 20. Undo local repository removal

**Acceptance criteria:** Given `/workspace/api` has two ordered setup commands, when the user removes it and selects Undo, then the auto-persisted config again contains `/workspace/api` with both commands in their original order and no repository files were deleted.

**Expected edits:**
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/screen/EngHubSettingsScreen.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/state/EngHubSettingsUiState.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubSettingsViewModel.kt`
- Local repository undo tests under `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/`

**Scope:** Short-lived inline Undo for repository config removal. Restore the exact removed `LocalRepositoryConfig`, including command order. No modal confirmation and no disk deletion.

**Notes:** This is intentionally the only positive-operation indication: it makes an immediately persisted destructive config edit reversible.

### 21. Invoke a filtered action by keyboard

**Acceptance criteria:** Given the action popup is open, when the user types `setings`, presses Down to highlight `Settings`, and presses Enter, then Settings opens.

**Expected edits:**
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/EngHubActionPopup.kt`
- Action popup tests under `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/component/`

**Scope:** Focus search when opened, filter as the user types, move highlight with Up/Down, and invoke the highlighted match with Enter. Mouse behavior remains supported.

**Notes:** Keep keyboard selection valid as filtering changes the result list.

### 22. Dismiss the action popup by keyboard

**Acceptance criteria:** Given the action popup is open from the three-dots trigger, when the user presses Escape, then the popup closes and focus returns to the trigger.

**Expected edits:**
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/EngHubActionPopup.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/screen/EngHubScreen.kt`
- Component/screen tests under `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/`

**Scope:** Escape dismissal and deterministic focus restoration only.

**Notes:** Dismissal must not navigate or invoke the highlighted action.

### 23. Persist the worktree polling interval

**Acceptance criteria:** Given the worktree polling interval is 120 seconds, when the user changes it to 60 and leaves it idle for 750 ms, then `worktreePollIntervalMs` is persisted as `60000`.

**Expected edits:**
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/screen/EngHubSettingsScreen.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/state/EngHubSettingsUiState.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubSettingsViewModel.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/LocalRepositoryController.kt`
- Settings and worktree polling tests under `eng-hub/src/commonTest/`

**Scope:** Positive whole-second editing, millisecond conversion, persistence, and replacement of the obsolete local-repository polling delay. Pull-request/notification polling is already covered by story 5.

**Notes:** A valid interval commit follows the same debounce and runtime-refresh rules as other settings.

### 24. Persist the setup shell

**Acceptance criteria:** Given the setup shell is `/bin/zsh`, when the user changes it to `/bin/bash` and leaves it idle for 750 ms, then the config contains `/bin/bash` for subsequent setup commands.

**Expected edits:**
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/screen/EngHubSettingsScreen.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/state/EngHubSettingsUiState.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubSettingsViewModel.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/WorktreeSetupCommands.kt`
- Settings/setup tests under `eng-hub/src/commonTest/`

**Scope:** Nonblank setup-shell editing and persistence. Already-running setup actions retain the shell they started with; subsequent actions use the committed shell.

**Notes:** A blank shell may persist as an incomplete partial draft only if the persisted model remains syntactically valid, but setup actions stay unavailable until the shell is nonblank.

### 25. Edit a configured repository path

**Acceptance criteria:** Given Settings contains repository `/workspace/old`, when the user changes its path to `/workspace/new` through Browse, then the auto-persisted repository entry uses `/workspace/new` and retains its setup commands.

**Expected edits:**
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/screen/EngHubSettingsScreen.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/state/EngHubSettingsUiState.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubSettingsViewModel.kt`
- Directory picker abstraction/implementation under `eng-hub/src/commonMain/` and `eng-hub/src/jvmMain/`
- Local repository settings tests under `eng-hub/src/commonTest/`

**Scope:** Replace the path of one existing `LocalRepositoryConfig`, preserve its ordered commands, reject duplicate normalized paths, and refresh current repository UI from committed config. Do not move or modify directories on disk.

### 26. Edit a setup command

**Acceptance criteria:** Given `/workspace/api` has setup command `direnv allow`, when the user changes it to `direnv allow .` and leaves it idle for 750 ms, then the auto-persisted command is `direnv allow .`.

**Expected edits:**
- Setup-command editor under `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/state/EngHubSettingsUiState.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubSettingsViewModel.kt`
- Setup-command editor tests under `eng-hub/src/commonTest/`

**Scope:** Debounced text editing of one command while preserving its position. Reject blank command rows and preserve shell text exactly.

### 27. Remove a setup command

**Acceptance criteria:** Given `/workspace/api` has setup commands `cp .env.example .env` and `direnv allow`, when the user removes `cp .env.example .env`, then the auto-persisted repository retains only `direnv allow`.

**Expected edits:**
- Setup-command editor under `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/state/EngHubSettingsUiState.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubSettingsViewModel.kt`
- Setup-command editor tests under `eng-hub/src/commonTest/`

**Scope:** Immediate removal persistence for one setup command while retaining remaining order. Removing the final command is allowed.

## Implementation sequence and trade-offs

1. Stories 1–2 establish both navigation routes to one placeholder Settings pane.
2. Stories 3–6 establish the settings tracer bullet: load → draft → auto-persist → runtime refresh → validation.
3. Stories 7–12 add organization, repository, command, and directory controls on that state model. Collection editing remains isolated from startup recovery.
4. Stories 13–15 add credential editing, secure secret persistence, and pane readiness. Credential persistence is harder to reverse than ordinary Eng Hub fields and remains isolated for review.
5. Stories 16–18 add first-launch, backup recovery, and persistence-failure behavior after the normal editor works.
6. Stories 19–20 harden pending edits and reversible removal.
7. Stories 21–22 complete keyboard behavior without coupling it to settings persistence.
8. Stories 23–27 close the remaining editable-config coverage for worktree polling, setup shell, repository paths, and command edit/removal. They reuse the established controls and persistence model.

Successful auto-save stays visually quiet; validation and storage failures are explicit. This trades confirmation for a modern low-friction UX without accepting silent data loss.

## Out of scope unless explicitly added

- Settings for other repository applications.
- Import/export, raw JSON editing, or cloud sync.
- Repository ordering when no runtime behavior depends on it.
- Field-level salvage from syntactically malformed JSON unless the recovery answer explicitly requires it.
- Platform-specific visual polish beyond usable Compose Desktop behavior.
