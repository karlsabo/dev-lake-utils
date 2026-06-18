# IntelliJ IDEA project helper for worktrees

**Goal**: Provide an `idea-tool source/.idea target/.idea` command that makes a new git worktree open in IntelliJ with the same useful project configuration as the base checkout without copying stale local state.

**Context**:

- Source request: `/Users/karl.sabo/git/dev-lake-utils/plans/idea-project-helper.md` is the repo-local plan stub for this feature.
- `dev-lake-utils` is a Gradle/Kotlin repo with subprojects declared in `/Users/karl.sabo/git/dev-lake-utils/settings.gradle.kts`; a new executable subproject is possible but heavier than a script.
- Existing IntelliJ-related code already lives in `/Users/karl.sabo/git/dev-lake-utils/utilities/src/commonMain/kotlin/com/github/karlsabo/intellij/ImlExcludeFolderManager.kt`, so a larger implementation should reuse or extend the `com.github.karlsabo.intellij` package rather than inventing a second IntelliJ utility area.
- Existing worktree creation is coordinated by `/Users/karl.sabo/git/dev-lake-utils/utilities/src/commonMain/kotlin/com/github/karlsabo/git/WorktreeSetupCoordinator.kt` and called from `/Users/karl.sabo/git/dev-lake-utils/eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/LocalWorktreeFromBaseCreator.kt`; automatic Eng Hub integration should be a later story, not part of the first CLI tracer bullet.
- Reviewed example IDEA folders:
  - `/Users/karl.sabo/Klaviyo/Repos/app/.idea` contains project files such as `app.iml`, `modules.xml`, `misc.xml`, shared run configs under `runConfigurations/`, plus local/transient files like `workspace.xml`, `dataSources.local.xml`, and `dataSources/`.
  - `/Users/karl.sabo/Klaviyo/Repos/k-repo/.idea` contains `k-repo.iml`, `modules.xml`, project dictionaries/inspection profiles, `workspace.xml`, local data source files, and a `shelf/` directory.
  - `/Users/karl.sabo/Klaviyo/Repos/fender/.idea` contains `fender.iml`, JS/TS config XML, `modules.xml`, and `workspace.xml`.
  - `/Users/karl.sabo/Klaviyo/Repos/infrastructure-deployment/.idea` contains `infrastructure-deployment.iml`, generated `libraries/`, `modules.xml`, and `workspace.xml`.
- `.idea/.gitignore` in `/Users/karl.sabo/Klaviyo/Repos/app/.idea/.gitignore` ignores `/workspace.xml`, `/shelf/`, `/queries/`, `/dataSources/`, `/dataSources.local.xml`, `/httpRequests/`, and `/libraries/`. Those should be treated as local state unless explicitly opted in.
- Project-level run configuration files in `/Users/karl.sabo/Klaviyo/Repos/app/.idea/runConfigurations/*.xml` use `$PROJECT_DIR$`, so they are safe to copy between worktrees.
- `.iml` files in the reviewed projects mostly use `$MODULE_DIR$` or `$PROJECT_DIR$` macros for content roots, source roots, template folders, and exclude folders; examples include `/Users/karl.sabo/Klaviyo/Repos/app/.idea/app.iml`, `/Users/karl.sabo/Klaviyo/Repos/k-repo/.idea/k-repo.iml`, `/Users/karl.sabo/Klaviyo/Repos/fender/.idea/fender.iml`, and `/Users/karl.sabo/Klaviyo/Repos/infrastructure-deployment/.idea/infrastructure-deployment.iml`.
- `workspace.xml` in the reviewed projects contains absolute checkout paths such as `last_opened_file_path` under the source repo. Copying it blindly risks pointing IntelliJ at the base checkout. The first version should skip it.

## Draft acceptance tests

These are intentionally split so each story has one observable behavior.

1. **Safe project config copy**
   - Given `/tmp/base/.idea` contains `modules.xml`, `app.iml`, `misc.xml`, `runConfigurations/App_HTTP.xml`, `workspace.xml`, `dataSources.local.xml`, and `dataSources/cache.xml`, when I run `idea-tool /tmp/base/.idea /tmp/worktree/.idea`, then `/tmp/worktree/.idea` contains the project files and run configuration, but does not contain `workspace.xml`, `dataSources.local.xml`, or `dataSources/`.

2. **Repeat runs preserve target-local state**
   - Given `/tmp/worktree/.idea/workspace.xml` already exists with target-local IntelliJ state, when I rerun `idea-tool /tmp/base/.idea /tmp/worktree/.idea`, then `workspace.xml` is unchanged while copied project files are refreshed from `/tmp/base/.idea`.

3. **Absolute source paths are rewritten in copied files**
   - Given a copied XML file contains `/tmp/base`, when I run `idea-tool /tmp/base/.idea /tmp/worktree/.idea`, then the output file contains `/tmp/worktree` and no `/tmp/base` references.

4. **Invalid input fails without partial target changes**
   - Given `/tmp/base/.idea` does not exist, when I run `idea-tool /tmp/base/.idea /tmp/worktree/.idea`, then the command exits non-zero, prints a clear error, and does not create `/tmp/worktree/.idea`.

5. **Installable executable wrapper**
   - Given a fresh `dev-lake-utils` checkout, when I build/install the tool through Gradle, then I can run `idea-tool /tmp/base/.idea /tmp/worktree/.idea` without invoking the Kotlin script path directly.

6. **Eng Hub automatic copy after worktree creation**
   - Given Eng Hub creates a worktree from `/Users/karl.sabo/Klaviyo/Repos/app`, when creation succeeds, then the new worktree gets safe IDEA project config copied from the base worktree before Eng Hub reports the worktree ready.

## Stories

### 1. Safe project config copy with a Kotlin script

**Acceptance criteria:** Given `/tmp/base/.idea` contains `modules.xml`, `app.iml`, `misc.xml`, `runConfigurations/App_HTTP.xml`, `workspace.xml`, `dataSources.local.xml`, and `dataSources/cache.xml`, when I run `idea-tool /tmp/base/.idea /tmp/worktree/.idea`, then `/tmp/worktree/.idea` contains the project files and run configuration, but does not contain `workspace.xml`, `dataSources.local.xml`, or `dataSources/`.

**Expected edits:**
- `/Users/karl.sabo/git/dev-lake-utils/scripts/idea-tool.main.kts` or equivalent script path.
- `/Users/karl.sabo/git/dev-lake-utils/plans/idea-project-helper.md` for the repo-local plan/usage notes.
- `/Users/karl.sabo/git/dev-lake-utils/README.md` only if we want the command discoverable immediately.

**Scope:**
- In: copy files/directories recursively from source `.idea` to target `.idea`.
- In: infer source repo root as parent of source `.idea` and target repo root as parent of target `.idea`.
- In: default skip list based on reviewed `.idea/.gitignore`: `workspace.xml`, `shelf/`, `queries/`, `dataSources/`, `dataSources.local.xml`, `httpRequests/`, `libraries/`.
- In: create target `.idea` when absent.
- Out: Gradle packaging, Eng Hub integration, project/module renaming, and opt-in copying of local state.

**Notes:**
- Start with a script as the tracer bullet. A subproject is overkill until the copy rules need real tests, XML parsing, or distribution.
- Evidence for skip list: `/Users/karl.sabo/Klaviyo/Repos/app/.idea/.gitignore`.
- Evidence that useful shared config lives outside skipped state: `/Users/karl.sabo/Klaviyo/Repos/app/.idea/runConfigurations/App_HTTP.xml`, `/Users/karl.sabo/Klaviyo/Repos/app/.idea/modules.xml`, `/Users/karl.sabo/Klaviyo/Repos/app/.idea/app.iml`.

### 2. Preserve target-local IntelliJ state on repeat runs

**Acceptance criteria:** Given `/tmp/worktree/.idea/workspace.xml` already exists with target-local IntelliJ state, when I rerun `idea-tool /tmp/base/.idea /tmp/worktree/.idea`, then `workspace.xml` is unchanged while copied project files are refreshed from `/tmp/base/.idea`.

**Expected edits:**
- `/Users/karl.sabo/git/dev-lake-utils/scripts/idea-tool.main.kts`.
- Add fixture-style script tests if the script has a test harness, or documented manual verification in `/Users/karl.sabo/git/dev-lake-utils/plans/idea-project-helper.md`.

**Scope:**
- In: skipped files/directories in the target are never deleted or overwritten.
- In: copied files are overwritten from source by default.
- Out: merge behavior for XML files.

**Notes:**
- This protects `workspace.xml`, `dataSources.local.xml`, and generated `libraries/`, which are present in reviewed repos and are local/transient.

### 3. Rewrite absolute source-root paths in copied text files

**Acceptance criteria:** Given a copied XML file contains `/tmp/base`, when I run `idea-tool /tmp/base/.idea /tmp/worktree/.idea`, then the output file contains `/tmp/worktree` and no `/tmp/base` references.

**Expected edits:**
- `/Users/karl.sabo/git/dev-lake-utils/scripts/idea-tool.main.kts` initially.
- If this becomes non-trivial, move logic into `/Users/karl.sabo/git/dev-lake-utils/utilities/src/commonMain/kotlin/com/github/karlsabo/intellij/` with tests under `/Users/karl.sabo/git/dev-lake-utils/utilities/src/commonTest/kotlin/com/github/karlsabo/intellij/`.

**Scope:**
- In: text-file replacement of source repo root with target repo root after copying.
- In: report replacements to stdout.
- Out: parsing every IntelliJ XML schema.

**Notes:**
- Most reviewed project files use `$PROJECT_DIR$`/`$MODULE_DIR$`, but `/Users/karl.sabo/Klaviyo/Repos/app/.idea/workspace.xml` and other workspace files contain absolute paths. Skipping `workspace.xml` avoids the worst case, but a rewrite pass makes the tool safer for future project files.

### 4. Fail safely on invalid input

**Acceptance criteria:** Given `/tmp/base/.idea` does not exist, when I run `idea-tool /tmp/base/.idea /tmp/worktree/.idea`, then the command exits non-zero, prints a clear error, and does not create `/tmp/worktree/.idea`.

**Expected edits:**
- `/Users/karl.sabo/git/dev-lake-utils/scripts/idea-tool.main.kts`.

**Scope:**
- In: validate source path exists and is a directory named `.idea`.
- In: validate target path ends in `.idea`.
- In: stage copy into a temporary directory before replacing/writing target files where practical.
- Out: rollback for every possible filesystem failure.

**Notes:**
- This is the first edge-path story. Do it after the happy path and repeat-run behavior are proven.

### 5. Promote to a Gradle-backed executable only if the script becomes painful

**Acceptance criteria:** Given a fresh `dev-lake-utils` checkout, when I build/install the tool through Gradle, then I can run `idea-tool /tmp/base/.idea /tmp/worktree/.idea` without invoking the Kotlin script path directly.

**Expected edits:**
- `/Users/karl.sabo/git/dev-lake-utils/settings.gradle.kts` to include a new subproject, likely `idea-tool`.
- `/Users/karl.sabo/git/dev-lake-utils/idea-tool/idea-tool.gradle.kts`.
- `/Users/karl.sabo/git/dev-lake-utils/idea-tool/src/jvmMain/kotlin/...` for CLI entrypoint if JVM distribution is enough.
- `/Users/karl.sabo/git/dev-lake-utils/utilities/src/commonMain/kotlin/com/github/karlsabo/intellij/` for reusable copy/path-rewrite logic.
- `/Users/karl.sabo/git/dev-lake-utils/utilities/src/commonTest/kotlin/com/github/karlsabo/intellij/` for tests.

**Scope:**
- In: installable command wrapper with the same behavior as the script.
- In: tests for copy, skip, idempotence, and path rewrite if logic moves out of the script.
- Out: native binaries for every OS unless JVM startup/distribution is actually a problem.

**Notes:**
- Don't start here. The requested behavior can be proven with a script. A subproject adds Gradle/config churn before we know the rules are right.
- Existing Gradle subproject layout is in `/Users/karl.sabo/git/dev-lake-utils/settings.gradle.kts` and shared conventions are under `/Users/karl.sabo/git/dev-lake-utils/buildSrc/src/main/kotlin/`.

### 6. Copy IDEA project config automatically from Eng Hub worktree creation

**Acceptance criteria:** Given Eng Hub creates a worktree from `/Users/karl.sabo/Klaviyo/Repos/app`, when creation succeeds, then the new worktree gets safe IDEA project config copied from the base worktree before Eng Hub reports the worktree ready.

**Expected edits:**
- `/Users/karl.sabo/git/dev-lake-utils/utilities/src/commonMain/kotlin/com/github/karlsabo/git/WorktreeSetupCoordinator.kt` or a collaborator called by it.
- `/Users/karl.sabo/git/dev-lake-utils/eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/LocalWorktreeFromBaseCreator.kt` if the base `.idea` path should be passed from UI/worktree request state.
- `/Users/karl.sabo/git/dev-lake-utils/eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubLocalWorktreeCreateHappyPathViewModelTest.kt` or adjacent tests.

**Scope:**
- In: after successful worktree creation, copy from `baseWorktreePath/.idea` to `worktreePath/.idea` using the already-proven safe-copy behavior.
- In: surface copy failures as setup failures or warnings; decide before implementation.
- Out: changing worktree creation itself.

**Notes:**
- Do this after the CLI behavior is stable. It couples IntelliJ-specific behavior to Eng Hub worktree setup, so it should be reversible and probably configurable.

## Open questions

1. Should the first version ever copy ignored/local IntelliJ state (`workspace.xml`, `dataSources.local.xml`, `dataSources/`, `libraries/`, `shelf/`), or is skipping them always correct?
2. Should the tool rename the module file/project identity for the target worktree, or is copying `app.iml` into an `app-some-branch` worktree acceptable?
3. Should overwriting copied target project files be the default, or should the command require `--force` once target `.idea` exists?
4. Do you want this run manually only, via configured Eng Hub setup commands, or automatically as part of Eng Hub worktree creation?
5. Is a JVM Gradle executable good enough if we promote from KTS, or do you specifically need native binaries for macOS/Linux/Windows?
