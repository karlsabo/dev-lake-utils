# Worktree Repository Panel Plan

**Goal**: Add an Eng Hub Worktrees view, reached from IntelliJ-style sidebar icon navigation, where users can add local git repositories, expand each repository into its worktrees, and run worktree actions from per-worktree menus.

**Context**:

- Eng Hub is a Compose Desktop app with two current panes, `Pull Requests` and `Notifications`, rendered from `EngHubScreen` (`eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/screen/EngHubScreen.kt:31`).
- The existing `Setup` action for PRs and notifications is `EngHubViewModel.checkoutAndOpen`, which derives a repo path from `repositoriesBaseDir`, ensures the repo exists, ensures a branch worktree exists, and runs configured setup commands (`eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModel.kt:210`).
- Config already contains `repositoriesBaseDir`, `worktreeSetupCommands`, and `setupShell`, but it does not contain a user-managed list of local repositories to show as top-level rows (`eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHubConfig.kt:15`).
- Worktree operations already exist behind `GitWorktreeApi`: `listWorktrees` and `removeWorktree` are available, but there is no repo discovery or repository list persistence API yet (`utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitWorktreeApi.kt:3`).
- `GitWorktreeService.listWorktrees` parses `git worktree list --porcelain`, and `removeWorktree` currently calls `git worktree remove <path>` (`utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitWorktreeService.kt:65`, `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitWorktreeService.kt:75`).
- `DesktopLauncherService.openInIdea` exists and runs `idea <projectPath>`, but current setup does not call it directly. The README notes that setup only opens an editor if configured setup commands do that (`utilities/src/commonMain/kotlin/com/github/karlsabo/system/DesktopLauncherService.kt:25`, `eng-hub/README.md:192`).

## Resolved Decisions

- `Open` means run the configured setup commands for that repository/worktree. The setup command list is expected to eventually call `idea .`; Eng Hub should not separately force an IDEA launch.
- Adding a repository should use an OS directory picker.
- Added repositories should be persisted in `eng-hub-config.json`.
- Eng Hub should prevent duplicate local repository roots.
- If the directory picker selects a linked worktree, Eng Hub should detect the canonical main repository root, store that root once, and show the selected worktree under that repository in the UI.
- If canonical root detection fails for a selected git directory or worktree, Eng Hub should reject it and ask the user to add the root directory.
- `Archive` must not allow removing the root/main worktree.
- Dirty worktree archive should prompt with a force-remove option.
- The main Eng Hub UI should move from stacked panels to IntelliJ-style sidebar navigation: a narrow left rail with small square icon buttons for `Pull Requests`, `Notifications`, and `Worktrees`. Emoji icons are acceptable for the first pass.
- Repositories in the Worktrees tree should be sorted alphabetically by folder name, for example `app`, `fender`, `infrastructure`, `k-repo`.
- Worktree rows should show branch name and dirty status. They do not need to show full path, commit hash, or last modified time in the first pass.
- Worktree status should use a green light emoji for clean worktrees and a yellow light emoji for dirty worktrees.
- Worktrees for all configured repositories should refresh through local polling at a separate interval from PRs and notifications, with a default of 2 minutes.
- Polling should automatically discover linked worktrees for configured repository roots, including worktrees created outside Eng Hub.
- Archive should run `git worktree remove`, delete any leftover checkout directory, and run `git worktree prune`.

## Acceptance Tests

1. **Move Eng Hub panes into sidebar navigation**
    - Given Eng Hub is running, when I view the main window, then I can switch between `Pull Requests`, `Notifications`, and `Worktrees` using small square icon buttons in a left sidebar without losing the existing pull request and notification behavior.

2. **Add a local repository**
    - Given `eng-hub-config.json` contains local repositories named `k-repo`, `app`, `infrastructure`, and `fender`, when I open the Worktrees view, then I see top-level repository rows ordered `app`, `fender`, `infrastructure`, `k-repo`.

3. **Add a local repository with a directory picker**
    - Given Eng Hub is running with no configured local repositories, when I pick linked worktree `/Users/karl.sabo/git/dev-lake-utils-feature-worktree-panel` from the Worktrees view directory picker, then Eng Hub writes canonical root `/Users/karl.sabo/git/dev-lake-utils` to `eng-hub-config.json` and shows `dev-lake-utils` as a top-level repository row with `feature/worktree-panel` underneath it.

4. **Expand a repository into worktrees**
    - Given `/Users/karl.sabo/git/dev-lake-utils` is a configured local repository and git reports worktrees for `main` and `feature/worktree-panel`, when I expand the `dev-lake-utils` repository row, then I see child worktree rows for both branch names.

5. **Show dirty status when expanding worktrees**
    - Given `/Users/karl.sabo/git/dev-lake-utils` is a configured local repository with clean `main` and dirty `feature/worktree-panel` worktrees, when I expand the `dev-lake-utils` repository row, then the `main` row shows a green light emoji and the `feature/worktree-panel` row shows a yellow light emoji.

6. **Poll all configured repositories every 2 minutes**
    - Given `dev-lake-utils` is configured and linked worktree `feature/worktree-panel` is created outside Eng Hub, when the 2-minute local worktree poll interval elapses, then the next time I view or expand `dev-lake-utils`, the `feature/worktree-panel` worktree row appears with current dirty status without refreshing PRs or notifications.

7. **Open an existing worktree**
    - Given the `dev-lake-utils` repository row is expanded and shows `/Users/karl.sabo/git/dev-lake-utils-feature-worktree-panel`, when I choose `Open` from that worktree row menu, then Eng Hub runs the same configured worktree setup behavior used by notification setup for that repo/worktree and reports any setup failure through the existing action error dialog.

8. **Prevent archiving the root worktree**
    - Given the `dev-lake-utils` repository row is expanded and shows root worktree `/Users/karl.sabo/git/dev-lake-utils`, when I open that worktree row menu, then `Archive` is not available for the root worktree.

9. **Archive a clean non-root worktree**
    - Given the `dev-lake-utils` repository row is expanded and shows clean non-root worktree `/Users/karl.sabo/git/dev-lake-utils-feature-worktree-panel`, when I choose `Archive` from that worktree row menu and confirm, then Eng Hub removes the git worktree, deletes any leftover checkout directory, prunes worktree metadata, and the row disappears from the expanded worktree list.

10. **Force archive a dirty non-root worktree**
- Given normal archive fails because `/Users/karl.sabo/git/dev-lake-utils-feature-worktree-panel` is dirty, when I confirm force removal, then Eng Hub force-removes the git worktree, deletes any leftover checkout directory, prunes worktree metadata, and the row disappears from the expanded worktree list.

## Stories

### 1. Move Main Eng Hub Panes Into Sidebar Navigation - Done

**Done:** 2026 05 05

**Acceptance criteria:** Given Eng Hub is running, when I view the main window, then I can switch between `Pull Requests`, `Notifications`, and `Worktrees` using small square icon buttons in a left sidebar without losing the existing pull request and notification behavior.

**Expected edits:** `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/screen/EngHubScreen.kt`, existing panel component call sites under `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/`, and focused UI-state tests if the project has a lightweight Compose testing pattern available.

**Scope:** Replace the current vertically stacked `Pull Requests` and `Notifications` layout with a narrow left navigation rail and one active content pane, keep the existing PR and notification panels behaviorally unchanged inside their views, and add an empty `Worktrees` view placeholder. Sidebar buttons should be small square icon buttons, similar in spirit to IntelliJ tool window buttons; emoji icons are acceptable for the initial implementation. This story does not add repository persistence or git worktree listing.

**Notes:** This is a layout slice so the later Worktrees stories do not have to also migrate the main screen structure. Preserve the existing state collection in `EngHubScreen` and only change where the existing panels are rendered. Use tooltips or accessible labels so emoji-only buttons still have clear names.

### 2. Render Configured Repositories In Worktrees View - Done

**Done:** 2026 05 05

**Acceptance criteria:** Given `eng-hub-config.json` contains local repositories named `k-repo`, `app`, `infrastructure`, and `fender`, when I open the Worktrees view, then I see top-level repository rows ordered `app`, `fender`, `infrastructure`, `k-repo`.

**Expected edits:** `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHubConfig.kt`, `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModel.kt`, `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/screen/EngHubScreen.kt`, a new `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/state/LocalRepositoryUiState.kt`, and a new component under `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/`.

**Scope:** Add config support for a user-managed list of local repository root paths and render the saved paths as alphabetically sorted top-level rows in the Worktrees view. This story does not add the directory picker, validation, child worktree listing, or row actions.

**Notes:** `EngHubConfig` is already serializable and saved with `saveEngHubConfig`, so the smallest persistence path is likely adding `localRepositories: List<String> = emptyList()` to the config model. Render the folder name as the row title and the absolute path as secondary text. Sort by folder name, not by full path, so `/Users/karl.sabo/git/app` appears before `/Users/karl.sabo/git/fender`.

### 3. Add Local Repository With Directory Picker - Done

**Done:** 2026 05 05

**Acceptance criteria:** Given Eng Hub is running with no configured local repositories, when I pick linked worktree `/Users/karl.sabo/git/dev-lake-utils-feature-worktree-panel` from the Worktrees view directory picker, then Eng Hub writes canonical root `/Users/karl.sabo/git/dev-lake-utils` to `eng-hub-config.json` and shows `dev-lake-utils` as a top-level repository row with `feature/worktree-panel` underneath it.

**Expected edits:** `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModel.kt`, the Worktrees view component, `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHubConfig.kt` if save helpers need adjustment, and focused tests for config mutation/validation.

**Scope:** Add an OS directory picker action, validate that the selected path is a git repository or linked worktree, resolve the selected path to the canonical main repository root, prevent adding a duplicate repository root, append the canonical root to the config-backed local repository list, save `eng-hub-config.json`, and update the visible tree. This story may list the selected repo's worktrees only enough to show the picked worktree under the root; it does not implement row actions.

**Notes:** Validation can use existing `GitCommandApi.isGitRepository` through a new small repository-facing method if the current `GitWorktreeApi` surface stays worktree-only. Canonical root detection can use git metadata such as `git rev-parse --git-common-dir`/`--show-toplevel` plus worktree list parsing, as long as linked worktrees resolve to the same stored root as the main checkout. Use the current global `actionError` dialog for invalid paths. Directory picker implementation is a Compose Desktop/JVM concern; keep it behind a small launcher abstraction if that avoids putting AWT/Swing directly into the composable.

### 4. Expand Repository To Show Worktrees - Done

**Done:** 2026 05 08

**Acceptance criteria:** Given `/Users/karl.sabo/git/dev-lake-utils` is a configured local repository and git reports worktrees for `main` and `feature/worktree-panel`, when I expand the `dev-lake-utils` repository row, then I see child worktree rows for both branch names.

**Expected edits:** `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModel.kt`, `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/state/LocalRepositoryUiState.kt`, the new worktree panel component, and focused tests under `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/`.

**Scope:** Add expandable/collapsible repository rows, load `GitWorktreeApi.listWorktrees(repoRoot)` for expanded repositories, and render child rows with branch names. This story does not add dirty status, local polling, `Open`, or `Archive` actions.

**Notes:** The git layer already parses porcelain worktree output into `Worktree(path, branch, commitHash)` (`utilities/src/commonMain/kotlin/com/github/karlsabo/git/Worktree.kt`). Keep this first expand story focused on the tree structure: repo rows at the top level, worktree rows underneath expanded repo roots.

### 5. Show Dirty Status When Expanding Worktrees - Done

**Done:** 2026 05 08

**Acceptance criteria:** Given `/Users/karl.sabo/git/dev-lake-utils` is a configured local repository with clean `main` and dirty `feature/worktree-panel` worktrees, when I expand the `dev-lake-utils` repository row, then the `main` row shows a green light emoji and the `feature/worktree-panel` row shows a yellow light emoji.

**Expected edits:** `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModel.kt`, `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitWorktreeApi.kt`, `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitWorktreeService.kt`, `utilities/src/commonMain/kotlin/com/github/karlsabo/git/Worktree.kt` if the model carries dirty status, and tests under `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/` plus `utilities/src/commonTest/kotlin/com/github/karlsabo/git/GitWorktreeServiceTest.kt` if the git API changes.

**Scope:** Compute dirty status when loading worktrees for an expanded repository and render a green/yellow light emoji beside each branch name. This story does not add periodic polling, `Open`, or `Archive` actions.

**Notes:** Dirty status can be based on `git status --porcelain` for the worktree path. Keep the row display compact: branch name plus a green light for clean or yellow light for dirty.

### 6. Poll All Configured Repositories Every 2 Minutes - Done

**Done:** 2026 05 08

**Acceptance criteria:** Given `dev-lake-utils` is configured and linked worktree `feature/worktree-panel` is created outside Eng Hub, when the 2-minute local worktree poll interval elapses, then the next time I view or expand `dev-lake-utils`, the `feature/worktree-panel` worktree row appears with current dirty status without refreshing PRs or notifications.

**Expected edits:** `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHubConfig.kt`, `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModel.kt`, and tests under `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/`.

**Scope:** Add a local worktree polling interval separate from `pollIntervalMs`, default it to 2 minutes, and refresh worktree state for all configured repositories on that cadence, including repositories whose rows are currently collapsed. Polling should discover added or removed linked worktrees created outside Eng Hub. This story does not add dirty-status computation itself; it reuses the dirty-status loading from the previous story.

**Notes:** Use a new config value such as `worktreePollIntervalMs`. Verify the worktree polling flow does not trigger PR or notification refreshes. If a user tries to add a linked worktree whose canonical root is already configured, treat it as a duplicate root; the poller should already discover that worktree under the existing root.

### 7. Open Worktree From Row Menu - Done

**Done:** 2026 05 08

**Acceptance criteria:** Given the `dev-lake-utils` repository row is expanded and shows `/Users/karl.sabo/git/dev-lake-utils-feature-worktree-panel`, when I choose `Open` from that worktree row menu, then Eng Hub runs the same configured worktree setup behavior used by notification setup for that repo/worktree and reports any setup failure through the existing action error dialog.

**Expected edits:** the new worktree panel component, `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModel.kt`, and tests under `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/`.

**Scope:** Add a per-worktree menu button, add `Open`, run `runConfiguredWorktreeSetup(repoRoot, worktreePath, config)`, expose per-worktree progress state, and route failures to `actionError`. This story does not add archive.

**Notes:** This deliberately matches current setup semantics rather than forcing `DesktopLauncher.openInIdea`, because the configured setup commands are expected to eventually call `idea .` (`eng-hub/README.md:112`). Reuse the same setup-command behavior as current PR/notification setup, but target the existing selected worktree path rather than ensuring a new branch worktree.

### 8. Prevent Archiving Root Worktree - Done

**Done:** 2026 05 08

**Acceptance criteria:** Given the `dev-lake-utils` repository row is expanded and shows root worktree `/Users/karl.sabo/git/dev-lake-utils`, when I open that worktree row menu, then `Archive` is not available for the root worktree.

**Expected edits:** the Worktrees view component, `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/state/LocalRepositoryUiState.kt`, and focused component/view-model tests if available.

**Scope:** Mark worktree rows as root vs non-root by comparing the worktree path to the configured repository root path, and hide or disable `Archive` for the root worktree only. This story does not remove any worktrees.

**Notes:** Root/main worktree detection should compare the worktree path to the configured repository root path, not just branch name, because branch labels are user-controlled. Prefer hiding the destructive menu item entirely for root rows unless the component pattern strongly favors disabled menu items.

### 9. Archive Clean Non-Root Worktree

**Acceptance criteria:** Given the `dev-lake-utils` repository row is expanded and shows clean non-root worktree `/Users/karl.sabo/git/dev-lake-utils-feature-worktree-panel`, when I choose `Archive` from that worktree row menu and confirm, then Eng Hub removes the git worktree, deletes any leftover checkout directory, prunes worktree metadata, and the row disappears from the expanded worktree list.

**Expected edits:** the Worktrees view component, `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModel.kt`, `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitWorktreeApi.kt`, `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitWorktreeService.kt`, and tests under `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/` plus `utilities/src/commonTest/kotlin/com/github/karlsabo/git/GitWorktreeServiceTest.kt`.

**Scope:** Add `Archive` to non-root worktree row menus, show a confirmation dialog naming the exact path, call normal worktree removal, delete any leftover checkout directory after successful git removal, run worktree prune, refresh the repo's worktree state on success, and show `actionError` if removal fails. This story should not add force removal.

**Notes:** `git worktree remove` normally removes the checkout directory for a clean worktree, but this story should make leftover directory deletion an explicit outcome. Add a prune-capable git API shape, such as `pruneWorktrees(repoRoot)`, or include pruning inside a higher-level archive method if that better fits the existing service. If normal removal fails because the worktree is dirty, this story can show the failure through `actionError`; the force retry path is a separate story.

### 10. Force Archive Dirty Non-Root Worktree

**Acceptance criteria:** Given normal archive fails because `/Users/karl.sabo/git/dev-lake-utils-feature-worktree-panel` is dirty, when I confirm force removal, then Eng Hub force-removes the git worktree, deletes any leftover checkout directory, prunes worktree metadata, and the row disappears from the expanded worktree list.

**Expected edits:** the Worktrees view component, `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModel.kt`, `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitWorktreeApi.kt`, `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitWorktreeService.kt`, tests under `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/`, and `utilities/src/commonTest/kotlin/com/github/karlsabo/git/GitWorktreeServiceTest.kt`.

**Scope:** Detect a normal archive failure that indicates a dirty worktree, present a force-remove confirmation naming the exact path, call a force-capable worktree removal API, delete any leftover checkout directory after successful forced git removal, run worktree prune, refresh the repo's worktree state on success, and show `actionError` if forced removal fails.

**Notes:** Add a force-capable git API shape, such as `removeWorktree(worktreePath, force: Boolean = false)`, only in this story. The git implementation should call `git worktree remove --force <path>` for forced removal and keep the existing non-force behavior unchanged. Preserve the prune behavior introduced by the clean archive story.

## Remaining Questions

None.
