# IntelliJ Worktree Setup Performance Fixes

**Goal:** Make Eng Hub's IntelliJ worktree setup avoid cloning stale IntelliJ project-instance state into every worktree so IntelliJ starts from clean per-worktree metadata.

## Context

Repo evidence:

- `scripts/idea-tool.kts` currently walks the entire source `.idea` directory, copies every file to the target with `StandardCopyOption.REPLACE_EXISTING`, then rewrites absolute root-checkout paths in UTF-8 files. This includes volatile IntelliJ state such as `workspace.xml`, `shelf/`, and `dataSources*` when present.
- `eng-hub/README.md` currently documents the helper as copying the full `.idea` tree and shows it in `setupCommands`.
- `utilities/src/commonMain/kotlin/com/github/karlsabo/git/WorktreeSetupCoordinator.kt` runs configured setup commands from the worktree path and expands `$root-repo-dir` / `$worktree-dir` placeholders.
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/ExistingWorktreeController.kt` uses the configured setup commands when opening an existing local worktree, so a command that refreshes `.idea` can run immediately before `idea ./`.
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/LocalWorktreeFromBaseCreator.kt` also uses the same configured setup commands after creating a worktree.
- `utilities/src/commonMain/kotlin/com/github/karlsabo/intellij/ImlExcludeFolderManager.kt` shows IntelliJ-specific utility code already belongs under `com.github.karlsabo.intellij` for reusable project-file manipulation.

Local evidence from the reported setup:

- `/Users/karl.sabo/Library/Application Support/DevLakeUtils/eng-hub-config.json` includes `kotlin /Users/karl.sabo/git/dev-lake-utils/scripts/idea-tool.kts "$root-repo-dir/.idea" "$worktree-dir/.idea"` before `idea ./` for several repositories.
- Local source `.idea` directories contain per-project state: `/Users/karl.sabo/Klaviyo/Repos/app/.idea/workspace.xml` and `/Users/karl.sabo/Klaviyo/Repos/k-repo/.idea/workspace.xml` include `ProjectId` and other window/session state.
- Some existing local worktrees already share the same copied `ProjectId` as their root checkout, which is consistent with copying `workspace.xml` into worktrees.

Problem framing:

- `idea ./` already gives IntelliJ the worktree directory.
- Copying the full `.idea` directory gives IntelliJ a stale but believable project model from another checkout. IntelliJ then has to reconcile branch/path/session state during project startup.
- Re-copying `.idea` before every open can also invalidate IntelliJ's project model by touching project files that did not semantically change.

## Decisions

- Fix the helper and docs first. Do not add a larger Eng Hub-specific IntelliJ workflow until the small utility fix has been tried.
- Treat IntelliJ local state as unsafe to copy by default.
- Skip `dataSources.xml`, `dataSources.local.xml`, and `dataSources/**` by default. Database connection state is personal/local and can trigger plugin work.
- Continue copying shared run configurations under `.idea/runConfigurations/**`, with absolute root-checkout paths rewritten to the target worktree path.
- Make the existing helper safe-by-default with no legacy full-copy mode. The current full-copy behavior is the bug, not a supported compatibility contract.
- Keep this as filesystem-observable behavior; do not depend on measuring IntelliJ startup time in automated tests.

## Resolved questions

1. The helper should only stop copying volatile files going forward. It should not automatically delete previously-copied volatile files from existing worktrees because that can destroy local IntelliJ state the developer may still want.
2. The helper should only create or update stable files that are present in the source template. It should not remove stable target files that no longer exist in the source unless a future explicit prune mode is added.
3. The script can remain a `.kts` for now. The focused JVM script tests are good enough while the behavior is small; move copy/filter logic into `utilities` only if the script grows enough that normal unit-level tests become materially easier to maintain.
4. Docs should recommend an optional one-time cleanup for already-polluted worktrees. The cleanup must be manual, clearly scoped to volatile IntelliJ files, and should tell developers to close IntelliJ first.

## Acceptance tests

1. **Volatile IntelliJ state is not copied into a new worktree**
   - Given a source `.idea` contains `workspace.xml` with a `ProjectId`, `shelf/`, `dataSources/`, `dataSources.xml`, `dataSources.local.xml`, `codeStyles/Project.xml`, and `runConfigurations/App.xml`, when `scripts/idea-tool.kts <source .idea> <target .idea>` runs for a target that has no `.idea`, then the target contains the stable code style and run configuration files, and does not contain the volatile workspace/shelf/data-source files.

2. **Existing worktree-local IntelliJ state is preserved during refresh**
   - Given a target worktree already has `.idea/workspace.xml` with target-specific state, and the source root checkout has a different `.idea/workspace.xml`, when `scripts/idea-tool.kts <source .idea> <target .idea>` runs, then the target `workspace.xml` is unchanged.

3. **Repeated safe refreshes do not touch unchanged project files**
   - Given a target `.idea/misc.xml` already contains the same content the helper would write after source-root path rewriting, when `scripts/idea-tool.kts <source .idea> <target .idea>` runs, then the target `.idea/misc.xml` modification time and contents are unchanged.

4. **Documentation no longer recommends full `.idea` cloning before IntelliJ launch**
   - Given a developer reads the Eng Hub setup-command documentation, when they follow the IntelliJ worktree example, then they are shown a safe template-copy command and an explanation that `workspace.xml`, shelves, and data sources are intentionally not copied.

## Stories / PRs

### 1. [Done] Skip volatile IntelliJ files when initializing a worktree `.idea`

**Acceptance criteria:** Given a source `.idea` contains `workspace.xml` with a `ProjectId`, `shelf/`, `dataSources/`, `dataSources.xml`, `dataSources.local.xml`, `codeStyles/Project.xml`, and `runConfigurations/App.xml`, when `scripts/idea-tool.kts <source .idea> <target .idea>` runs for a target that has no `.idea`, then the target contains the stable code style and run configuration files, and does not contain the volatile workspace/shelf/data-source files.

**Expected edits:**

- `scripts/idea-tool.kts`
- Add tests for the script if there is an existing script-test pattern; otherwise add a focused Kotlin/JVM test around extracted copy/filter logic in a reusable source file.

**Scope:**

- In: exclude volatile paths by default, at least:
  - `workspace.xml`
  - `shelf/**`
  - `dataSources/**`
  - `dataSources.xml`
  - `dataSources.local.xml`
  - `tasks.xml`
  - `usage.statistics.xml`
- In: continue copying stable shared files such as `codeStyles/**`, `inspectionProfiles/**`, `runConfigurations/**`, `modules.xml`, `.iml`, `misc.xml`, `vcs.xml`, and language/tool config files.
- In: do not add a legacy full-copy flag or mode.
- In: continue rewriting exact absolute source repository root strings in copied UTF-8 files.
- Out: measuring IntelliJ startup time.
- Out: changing Eng Hub setup command execution semantics.

**Notes:**

The current helper copies every path from source to target in `scripts/idea-tool.kts`. This story changes the helper's default contract from “copy full `.idea` tree” to “copy safe reusable project template files.” That is the behavior users need when the command runs before `idea ./` from Eng Hub setup commands.

### 2. [Done] Preserve existing worktree-local IntelliJ state during refresh

**Acceptance criteria:** Given a target worktree already has `.idea/workspace.xml` with target-specific state, and the source root checkout has a different `.idea/workspace.xml`, when `scripts/idea-tool.kts <source .idea> <target .idea>` runs, then the target `workspace.xml` is unchanged.

**Expected edits:**

- `scripts/idea-tool.kts`
- The same script/helper tests introduced in Story 1, extended for an existing target `.idea`.

**Scope:**

- In: skipped volatile files must be skipped both when creating a new target `.idea` and when refreshing an existing target `.idea`.
- In: existing target-only volatile files remain in place.
- Out: deleting/cleaning already-bad copied `workspace.xml` files from existing developer machines unless that is explicitly pulled into this story after answering the cleanup question.
- Out: automatic IntelliJ cache cleanup.

**Notes:**

`ExistingWorktreeController` runs configured setup commands when opening an existing worktree. If a user keeps the helper in `setupCommands`, refreshing an existing worktree must not replace local IntelliJ session/identity files immediately before launch.

### 3. [Done] Avoid rewriting unchanged IntelliJ project files

**Acceptance criteria:** Given a target `.idea/misc.xml` already contains the same content the helper would write after source-root path rewriting, when `scripts/idea-tool.kts <source .idea> <target .idea>` runs, then the target `.idea/misc.xml` modification time and contents are unchanged.

**Expected edits:**

- `scripts/idea-tool.kts`
- Script/helper tests that compare target file content and modification time before and after a no-op refresh.

**Scope:**

- In: compute the target bytes/text before writing when practical.
- In: only write a target file when copied/re-written content differs from the current target file.
- In: preserve existing behavior for changed files.
- Out: content hashing framework or broad file-sync abstraction unless the simple implementation becomes messy.

**Notes:**

The current script uses `Files.copy(..., REPLACE_EXISTING, COPY_ATTRIBUTES, ...)` before text rewriting, so even identical stable project files can be touched during every Eng Hub open. Avoiding no-op writes reduces the chance that IntelliJ treats project configuration as externally changed during startup.

### 4. [Done] Update Eng Hub IntelliJ setup documentation

**Acceptance criteria:** Given a developer reads the Eng Hub setup-command documentation, when they follow the IntelliJ worktree example, then they are shown a safe template-copy command and an explanation that `workspace.xml`, shelves, and data sources are intentionally not copied.

**Expected edits:**

- `eng-hub/README.md`
- Any usage text embedded in `scripts/idea-tool.kts`

**Scope:**

- In: replace “copies the full source `.idea` tree” wording.
- In: document the volatile-file exclusions and why they exist.
- In: show the command before `idea ./` only if it is safe after Stories 1-3.
- Out: adding new Eng Hub config fields.

**Notes:**

The current README example encourages exactly the reported setup. The docs should make it clear that `idea ./` is what opens the project directory; the helper only seeds reusable project settings.

### 5. [Done] Document one-time cleanup for existing polluted IntelliJ worktrees

**Acceptance criteria:** Given an existing worktree has stale copied IntelliJ local state from an earlier full `.idea` copy, when a developer reads the Eng Hub IntelliJ helper docs, then they see a safe manual cleanup procedure that tells them to close IntelliJ first and remove only volatile IntelliJ files such as `workspace.xml`, `shelf/`, `dataSources/`, `dataSources.xml`, and `dataSources.local.xml`.

**Expected edits:**

- `eng-hub/README.md`

**Scope:**

- In: document optional one-time cleanup for already-polluted worktrees.
- In: warn developers to close IntelliJ before deleting local project state.
- In: keep the cleanup scoped to volatile IntelliJ files already excluded by the helper.
- Out: automatic cleanup in `scripts/idea-tool.kts`.
- Out: deleting IntelliJ caches or changing Eng Hub setup command semantics.

**Notes:**

The safe helper prevents future pollution, but it does not repair worktrees that already received copied root-checkout local state. Manual cleanup is safer than script-driven deletion because `workspace.xml`, shelves, and data sources may contain developer-local state.

## Suggested sequencing

1. Story 1 first: it removes the dangerous files from new worktrees.
2. Story 2 second: it protects existing worktrees when setup commands run on open.
3. Story 3 third: it reduces unnecessary IntelliJ model invalidation on repeated opens.
4. Story 4 next: update the normal helper/setup documentation.
5. Story 5 last: document optional cleanup for existing worktrees polluted by the old helper behavior.

## Validation

Performed:

- `./gradlew :utilities:jvmTest --tests com.github.karlsabo.intellij.IdeaToolScriptTest` — passed.
- Manual `/tmp/idea-source` to `/tmp/idea-target` script run — passed; target contained only stable code style and run configuration files.

Manual validation after implementation:

```bash
rm -rf /tmp/idea-source /tmp/idea-target
mkdir -p /tmp/idea-source/.idea/{codeStyles,runConfigurations,shelf,dataSources}
printf '<project><component name="ProjectId" id="source" /></project>' > /tmp/idea-source/.idea/workspace.xml
printf '<component name="Project" />' > /tmp/idea-source/.idea/codeStyles/Project.xml
printf '<configuration name="App" />' > /tmp/idea-source/.idea/runConfigurations/App.xml
printf '<data />' > /tmp/idea-source/.idea/dataSources.xml
kotlin scripts/idea-tool.kts /tmp/idea-source/.idea /tmp/idea-target/.idea
find /tmp/idea-target/.idea -maxdepth 3 -type f | sort
```

Expected: stable files exist; `workspace.xml`, `shelf/**`, and `dataSources*` do not appear.

Also validate against a real worktree by closing IntelliJ, running the helper twice, and confirming the second run does not modify unchanged `.idea` files.
