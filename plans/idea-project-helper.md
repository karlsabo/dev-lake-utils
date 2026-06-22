# IntelliJ IDEA project helper for worktrees

**Goal**: Provide a KTS `idea-tool source/.idea target/.idea` command that copies the base checkout's IDEA project template into a worktree, and let Eng Hub setup commands reference the root repo and worktree paths when invoking it.

**Context**:

- Source request: `/Users/karl.sabo/git/dev-lake-utils/plans/idea-project-helper.md` is the repo-local plan stub for this feature.
- A plain `cp`/`rsync` command can copy `.idea`, but path rewriting inside copied IntelliJ files is awkward without `perl`/`sed`. Keeping a small KTS script avoids fragile shell quoting and external text-rewrite commands.
- Eng Hub config is loaded from `/Users/karl.sabo/git/dev-lake-utils/eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHubConfig.kt`; local setup commands live under `LocalRepositoryConfig.setupCommands` and the local config file is `/Users/karl.sabo/Library/Application Support/DevLakeUtils/eng-hub-config.json`.
- Configured setup commands are selected in `/Users/karl.sabo/git/dev-lake-utils/eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/WorktreeSetupCommands.kt` by matching the configured local repository path.
- Setup commands are executed by `/Users/karl.sabo/git/dev-lake-utils/utilities/src/commonMain/kotlin/com/github/karlsabo/git/WorktreeSetupCoordinator.kt` via `ShellWorktreeSetupCommandRunner`, with `workingDirectory = request.worktreePath.value`.
- `WorktreeSetupRequest` in `/Users/karl.sabo/git/dev-lake-utils/utilities/src/commonMain/kotlin/com/github/karlsabo/git/WorktreeSetupCoordinator.kt` already carries both `repoPath` and `worktreePath`, so it has the data needed to expand setup command placeholders.
- Existing setup-command behavior is tested in `/Users/karl.sabo/git/dev-lake-utils/utilities/src/commonTest/kotlin/com/github/karlsabo/git/WorktreeSetupCoordinatorTest.kt`.
- Reviewed example IDEA folders:
  - `/Users/karl.sabo/Klaviyo/Repos/app/.idea` contains project files such as `app.iml`, `modules.xml`, `misc.xml`, shared run configs under `runConfigurations/`, plus local/template files like `workspace.xml`, `dataSources.local.xml`, and `dataSources/`.
  - `/Users/karl.sabo/Klaviyo/Repos/k-repo/.idea` contains `k-repo.iml`, `modules.xml`, project dictionaries/inspection profiles, `workspace.xml`, local data source files, and a `shelf/` directory.
  - `/Users/karl.sabo/Klaviyo/Repos/fender/.idea` contains `fender.iml`, JS/TS config XML, `modules.xml`, and `workspace.xml`.
  - `/Users/karl.sabo/Klaviyo/Repos/infrastructure-deployment/.idea` contains `infrastructure-deployment.iml`, generated `libraries/`, `modules.xml`, and `workspace.xml`.
- `.idea/.gitignore` in `/Users/karl.sabo/Klaviyo/Repos/app/.idea/.gitignore` ignores `/workspace.xml`, `/shelf/`, `/queries/`, `/dataSources/`, `/dataSources.local.xml`, `/httpRequests/`, and `/libraries/`. For this workflow, those are still part of the base template and should be copied.
- Project-level run configuration files in `/Users/karl.sabo/Klaviyo/Repos/app/.idea/runConfigurations/*.xml` use `$PROJECT_DIR$`, so they are safe to copy between worktrees.
- `.iml` files in the reviewed projects mostly use `$MODULE_DIR$` or `$PROJECT_DIR$` macros for content roots, source roots, template folders, and exclude folders; examples include `/Users/karl.sabo/Klaviyo/Repos/app/.idea/app.iml`, `/Users/karl.sabo/Klaviyo/Repos/k-repo/.idea/k-repo.iml`, `/Users/karl.sabo/Klaviyo/Repos/fender/.idea/fender.iml`, and `/Users/karl.sabo/Klaviyo/Repos/infrastructure-deployment/.idea/infrastructure-deployment.iml`.
- `workspace.xml` in the reviewed projects contains absolute checkout paths such as `last_opened_file_path` under the source repo. Since `workspace.xml` should be copied, source-root path rewriting belongs in the KTS script.

## Decisions

- Keep a small Kotlin script: `scripts/idea-tool.kts`.
- Copy the whole source `.idea` tree by default, including `workspace.xml`, `dataSources.local.xml`, `dataSources/`, `libraries/`, and `shelf/`.
- Overwrite copied target files by default.
- Rewrite exact absolute source repo root strings to the target repo root in copied text files.
- Leave `.iml` filenames alone. IntelliJ reads module file paths from `modules.xml`; renaming `.iml` files is unnecessary for same-repo worktrees.
- Add setup-command placeholder replacement before commands are passed to the shell.
- Use the requested placeholder tokens:
  - `$root-repo-dir` → `WorktreeSetupRequest.repoPath`
  - `$worktree-dir` → `WorktreeSetupRequest.worktreePath.value`
- These are setup-command placeholders, not real shell environment variables. Hyphenated names are not valid shell variable names; replacing the literal tokens before shell execution gives the desired config syntax without shell-variable issues.
- Keep command execution working directory as the new worktree path.
- Do not add optional excludes, dry-run, Gradle packaging, native binaries, or Eng Hub automatic IntelliJ integration unless the simple script/config path proves insufficient.

## Acceptance tests

1. **Setup commands can reference root repo and worktree paths**
   - Given `/Users/karl.sabo/Library/Application Support/DevLakeUtils/eng-hub-config.json` contains a local repository entry for `/tmp/base` with setup command `printf '%s\n' '$root-repo-dir|$worktree-dir' > setup-vars.txt`, when Eng Hub creates a new worktree at `/tmp/worktree`, then `/tmp/worktree/setup-vars.txt` contains `/tmp/base|/tmp/worktree`.

2. **KTS script copies a base `.idea` template into a worktree**
   - Given `/tmp/base/.idea` contains `modules.xml`, `app.iml`, `misc.xml`, `runConfigurations/App_HTTP.xml`, `workspace.xml`, `dataSources.local.xml`, `dataSources/cache.xml`, `libraries/sdk.xml`, and `shelf/shelved.patch`, and `/tmp/base/.idea/workspace.xml` contains `/tmp/base`, and `/tmp/worktree/.idea/workspace.xml` already exists with old content, when I run `kotlin scripts/idea-tool.kts /tmp/base/.idea /tmp/worktree/.idea`, then `/tmp/worktree/.idea` contains all source files, existing copied files are overwritten, and copied text files contain `/tmp/worktree` instead of `/tmp/base`.

## Stories

### 1. Expand root and worktree path placeholders in setup commands - Done

**Status:** Done.

**Acceptance criteria:** Given a configured setup command contains `$root-repo-dir` and `$worktree-dir`, when Eng Hub runs setup for a created worktree, then the command executed by the setup shell contains the repository root path and created worktree path in place of those placeholders.

**Expected edits:**
- `/Users/karl.sabo/git/dev-lake-utils/utilities/src/commonMain/kotlin/com/github/karlsabo/git/WorktreeSetupCoordinator.kt`
- `/Users/karl.sabo/git/dev-lake-utils/utilities/src/commonTest/kotlin/com/github/karlsabo/git/WorktreeSetupCoordinatorTest.kt`
- `/Users/karl.sabo/git/dev-lake-utils/eng-hub/README.md` or `/Users/karl.sabo/git/dev-lake-utils/README.md` for setup command examples
- `/Users/karl.sabo/git/dev-lake-utils/plans/idea-project-helper.md`

**Scope:**
- In: replace literal `$root-repo-dir` with `request.repoPath` before building the shell script.
- In: replace literal `$worktree-dir` with `request.worktreePath.value` before building the shell script.
- In: add tests for placeholder expansion in setup command script generation or command runner behavior.
- In: document a setup command example that invokes the KTS IDEA copy script.
- Out: adding real process environment variables.
- Out: automatic Eng Hub-specific IntelliJ integration.
- Out: `.iml` renaming, optional excludes, dry-run, Gradle packaging, or native binaries.

**Notes:**
- Example config command after story 2 exists:

  ```bash
  kotlin /Users/karl.sabo/git/dev-lake-utils/scripts/idea-tool.kts "$root-repo-dir/.idea" "$worktree-dir/.idea"
  ```

- The placeholders are replaced before shell execution, so quoting them like normal path strings is still important.

### 2. KTS script copies IDEA project template into a worktree

**Acceptance criteria:** Given the `.idea` acceptance-test fixture above, when I run `kotlin scripts/idea-tool.kts /tmp/base/.idea /tmp/worktree/.idea`, then the target `.idea` is a refreshed copy of the source `.idea` with absolute source repo paths rewritten to the target repo path.

**Expected edits:**
- `/Users/karl.sabo/git/dev-lake-utils/scripts/idea-tool.kts`
- `/Users/karl.sabo/git/dev-lake-utils/README.md` or `/Users/karl.sabo/git/dev-lake-utils/eng-hub/README.md`
- `/Users/karl.sabo/git/dev-lake-utils/plans/idea-project-helper.md`

**Scope:**
- In: recursively copy every file and directory from source `.idea` to target `.idea`.
- In: create target `.idea` if missing.
- In: overwrite copied target files by default.
- In: infer source repo root as parent of source `.idea` and target repo root as parent of target `.idea`.
- In: rewrite exact absolute source repo root strings to the target repo root in copied text files.
- In: basic argument validation: source exists, source/target paths are named `.idea`, and source/target are not the same path.
- In: usage notes showing manual invocation and Eng Hub setup-command invocation using `$root-repo-dir` and `$worktree-dir`.
- Out: optional excludes, dry-run, merge behavior, `.iml` renaming, Gradle packaging, native binaries, and Eng Hub code changes.

**Notes:**
- Copying ignored IntelliJ files is intentional because the base checkout acts as the worktree template.
- Evidence for copied template content: `/Users/karl.sabo/Klaviyo/Repos/app/.idea/workspace.xml`, `/Users/karl.sabo/Klaviyo/Repos/app/.idea/dataSources.local.xml`, `/Users/karl.sabo/Klaviyo/Repos/app/.idea/dataSources/`, `/Users/karl.sabo/Klaviyo/Repos/app/.idea/runConfigurations/App_HTTP.xml`, `/Users/karl.sabo/Klaviyo/Repos/app/.idea/modules.xml`, `/Users/karl.sabo/Klaviyo/Repos/app/.idea/app.iml`.

## Deferred / rejected for now

- **Real shell environment variables named `root-repo-dir` / `worktree-dir`**: rejected because hyphens are not valid shell variable names. Literal placeholder substitution gives the requested config syntax without shell-variable issues.
- **Optional excludes / dry-run**: not needed for the current problem. Add only if full template copy proves noisy.
- **Rename `.iml` files to match worktree directory names**: rejected for now. IntelliJ uses `modules.xml` to locate module files. Keeping `.idea/app.iml` for every `app` worktree is simpler and should work.
- **Preserve target-local `workspace.xml` on rerun**: rejected. The accepted behavior is template refresh with overwrite.
- **Automatic Eng Hub IntelliJ integration**: deferred. User-configured setup commands are enough.
- **Gradle/native executable packaging**: deferred. Only revisit if the KTS script becomes hard to maintain or distribute. If revived, it should produce Linux, macOS, and Windows executables.
