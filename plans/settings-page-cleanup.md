# Settings page cleanup

## Goal

Make Eng Hub settings easy to find and edit without scrolling through every repository, with consistently aligned form controls.

## Context

- Repository: `/Users/karl.sabo/git/dev-lake-utils`.
- Status: proposed plan, not implementation. Story boundaries and UX defaults below are recommendations awaiting review; each numbered story is intended as one ticket and one PR with one primary acceptance scenario.
- User-reported problems: confusing organization, excessive scrolling, buttons misaligned with textboxes, difficulty finding repository settings. Requested improvement: choose a repository before configuring it, plus settings search.
- No configuration-file migration, backend changes, new settings, or persistence redesign is needed.

### Repository evidence

All paths below are relative to the repository root.

- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/screen/EngHubSettingsScreen.kt`: one vertically scrolling `Column` contains GitHub access, GitHub activity, repositories, LLM templates, and planning/setup. `RepositoriesSettings` renders every repository editor with `forEachIndexed`. Repository headings are positional labels such as “Local repository 1.”
- The same file's `PickerSettingsField`, `LocalRepositorySettings`, and `OrganizationIdRow` center action buttons alongside `SettingsField`. That field wraps the textbox and optional error text in a column. This structure can shift button alignment when validation text appears; the reported visual issue has not been reproduced in the running app.
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/SetupCommandEditor.kt`: command editing has its own field/action rows, insertion controls, draft field, and error rendering. Preserve command ordering and insertion behavior.
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/screen/EngHubLlmSkillTemplateSettings.kt`: the alert-triage Markdown editor is multiline, contributing to the long page.
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/state/EngHubSettingsUiState.kt`: repository drafts and validation already live outside composables; repository UI state does not currently expose a stable identity.
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubLocalRepositoryPathEditor.kt`: repository operations already track session-local identities across edits. Reuse that identity ownership rather than adding a competing path/index-based identity system for selection.
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubLocalRepositorySettingsController.kt`: owns repository add/remove/undo and reconciliation with committed configuration updates.
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubSettingsViewModel.kt`: text edits generally commit after 750 ms; `flushPendingEdits` coordinates pending work.
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/screen/EngHubScreen.kt`: app-pane navigation flushes settings before switching panes. Internal settings navigation must not discard drafts or interfere with this behavior.
- `eng-hub/eng-hub.gradle.kts`: existing common tests use Kotlin test and coroutine test; no Compose UI-test dependency is currently declared here. Existing `screen/EngHubScreenTest.kt` tests state/navigation functions, not rendered layout.
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHub.kt`: default window is 1400 × 900 dp.

## Open Questions

1. **Repository chooser:** Is a searchable list beside one repository editor the right interaction?
   - Recommendation: yes. Show repository basename plus full path for disambiguation, rather than another long stack of expanded forms. Use a compact chooser above the editor when space is constrained.
   - Answer: awaiting confirmation; use as the proposed default.
2. **Settings search scope:** Should search inspect setting values and setup-command contents?
   - Recommendation: search setting labels, descriptions, a small explicit alias list, and configured repository names/paths. Do not search arbitrary field values, tokens, token-file contents, command bodies, or template Markdown. This keeps results predictable and avoids indexing secrets.
   - Answer: awaiting confirmation; use as the proposed default.
3. **Selection retention:** Should the last section/repository survive an application restart?
   - Recommendation: retain it while the app is running, but do not persist it to configuration. First-run/missing-config Settings should open GitHub access; other visits restore the current session's selection.
   - Answer: awaiting confirmation; use as the proposed default.

## Recommended experience

```text
Settings                         [ Search settings…                 ]

GitHub access       Repositories
GitHub activity     Base directory       [ /workspace          ] [Browse]
Repositories        Worktree polling     [ 120                 ]
LLM skill templates
Planning and setup  [ Find repository… ]   api
                    api                   /workspace/api
                    /workspace/api        [ repository path    ] [Browse]
                    web                   [Remove repository]
                    /workspace/web        Setup commands
                    …                     [ command            ] [actions]
                    [Add repository]
```

- Render only the selected section. Keep the search control and section navigation outside the scrolling form body.
- In Repositories, show shared repository settings once, a bounded/lazy repository list, and only the selected repository's editor. The form body and repository list may scroll independently; never nest unbounded same-axis scroll containers.
- Keep shared directory/polling settings distinct from the selected repository's path/setup commands. An unselected repository has no editable controls mounted.
- Select the first configured repository initially. If none exist, show a clear empty state and Add repository action.
- Keep Add repository available without scrolling to the end of the list. Successful addition selects the new repository. Remove is explicitly destructive and separate from Browse. Preserve Undo.
- At constrained widths, use a compact section selector and repository chooser above the editor, rather than squeezing multiple sidebars into the existing app navigation.
- Settings search temporarily shows navigable results with context, e.g. `Repositories › Worktree polling interval` or `Repositories › api (/workspace/api)`. Selecting a result clears the query, opens its section/repository, scrolls to the target, and focuses it. Clearing search without selecting a result returns to the previous section/repository.
- Case-insensitive substring matching is sufficient initially. Support a few explicit aliases such as “refresh” for polling and “repo” for repositories. No fuzzy-search dependency or remote search.
- Use consistent field/action spacing and action sizing. Align actions to the outlined textbox's actual bounds, not the combined textbox/helper/error column. Put helper/error content below the control row. On narrow layouts, stack actions intentionally rather than clipping controls.
- Keep persistence failures visible outside the selected section/search results. Surface badges on sections/repositories with validation errors so navigation cannot conceal invalid drafts.

## Stories and proposed PRs

For exact expected paths, use these roots:

- `MAIN` = `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub`
- `TEST` = `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub`
- Proposed new files are explicitly marked **new**. Names may follow nearby conventions at implementation time.

### 1. Navigate settings by section

**Acceptance:** Given Settings displays GitHub access, when I choose “Planning and setup,” then only the planning/setup section is rendered in the form body.

**Expected edits:** `MAIN/screen/EngHubSettingsScreen.kt`; `MAIN/screen/EngHubSettingsNavigation.kt` (**new**); `TEST/screen/EngHubSettingsNavigationTest.kt` (**new**).

**Scope/notes:** Introduce the five existing section destinations, persistent header/navigation, bounded content scrolling, and session selection. Reuse current editors/callbacks; do not change autosave or configuration shape. Keep persistence errors globally visible. Repository forms remain unchanged until story 2.

### 2. Configure one selected repository

**Acceptance:** Given `/workspace/api` and `/workspace/web` are configured, when I select `web`, then the repository detail area contains only `/workspace/web`'s editor.

**Expected edits:** `MAIN/screen/EngHubSettingsScreen.kt`; `MAIN/screen/EngHubRepositorySettings.kt` (**new**); `MAIN/state/EngHubSettingsUiState.kt`; `MAIN/viewmodel/EngHubLocalRepositoryPathEditor.kt`; `MAIN/viewmodel/EngHubLocalRepositorySettingsController.kt`; `TEST/state/EngHubSettingsUiStateTest.kt`; `TEST/screen/EngHubRepositorySelectionTest.kt` (**new**).

**Scope/notes:** Replace positional headings with name/path labels and a bounded selector. Keep global repository settings outside the detail editor. Expose/reuse the existing session identity for selection; resolve current indexes only at callback boundaries. Preserve drafts in existing state when editors unmount. Include empty-state/add/remove/undo controls with deterministic selection: select newly added/restored entries; on selected-entry removal choose the next remaining entry, otherwise the previous one, otherwise the empty state. Do not persist new IDs.

### 3. Find a repository in the chooser

**Acceptance:** Given 100 configured repositories including `/workspace/payments-api`, when I enter `payments` in “Find repository,” then the chooser shows only matching repository names/paths.

**Expected edits:** `MAIN/screen/EngHubRepositorySettings.kt`; `TEST/screen/EngHubRepositorySelectionTest.kt`.

**Scope/notes:** Local case-insensitive filtering, full-path disambiguation, and clearable query. Filter only chooser entries; do not modify configuration, lose selection, or hide the selected detail merely because its list entry stops matching.

### 4. Preserve the selected repository through a path edit

**Acceptance:** Given `api` is selected, when its path changes from `/workspace/api` to `/workspace/api-renamed`, then the same repository remains selected.

**Expected edits:** `MAIN/screen/EngHubRepositorySettings.kt`; selection state introduced in story 2; `TEST/screen/EngHubRepositorySelectionTest.kt`; `TEST/viewmodel/EngHubSettingsSynchronizationRegressionTest.kt`.

**Scope/notes:** Validate the identity bridge from story 2 across debounced commits/reconciliation. Do not key selection by editable path or visible/filtered index. Preserve existing duplicate-path validation and command targeting.

### 5. Keep pending edits when switching repository editors

**Acceptance:** Given I type `bootstrap --fast` into api's setup-command draft, when I select web and return to api, then api's draft still reads `bootstrap --fast`.

**Expected edits:** `MAIN/screen/EngHubRepositorySettings.kt`; `MAIN/component/SetupCommandEditor.kt` only if needed; `TEST/screen/EngHubRepositorySelectionTest.kt`.

**Scope/notes:** Use the existing repository draft state, not composable-local copies. Merely changing selection must not add commands, force invalid drafts into persisted configuration, or cancel scheduled valid edits.

### 6. Align buttons with textboxes

**Acceptance:** Given a repository-path row shows a validation message, when the row is laid out, then Browse's vertical center aligns with the outlined textbox's vertical center within 1 dp.

**Expected edits:** `MAIN/screen/EngHubSettingsScreen.kt`; `MAIN/screen/EngHubRepositorySettings.kt`; `MAIN/component/SetupCommandEditor.kt`; `MAIN/screen/EngHubSettingsField.kt` (**new**, if extraction is warranted).

**Scope/notes:** Apply the same row/error structure to picker, organization, repository, and command controls. Keep action spacing consistent; do not impose textbox height on buttons merely to mask misalignment. Add visual/bounds validation as described below. Preserve secure token semantics if extracting fields.

### 7. Search for a setting

**Acceptance:** Given Settings is on GitHub access, when I search `polling`, then the results list contains both activity polling and worktree polling with their section names.

**Expected edits:** `MAIN/screen/EngHubSettingsScreen.kt`; `MAIN/screen/EngHubSettingsSearch.kt` (**new**); `TEST/screen/EngHubSettingsSearchTest.kt` (**new**).

**Scope/notes:** Build a small explicit descriptor list with stable field IDs, section IDs, labels, descriptions, and aliases. Use shared label definitions where practical so search labels cannot silently diverge from forms. No generic settings-schema framework. Search uses no arbitrary configured values or filesystem reads. Keep navigation/search state separate from committed configuration.

### 8. Open a setting from search results

**Acceptance:** Given search results include “Worktree polling interval,” when I activate that result, then the worktree polling field in Repositories receives focus.

**Expected edits:** `MAIN/screen/EngHubSettingsSearch.kt`; `MAIN/screen/EngHubSettingsNavigation.kt`; `MAIN/screen/EngHubRepositorySettings.kt`; `TEST/screen/EngHubSettingsSearchTest.kt`.

**Scope/notes:** Clear the query after activation, select the destination, bring the target into view after composition, then focus it. Use stable IDs/focus targets, not translated display text. Keyboard activation must work without a mouse.

### 9. Open a repository from settings search

**Acceptance:** Given `/workspace/payments-api` is configured, when I select its result from a `payments` settings search, then its repository editor opens.

**Expected edits:** `MAIN/screen/EngHubSettingsSearch.kt`; `MAIN/screen/EngHubRepositorySettings.kt`; `TEST/screen/EngHubSettingsSearchTest.kt`.

**Scope/notes:** Generate repository results from current names/paths, keyed by the session identity from story 2. Include full paths to distinguish equal basenames. Gracefully ignore/reconcile a stale result if its repository disappears before activation.

### 10. Recover from an unsuccessful settings search

**Acceptance:** Given I was editing `/workspace/api`, when I search `zz-no-such-setting` and clear the query, then I return to api's editor.

**Expected edits:** `MAIN/screen/EngHubSettingsSearch.kt`; `MAIN/screen/EngHubSettingsNavigation.kt`; `TEST/screen/EngHubSettingsSearchTest.kt`.

**Scope/notes:** Show an explicit “No settings found” state with a Clear search action. Searching does not mutate the underlying section/repository selection or any form drafts.

### 11. Find validation errors outside the current editor

**Acceptance:** Given api has a duplicate-path validation error while web is selected, when I inspect the repository chooser, then api has an accessible error indicator.

**Expected edits:** `MAIN/screen/EngHubSettingsNavigation.kt`; `MAIN/screen/EngHubRepositorySettings.kt`; `TEST/screen/EngHubRepositorySelectionTest.kt`.

**Scope/notes:** Derive section/repository badges from existing validation state; selecting the affected item uses normal navigation. Keep persistence failures visible in the shell even during search. No new validation rules or save model.

### 12. Use settings in a smaller window

**Acceptance:** Given an application window of 1000 × 700 dp, when I edit a repository path, then its full field and actions remain reachable without horizontal page scrolling.

**Expected edits:** `MAIN/screen/EngHubSettingsScreen.kt`; `MAIN/screen/EngHubSettingsNavigation.kt`; `MAIN/screen/EngHubRepositorySettings.kt`; shared form-row layout from story 6.

**Scope/notes:** Account for the existing app navigation consuming width. Switch to compact section/repository selection based on available settings-pane width. Stack field actions when necessary; retain bounded vertical scrolling and keyboard access. Do not redesign other app panes.

## Implementation boundaries and trade-offs

- Section navigation adds a click but removes unrelated content from the reading/editing path. Prefer it to collapsible cards that can recreate the original infinite page.
- A repository list/detail editor uses horizontal space but scales better than rendering every form. The compact chooser is the fallback, not a second configuration workflow.
- Existing controllers own editable values, validation, autosave, and repository identity. The settings navigation state owns only section, selected identity, query, and pending focus target. Unmounting content must not dispose the controllers.
- Extend the existing identity mechanism narrowly if necessary; do not introduce persisted UUIDs or infer identity from path equality during an in-progress rename.
- Search descriptors own discoverability metadata, not persistence or validation. Never include the raw GitHub token in descriptors, result snippets, logs, or assertions that print state.
- Extract settings-specific composables as the screen is split, rather than enlarging the already substantial screen file. Avoid broad design-system work or unrelated refactors.
- Keep all current controls discoverable: token path/token, organizations, author, both polling intervals, repository base directory, repository CRUD/undo, command insertion/edit/removal, planning directory, shell, and LLM template Markdown.

## Sequence

1. Stories 1–2 establish section navigation and the single-repository editor.
2. Stories 3–5 make repository selection usable and safe; story 6 fixes the reported alignment problem independently of search.
3. Stories 7–10 add settings search end to end.
4. Stories 11–12 complete error discoverability and constrained-width behavior before considering the cleanup done.

Do not ship the new repository editor without verifying the draft/identity behaviors in stories 4–5, even if their follow-through PRs are reviewed separately.

## Validation

- Add focused common tests for navigation transitions, matching, repository result targeting, selection reconciliation, and draft retention. Reuse existing view-model fixtures for debounce/persistence cases.
- Retain and run `EngHubSettingsViewModelTest`, `EngHubSettingsPersistenceRegressionTest`, `EngHubSettingsSynchronizationRegressionTest`, `EngHubSettingsUiStateTest`, and `EngHubRepositoryPathDuplicateTest` under their existing `TEST` subdirectories.
- Layout/focus are not proven by pure state tests. Prefer a small JVM Compose UI-test setup for rendered control bounds, focus, and accessibility; this may require updating `eng-hub/eng-hub.gradle.kts`. If unavailable in CI, explicitly record that gap and perform the manual checks below rather than claiming automated visual coverage.
- Run the desktop app with `./gradlew :eng-hub:run`; inspect 0, 1, and 100 synthetic repositories, duplicate basenames, long paths, many commands, multiline templates, and visible validation errors. Verify alignment at 1400 × 900 and 1000 × 700 dp. Use synthetic credentials only.
- Keyboard-check section/repository selection, search activation/clearing, tab order, focus after result selection, and accessible names on icon/error controls.
- Regression-check add/remove/undo, selected-item removal, earlier-item removal, path rename during debounce, external repository updates, edits followed immediately by app-pane navigation, restart persistence, and persistence failure visibility during search. Verify no edit is applied to a different repository after list/filter changes.
- Confirm metadata/name/path search never matches a synthetic token or arbitrary command/template contents; token masking and sensitive semantics must survive field extraction.
- After code changes, run `./gradlew clean build` from the repository root. Do not suppress analysis warnings or weaken rules to pass the build.

## Non-goals

No bulk repository editing, command search, fuzzy/remote search, config-format changes, cross-restart navigation preferences, new save/cancel workflow, or redesign of Pull Requests/Notifications/Worktrees.
