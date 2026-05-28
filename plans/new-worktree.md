# New Worktree Creation

## Stories

These stories are intentionally small. Each story should be one PR with one primary acceptance test. Later stories may depend on earlier utility/API seams, but each story includes enough context to pick up without reading the removed Q&A notes.

### Shared context and decisions

- Entry point is the existing Worktrees pane. The PR/notifications pane already has the desired setup behavior and should not change for this feature.
- Each story must leave Eng Hub operable after merge: the app compiles, existing Worktrees pane list/open/archive behavior still works, and PR/notification checkout setup behavior is unchanged. Partial feature paths must be disabled, no-op safely, or report a caught user-facing error instead of throwing through Compose/coroutine boundaries. Before merging each story, run at least `./gradlew :utilities:check :eng-hub:check` or document why a narrower equivalent was used.
- Worktree row action label: `Create worktree`.
- Any visible worktree row can be a base, including the root/default worktree.
- Disable `Create worktree` while the selected base worktree is running setup or being archived.
- User enters a target branch name only. The target path uses the existing derived path convention: repo directory name plus sanitized branch name, e.g. `dev-lake-utils-feature-stacked-pr`.
- Branch validation: run/encapsulate `git check-ref-format --branch <branch>` and reject whitespace/control characters. UI validation must be backed by business-logic validation.
- Stacked branch creation uses `git worktree add -b <new-branch> <new-path> <base-branch>` from the selected/base context.
- If a target worktree already exists at the exact derived path, run setup there without ancestry validation.
- Run only `localRepositories[].setupCommands` after git worktree creation. Setup failure keeps the worktree and uses the existing setup error dialog behavior.
- After git worktree creation succeeds, refresh the repository row immediately while setup continues.
- Hierarchy is inferred from git ancestry only; do not store parent metadata. If no parent is found, parent selection is ambiguous, or ancestry/default-branch checks fail, show the worktree flat under the repository root and keep the Worktrees pane usable.
- Default branch/root inference should use the current branch upstream remote when possible and fall back to `origin/HEAD`.
- Background fetch failures before ancestry/hierarchy checks should not block the user. Log a warning for now.
- Detached worktrees can be used as a base by creating from the detached commit SHA, but detached-base children display flat because there is no parent branch. Until detached-base support is wired, never submit `(detached)` as a ref; disable the partial action or report a controlled error.
- Rebase-needed indicator starts with only this rule: parent branch has commits not contained in child. Conflicts are discovered when rebase runs.

Relevant current code:

- Worktrees UI: `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreePanel.kt`
- Worktrees screen wiring: `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/screen/EngHubScreen.kt`
- ViewModel setup/open flows: `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModel.kt`
- Setup command selection: `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/WorktreeSetupCommands.kt`
- Git/worktree services: `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitCommandApi.kt`, `GitCommandService.kt`, `GitWorktreeService.kt`, `WorktreeSetupCoordinator.kt`
- Worktree path sanitization: `utilities/src/commonMain/kotlin/com/github/karlsabo/git/WorktreePath.kt`
- UI state: `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/state/LocalRepositoryUiState.kt`

### 1. Validate branch names for worktree creation - Done

**Done** 2026 05 26

**Acceptance criteria:** Given a branch name with whitespace or control characters, when Eng Hub validates it for worktree creation, then validation fails before any git worktree command runs.

**Expected edits:**

- `utilities/src/commonMain/kotlin/com/github/karlsabo/git/...`
- `utilities/src/commonTest/kotlin/com/github/karlsabo/git/...`

**Scope:** Add a shared validator usable by UI and business logic. Do not add UI, worktree creation, or path-collision handling.

**Notes:** Validation should include `git check-ref-format --branch <branch>` semantics plus whitespace/control-character rejection.

### 2. Create a branch worktree from a selected branch base - Done

**Done** 2026 05 26

**Acceptance criteria:** Given an existing worktree on branch `feature/base-pr`, when worktree creation is requested for `feature/stacked-pr`, then git creates the derived worktree path using `git worktree add -b feature/stacked-pr <derived-path> feature/base-pr`.

**Expected edits:**

- `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitCommandApi.kt`
- `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitCommandService.kt`
- `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitWorktreeService.kt`
- `utilities/src/commonTest/kotlin/com/github/karlsabo/git/...`

**Scope:** Add the typed lower-level creation path and derived-path use. Do not run setup, add UI, support detached bases, or add polished conflict handling.

**Notes:** Keep base-branch behavior explicit; do not hide it inside the existing `ensureWorktree(repoPath, branch)` behavior.

### 3. Run setup after creating a branch worktree - Done

**Done** 2026 05 28

**Acceptance criteria:** Given setup command `touch setup-ran.txt`, when a setup request creates `feature/stacked-pr` from `feature/base-pr`, then setup commands run in the new worktree after git creation succeeds.

**Expected edits:**

- `utilities/src/commonMain/kotlin/com/github/karlsabo/git/WorktreeSetupCoordinator.kt`
- `utilities/src/commonTest/kotlin/com/github/karlsabo/git/WorktreeSetupCoordinatorTest.kt`

**Scope:** Add a create-from-base-then-setup flow and setup status keyed by the new worktree path. Keep the existing checkout/setup request path behavior unchanged. Do not add UI, hierarchy, ancestry override, or detached-base support.

### 4. Open a create-worktree dialog from a worktree row - Done

**Done** 2026 05 28

**Acceptance criteria:** Given worktree `feature/base-pr`, when the user chooses `Create worktree`, then a dialog opens showing `feature/base-pr` as the base and an empty target branch input.

**Expected edits:**

- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreePanel.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/screen/EngHubScreen.kt`
- `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreePanelTest.kt`

**Scope:** Add the row action and dialog open/dismiss state. Disable the action while the base worktree is running setup or being archived. If detached rows are visible before detached-base support lands, keep their create action disabled or show a controlled unsupported message; do not submit `(detached)` as a ref. Do not submit, validate, or create a worktree.

### 5. Validate create-worktree dialog input inline - Done

**Done** 2026 05 28

**Acceptance criteria:** Given the create-worktree dialog is open, when the user enters `feature/new dashboard`, then the create button is disabled and inline validation text is shown.

**Expected edits:**

- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreePanel.kt`
- `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreePanelTest.kt`

**Scope:** Use the shared validator from story 1 for visible dialog validation. Do not submit or run git.

### 6. Reject target branch equal to selected base branch - Done

**Done** 2026 05 28

**Acceptance criteria:** Given selected base branch `feature/base-pr`, when the user enters `feature/base-pr` as the target branch, then the create button is disabled and inline validation says the target branch must differ from the base branch.

**Expected edits:**

- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreePanel.kt`
- `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreePanelTest.kt`

**Scope:** Add this specific validation case in UI and business validation. Do not handle remote branch conflicts or branches checked out elsewhere.

### 7. Submit a valid branch from the dialog to the ViewModel

**Acceptance criteria:** Given base worktree `feature/base-pr` and target branch `feature/stacked-pr`, when the user confirms the dialog, then the Worktrees pane calls the ViewModel-facing callback with repo root path, base worktree path, base branch, and target branch.

**Expected edits:**

- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreePanel.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/screen/EngHubScreen.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModel.kt`
- tests in component/ViewModel layers

**Scope:** Wire UI to ViewModel method boundary. The ViewModel method may be a no-op/test-recorded seam if creation is not part of this PR.

### 8. Create and set up a stacked worktree from the submitted dialog

**Acceptance criteria:** Given the ViewModel receives base branch `feature/base-pr` and target branch `feature/stacked-pr`, when creation succeeds, then Eng Hub creates the derived worktree and starts setup for that worktree path.

**Expected edits:**

- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModel.kt`
- `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModelTest.kt`

**Scope:** Add business validation and create+setup invocation using existing configured setup commands. Creation/setup failures must be caught and reported through the existing action-error path so the app remains usable. Do not add immediate refresh timing, hierarchy display, ancestry override, detached-base stacking, or polished conflict handling.

### 9. Refresh the repository row after git creation before setup completes

**Acceptance criteria:** Given git worktree creation succeeds and setup is still running, when the repository row is visible, then the new worktree appears while setup status remains visible until setup completes.

**Expected edits:**

- `utilities/src/commonMain/kotlin/com/github/karlsabo/git/WorktreeSetupCoordinator.kt` if a creation callback/status transition is needed
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModel.kt`
- `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModelTest.kt`

**Scope:** Change refresh timing only. The post-create refresh is best-effort: log/report refresh failures without converting a successful create/setup operation into a failed setup. Do not change dialog behavior or create command semantics.

### 10. Report setup failure after creating a worktree without hiding the worktree

**Acceptance criteria:** Given setup command `exit 23`, when stacked worktree creation succeeds but setup fails, then Eng Hub shows the existing setup failure error and the Worktrees pane can refresh to show the created worktree.

**Expected edits:**

- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModel.kt`
- `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModelTest.kt`

**Scope:** Preserve/report post-create setup failure through the existing setup error dialog/action-error path. Refresh/list failures after the setup failure should not hide or delete the created worktree. Do not delete/archive the failed worktree or add retry setup.

### 11. Show branch checked out elsewhere error

**Acceptance criteria:** Given `feature/stacked-pr` is already checked out in another worktree at a different path, when the user tries to create `feature/stacked-pr` from `feature/base-pr`, then Eng Hub shows an error explaining that the branch is already checked out elsewhere.

**Expected edits:**

- `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitWorktreeService.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModel.kt`
- tests in utilities/ViewModel layers

**Scope:** Add the explicit error. Do not offer to open or run setup on the other worktree.

### 12. Show remote branch name conflict before creating a local branch

**Acceptance criteria:** Given `origin/feature/stacked-pr` exists and no local `feature/stacked-pr` branch/worktree exists, when the user creates `feature/stacked-pr` from `feature/base-pr`, then Eng Hub errors and asks the user to choose another branch name.

**Expected edits:**

- `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitCommandApi.kt`
- `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitCommandService.kt`
- `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitWorktreeService.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModel.kt`
- tests in utilities/ViewModel layers

**Scope:** Detect remote same-name branch and block. Do not silently check out or track the remote branch.

### 13. Reuse an exact existing target worktree

**Acceptance criteria:** Given the exact derived worktree path for `feature/stacked-pr` already exists, when the user creates `feature/stacked-pr` from `feature/base-pr`, then Eng Hub skips ancestry validation, runs setup commands in that existing worktree, and refreshes the repository row.

**Expected edits:**

- `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitWorktreeService.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModel.kt`
- `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModelTest.kt`

**Scope:** Reuse only the exact target worktree path. Do not reuse branches checked out at a different path.

### 14. Expose branch ancestry checks in git utilities

**Acceptance criteria:** Given branches `feature/base-pr` and `feature/stacked-pr`, when ancestry is checked, then utilities report whether the base tip is an ancestor of the child tip.

**Expected edits:**

- `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitCommandApi.kt`
- `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitCommandService.kt`
- `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitWorktreeService.kt`
- `utilities/src/commonTest/kotlin/com/github/karlsabo/git/GitWorktreeServiceTest.kt`

**Scope:** Add `merge-base --is-ancestor`-style behavior only. Do not render hierarchy or add override prompts.

### 15. Infer nearest unambiguous parent among visible worktrees

**Acceptance criteria:** Given visible worktrees `main`, `feature/base-pr`, and `feature/stacked-pr`, when parent candidates are evaluated, then `feature/base-pr` is selected as parent when it is the nearest unambiguous ancestor of `feature/stacked-pr`.

**Expected edits:**

- `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitWorktreeService.kt`
- `utilities/src/commonTest/kotlin/com/github/karlsabo/git/GitWorktreeServiceTest.kt`

**Scope:** Return parent mapping for visible local worktree branches. Git/ancestry failures should produce no parent assignment for the affected worktree rather than failing the whole list. Do not change UI state or rendering.

### 16. Show ambiguous or unparented worktrees flat in inferred hierarchy data

**Acceptance criteria:** Given two equally-near ancestor candidates for `feature/stacked-pr`, when hierarchy is inferred, then `feature/stacked-pr` has no parent assignment.

**Expected edits:**

- `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitWorktreeService.kt`
- `utilities/src/commonTest/kotlin/com/github/karlsabo/git/GitWorktreeServiceTest.kt`

**Scope:** Add the ambiguity/no-parent rule only. Treat missing or failed ancestry data the same as no unambiguous parent so existing flat rendering remains available. Do not render hierarchy.

### 17. Infer the default branch reference for hierarchy roots

**Acceptance criteria:** Given the current branch has upstream remote `upstream`, when Eng Hub needs a default branch reference, then it uses that remote default if available and falls back to `origin/HEAD`.

**Expected edits:**

- `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitCommandApi.kt`
- `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitCommandService.kt`
- `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitWorktreeService.kt`
- `utilities/src/commonTest/kotlin/com/github/karlsabo/git/GitWorktreeServiceTest.kt`

**Scope:** Add default-branch discovery only. If discovery fails, return a controlled absence/fallback result that callers can use to keep worktrees flat instead of throwing during polling/rendering. Do not add repository-row creation or hierarchy rendering.

### 18. Log fetch failures while using local refs

**Acceptance criteria:** Given remote/default branch fetch fails before an ancestry or hierarchy check, when Eng Hub continues with local refs, then it logs a warning and does not block the user flow.

**Expected edits:**

- `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitWorktreeService.kt`
- `utilities/src/commonTest/kotlin/com/github/karlsabo/git/GitWorktreeServiceTest.kt`

**Scope:** Add console/log warning only. Do not add a user-visible notification.

### 19. Add hierarchical worktree UI state without changing rendering

**Acceptance criteria:** Given an inferred parent mapping where `feature/stacked-pr` is a child of `feature/base-pr`, when worktrees are converted to UI state, then the relationship is represented in `LocalRepositoryUiState`.

**Expected edits:**

- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/state/LocalRepositoryUiState.kt`
- `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/state/LocalRepositoryUiStateTest.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModel.kt` if plumbing is needed

**Scope:** Add data shape/state plumbing only. New fields must have defaults/backward-compatible constructors so existing UI rendering and tests continue to compile and behave flat when no hierarchy is supplied. Do not visually nest rows.

### 20. Render one nested child level in the Worktrees pane

**Acceptance criteria:** Given UI state where `feature/stacked-pr` is a child of `feature/base-pr`, when the Worktrees pane renders, then the child row appears indented under the parent row.

**Expected edits:**

- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreePanel.kt`
- `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreePanelTest.kt`

**Scope:** Render one nested child level using already-supplied UI state. If hierarchy data is empty or incomplete, render the current flat list. Do not add rebase indicators/actions or new git calls.

### 21. Add repository-row `Create worktree` menu action

**Acceptance criteria:** Given a configured repository row, when the user opens its action menu, then `Create worktree` is available.

**Expected edits:**

- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreePanel.kt`
- `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreePanelTest.kt`

**Scope:** Add repository-row action only. Until base resolution is implemented, the action must be disabled, no-op safely, or show a controlled unsupported message; it must not throw. Do not resolve base, submit dialog, or create a worktree.

### 22. Resolve repository-row create base to the inferred default branch

**Acceptance criteria:** Given a configured repository row, when create-worktree is requested from that row, then the ViewModel requests a base ref from default-branch inference.

**Expected edits:**

- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModel.kt`
- `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModelTest.kt`

**Scope:** Base selection only. Failure to infer a default branch should be reported as a caught action error and leave the dialog/action flow dismissible. Do not create/setup or change dialog rendering.

### 23. Create and set up from repository-row default branch

**Acceptance criteria:** Given a configured repository row whose inferred default base is `main`, when the user enters `feature/new-dashboard`, then Eng Hub creates `feature/new-dashboard` from `main`, runs setup, and refreshes the repository row.

**Expected edits:**

- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreePanel.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/screen/EngHubScreen.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModel.kt`
- tests in component/ViewModel layers

**Scope:** Reuse the dialog/create path from worktree-row creation. Creation/setup/default-branch failures must be caught and reported without breaking existing Worktrees pane behavior. Do not add custom base selection or custom target path.

### 24. Detect local branch existence without a worktree

**Acceptance criteria:** Given local branch `feature/stacked-pr` exists and no exact target worktree exists, when creation is requested, then Eng Hub detects the existing branch before creating a new branch.

**Expected edits:**

- `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitCommandApi.kt`
- `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitCommandService.kt`
- `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitWorktreeService.kt`
- `utilities/src/commonTest/kotlin/com/github/karlsabo/git/GitWorktreeServiceTest.kt`

**Scope:** Detection only. Expose enough typed utility result data for later stories to choose between `worktree add -b <new-branch> <path> <base>` and `worktree add <path> <existing-branch>`, but do not validate ancestry or add override UI.

### 25. Block existing target branch when ancestry check fails

**Acceptance criteria:** Given existing branch `feature/stacked-pr` is not descended from selected base `feature/base-pr`, when creation is requested, then Eng Hub shows an error and does not create/setup the worktree.

**Expected edits:**

- `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitWorktreeService.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModel.kt`
- tests in utilities/ViewModel layers

**Scope:** Best-effort ancestry check failure blocks. Do not add override confirmation.

### 26. Ask for confirmation to use an unrelated existing branch

**Acceptance criteria:** Given ancestry validation fails for existing branch `feature/stacked-pr`, when the user chooses to continue, then Eng Hub proceeds to create/reuse the worktree for that existing branch and run setup.

**Expected edits:**

- `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitCommandApi.kt`
- `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitCommandService.kt`
- `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitWorktreeService.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreePanel.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/screen/EngHubScreen.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModel.kt`
- tests in utilities/component/ViewModel layers

**Scope:** Add confirmation override flow and the typed utility path for creating/reusing a worktree from an existing local branch without `-b` (`git worktree add <path> <existing-branch>`). Catch/report failures; do not store hierarchy metadata.

### 27. Carry detached commit hash into worktree UI state

**Acceptance criteria:** Given a detached worktree at commit `abc123`, when worktrees are mapped to UI state, then the row still displays `(detached)` and also retains `abc123` as the base commit value.

**Expected edits:**

- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/state/LocalRepositoryUiState.kt`
- `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/state/LocalRepositoryUiStateTest.kt`

**Scope:** State only. Retain backward-compatible display as `(detached)` while carrying the commit separately; do not create from detached base.

### 28. Create a branch worktree from a commit-ish base

**Acceptance criteria:** Given base commit `abc123`, when target `feature/from-detached` is requested, then git runs `worktree add -b feature/from-detached <path> abc123`.

**Expected edits:**

- `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitCommandApi.kt`
- `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitCommandService.kt`
- `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitWorktreeService.kt`
- `utilities/src/commonTest/kotlin/com/github/karlsabo/git/GitWorktreeServiceTest.kt`

**Scope:** Git utility support for commit-ish base. Do not add UI wiring or detached hierarchy display.

### 29. Wire detached-row create action to the commit-ish base

**Acceptance criteria:** Given a visible detached worktree at commit `abc123`, when the user creates branch `feature/from-detached`, then Eng Hub creates from `abc123` and runs setup commands.

**Expected edits:**

- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreePanel.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/screen/EngHubScreen.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModel.kt`
- tests in component/ViewModel layers

**Scope:** Support detached worktree as base commit. Use the retained commit hash as the base; never pass the display label `(detached)` to git. Detached-base children still display flat under default/root.

### 30. Compute parent-has-new-commits in git utilities

**Acceptance criteria:** Given parent `feature/base-pr` has commits not contained in child `feature/stacked-pr`, when checked, then utilities return `needsRebase=true`.

**Expected edits:**

- `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitCommandApi.kt`
- `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitCommandService.kt`
- `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitWorktreeService.kt`
- `utilities/src/commonTest/kotlin/com/github/karlsabo/git/GitWorktreeServiceTest.kt`

**Scope:** Git containment calculation only. Do not pre-detect conflicts or render indicators.

### 31. Add rebase-needed flag to worktree UI state

**Acceptance criteria:** Given git reports `feature/stacked-pr` needs rebase, when worktrees are mapped to UI state, then that child has `needsRebase=true`.

**Expected edits:**

- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/state/LocalRepositoryUiState.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModel.kt`
- tests in state/ViewModel layers

**Scope:** State plumbing only. Default `needsRebase` to `false` when git data is missing or checks fail so existing worktree rendering remains safe. Do not render the indicator or add a rebase action.

### 32. Render rebase-needed indicator

**Acceptance criteria:** Given `feature/stacked-pr` has `needsRebase=true`, when the Worktrees pane renders, then the row shows a rebase-needed indicator.

**Expected edits:**

- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreePanel.kt`
- `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreePanelTest.kt`

**Scope:** Display only. Conflict discovery happens when rebase runs.

### 33. Add git utility to rebase a worktree onto a parent branch

**Acceptance criteria:** Given child worktree path `/repos/dev-lake-utils-feature-stacked-pr` and parent `feature/base-pr`, when rebase is requested, then utilities run `git rebase --autostash feature/base-pr` in the child worktree.

**Expected edits:**

- `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitCommandApi.kt`
- `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitCommandService.kt`
- `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitWorktreeService.kt`
- `utilities/src/commonTest/kotlin/com/github/karlsabo/git/GitWorktreeServiceTest.kt`

**Scope:** Git command only. Do not add UI menu, status refresh, or conflict prompt.

### 34. Expose rebase action for worktrees with an inferred parent

**Acceptance criteria:** Given `feature/stacked-pr` has inferred parent `feature/base-pr`, when the user opens its quick menu, then a `Rebase onto parent` action is available.

**Expected edits:**

- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreePanel.kt`
- `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreePanelTest.kt`

**Scope:** Menu action visibility only. Do not run the command.

### 35. Run rebase action and refresh worktree status

**Acceptance criteria:** Given the user chooses `Rebase onto parent` for `feature/stacked-pr`, when the rebase succeeds, then Eng Hub refreshes the repository worktree status.

**Expected edits:**

- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModel.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/screen/EngHubScreen.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreePanel.kt`
- tests in ViewModel/component layers

**Scope:** Successful rebase flow plus safe failure handling only. Rebase failures must be caught, reported as an action error, and followed by a best-effort refresh; until conflict-specific stories land, users may resolve/abort manually. Do not add conflict prompt or automated conflict resolution.

### 36. Classify rebase conflict failure

**Acceptance criteria:** Given `git rebase --autostash feature/base-pr` fails and leaves a rebase in progress, when Eng Hub handles the error, then it identifies the failure as a rebase conflict.

**Expected edits:**

- `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitWorktreeService.kt`
- `utilities/src/commonTest/kotlin/com/github/karlsabo/git/GitWorktreeServiceTest.kt`

**Scope:** Classification only. Do not add abort command or prompt.

### 37. Add abort-rebase utility

**Acceptance criteria:** Given a child worktree in a conflicted rebase, when abort is requested, then utilities run `git rebase --abort` in the child worktree.

**Expected edits:**

- `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitCommandApi.kt`
- `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitCommandService.kt`
- `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitWorktreeService.kt`
- `utilities/src/commonTest/kotlin/com/github/karlsabo/git/GitWorktreeServiceTest.kt`

**Scope:** Git abort command only. Do not add user prompt.

### 38. Prompt after rebase conflict

**Acceptance criteria:** Given a rebase conflict is detected, when the prompt appears, then choosing `Abort` aborts the rebase and choosing `Leave as-is` leaves the worktree in the conflicted rebase state.

**Expected edits:**

- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreePanel.kt` or a shared dialog component
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/screen/EngHubScreen.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModel.kt`
- tests in component/ViewModel layers

**Scope:** Abort-or-leave prompt outcome only. Do not attempt automated conflict resolution.
