# Windows Build Fixes

**Goal:** Make Windows a first-class supported platform by running setup commands through native PowerShell and ensuring CI executes tests instead of restoring prior test results.

## Context

- GitHub Actions run `28899529340` failed only in `build-windows`; Linux and macOS passed. Windows reported 17 failures in `:utilities:jvmTest` and 12 failures in `:eng-hub:jvmTest`.
- Run `28900549449` later passed, but its Windows job restored `:utilities:jvmTest` and `:eng-hub:jvmTest` from Gradle's build cache. The restored key was Windows-specific (`gradle-home-v1|Windows-X64|build-windows...`), so this demonstrates same-OS reuse from a prior run, not cross-OS reuse.
- `.github/workflows/build.yml` runs `./gradlew clean build`, while `gradle.properties` enables the Gradle build cache with `org.gradle.caching=true`.
- Twelve utilities failures came from POSIX-dependent fixture writes in `utilities/src/commonTest/kotlin/com/github/karlsabo/git/GitCommandServiceTest.kt`.
- Five utilities failures came from setup tests that configure `/bin/sh` in `utilities/src/jvmTest/kotlin/com/github/karlsabo/git/ShellWorktreeSetupCommandRunnerTest.kt` and `utilities/src/commonTest/kotlin/com/github/karlsabo/git/WorktreeSetupCoordinatorTest.kt`.
- Production setup execution in `utilities/src/commonMain/kotlin/com/github/karlsabo/git/ShellWorktreeSetupCommandRunner.kt` always invokes `<shell> -l -c <script>` and generates a POSIX script.
- Five Eng Hub failures came from an unclosed source in `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/LlmSkillSyncTest.kt`.
- Seven Eng Hub failures occurred in checkout and existing-worktree setup tests. Several configure `/bin/bash`, but two checkout tests use `BlockingCoordinatorSetupRunner`; native shell support alone may not explain those timeouts.
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHubConfig.kt` currently hard-codes `setupShell = "/bin/zsh"`.

## Decisions

1. Windows setup commands use `powershell.exe`.
2. `cmd` and automatic `pwsh` discovery are out of scope.
3. Explicitly configured POSIX shells continue to use the existing POSIX invocation and script syntax.
4. PowerShell setup commands share shell state, including directory and variable changes.
5. Setup continues after a command fails, including a PowerShell terminating error, matching current POSIX behavior.
6. The first native nonzero exit code becomes the overall exit code. A PowerShell error without a native numeric exit code uses `1`.
7. Replace the hard-coded `/bin/zsh` property default with an OS-aware default: `powershell.exe` on Windows and `/bin/zsh` on macOS/Linux.
8. Existing saved configs with an explicit `setupShell` remain unchanged. This work does not guess whether a saved `/bin/zsh` value was user-selected.
9. All CI Gradle builds run with `--no-build-cache`; local Gradle caching remains enabled.
10. Each story has one acceptance test, one ticket, and one PR.

## Stories

### 1. Close LlmSkillSync test file handles ✅

**Acceptance criteria:** Given a Windows runner, when `./gradlew :eng-hub:jvmTest --tests com.github.karlsabo.devlake.enghub.LlmSkillSyncTest --no-build-cache` runs, then every `LlmSkillSyncTest` passes and its temporary directories are deleted without file-lock errors.

**Expected edits:**
- `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/LlmSkillSyncTest.kt`

**Scope:**
- In: close buffered sources in test helpers, especially `readFile`.
- Out: production synchronization behavior.

**Notes:**
- Use `fs.source(path).buffered().use { it.readString() }`, matching `readText` in `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModelTestFixtures.kt`.

### 2. Make GitCommandService fixtures path-portable ✅

**Acceptance criteria:** Given a Windows runner with Git installed, when `./gradlew :utilities:jvmTest --tests com.github.karlsabo.git.GitCommandServiceTest --no-build-cache` runs, then the Git integration suite creates its fixture commits without a POSIX shell and all tests pass.

**Expected edits:**
- `utilities/src/commonTest/kotlin/com/github/karlsabo/git/GitCommandServiceTest.kt`

**Scope:**
- In: replace `sh -c` fixture writes with Kotlin file IO and fail immediately when fixture Git commands fail.
- Out: production `GitCommandService` changes unless the portable fixtures expose a production defect.

**Notes:**
- The failures share fixture setup knowledge, so this remains one portability story even though the fixture supports multiple Git scenarios.
- Write files through `SystemFileSystem` under `Path(repoDir, fileName)` instead of interpolating paths into shell commands.

### 3. Run successful setup commands through PowerShell ✅

**Acceptance criteria:** Given a Windows runner, when `./gradlew :utilities:jvmTest --tests 'com.github.karlsabo.git.ShellWorktreeSetupCommandRunnerTest.setupCommandsShareShellState' --no-build-cache` runs, then setup uses `powershell.exe`, a directory and variable set by earlier commands remain available to a later command, the later command writes the expected file, and the runner returns `0`.

**Expected edits:**
- `utilities/src/commonMain/kotlin/com/github/karlsabo/git/ShellWorktreeSetupCommandRunner.kt`
- `utilities/src/jvmTest/kotlin/com/github/karlsabo/git/ShellWorktreeSetupCommandRunnerTest.kt`
- Possibly a shell-dialect or script-builder file under `utilities/src/commonMain/kotlin/com/github/karlsabo/git/`

**Scope:**
- In: recognize `powershell.exe`, invoke it with PowerShell arguments such as `-NoProfile -Command`, and generate a successful PowerShell setup script.
- In: preserve existing POSIX behavior for explicitly configured POSIX shells.
- Out: failure reporting and placeholder escaping.

### 4. Report native command failures from PowerShell ✅

**Acceptance criteria:** Given a Windows runner, when `./gradlew :utilities:jvmTest --tests 'com.github.karlsabo.git.ShellWorktreeSetupCommandRunnerTest.setupFailureReportsPerCommandOutputAndRunsCommandsAfterFailure' --no-build-cache` runs, then a child process invoked by one PowerShell setup command exits `23`, later setup commands still run, and `WorktreeSetupException` reports each command's stdout, stderr, status, plus overall exit code `23`.

**Expected edits:**
- `utilities/src/commonMain/kotlin/com/github/karlsabo/git/ShellWorktreeSetupCommandRunner.kt`
- `utilities/src/commonMain/kotlin/com/github/karlsabo/git/WorktreeSetupCommandFailureFormatter.kt`
- `utilities/src/commonMain/kotlin/com/github/karlsabo/git/WorktreeSetupCommandOutputParser.kt`
- `utilities/src/jvmTest/kotlin/com/github/karlsabo/git/ShellWorktreeSetupCommandRunnerTest.kt`

**Scope:**
- In: emit the existing per-command markers from PowerShell, capture `$LASTEXITCODE`, preserve the first native nonzero exit code, and continue subsequent commands.
- In: make the formatted shell invocation reflect PowerShell rather than always displaying `-l -c`.
- Out: PowerShell errors without a native exit code; covered by story 5.

### 5. Continue after a PowerShell error ✅

**Acceptance criteria:** Given a Windows runner, when a setup command raises a terminating PowerShell error, then the command is reported as failed with exit code `1`, subsequent setup commands run, and the overall setup exit code is `1` when no earlier native command returned a nonzero code.

**Expected edits:**
- `utilities/src/commonMain/kotlin/com/github/karlsabo/git/ShellWorktreeSetupCommandRunner.kt`
- `utilities/src/jvmTest/kotlin/com/github/karlsabo/git/ShellWorktreeSetupCommandRunnerTest.kt`

**Scope:**
- In: catch command-scoped PowerShell errors, capture their stderr, assign status `1`, and continue.
- Out: changing POSIX error behavior.

### 6. Expand placeholders safely for PowerShell ✅

**Acceptance criteria:** Given a Windows runner and repo/worktree paths containing valid shell-sensitive characters, when a PowerShell setup command uses `$root-repo-dir` and `$worktree-dir`, then both placeholders resolve to the exact literal paths without PowerShell re-expanding spaces, `$`, backticks, apostrophes, `&`, or brackets.

**Expected edits:**
- `utilities/src/commonMain/kotlin/com/github/karlsabo/git/ShellWorktreeSetupCommandRunner.kt`
- `utilities/src/commonMain/kotlin/com/github/karlsabo/git/ShellPlaceholderExpansion.kt`
- `utilities/src/commonTest/kotlin/com/github/karlsabo/git/WorktreeSetupCoordinatorTest.kt`

**Scope:**
- In: select placeholder escaping by shell dialect and add PowerShell-specific tests.
- In: retain existing POSIX unquoted, single-quoted, and double-quoted behavior.
- Out: new placeholder names.

**Notes:**
- Double quotes are not valid Windows path characters. End-to-end path cases must use valid Windows characters; arbitrary string escaping can be tested at the script-builder level.

### 7. Run placeholder-expanded setup in the worktree

**Acceptance criteria:** Given a Windows runner, when `WorktreeSetupCoordinator` executes a repository setup command through `powershell.exe`, then the command runs in the selected worktree and writes the exact expanded root-repository and worktree paths to the expected output.

**Expected edits:**
- `utilities/src/commonTest/kotlin/com/github/karlsabo/git/WorktreeSetupCoordinatorTest.kt`
- Production setup files only if stories 3-6 do not already provide the required behavior.

**Scope:**
- In: make `setupRunsPlaceholderExpandedCommandsInWorktreeDirectory` portable and prove the coordinator-to-runner path.
- Out: additional placeholder edge cases; covered by story 6.

### 8. Default Eng Hub to PowerShell on Windows

**Acceptance criteria:** Given a Windows runner, when `EngHubConfig` is created or decoded without an explicit `setupShell`, then its setup shell is `powershell.exe`.

**Expected edits:**
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHubConfig.kt`
- `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/EngHubConfigTest.kt`
- Possibly an OS-aware default helper using `osFamily()` from `utilities/src/commonMain/kotlin/com/github/karlsabo/system/Utils.kt`

**Scope:**
- In: remove the hard-coded property default and compute it from the operating-system family.
- In: preserve explicitly decoded `setupShell` values.
- Out: migrating existing config files.

### 9. Run Eng Hub checkout setup natively on Windows

**Acceptance criteria:** Given a Windows runner and an Eng Hub repository config containing a PowerShell setup command, when `./gradlew :eng-hub:jvmTest --tests 'com.github.karlsabo.devlake.enghub.viewmodel.EngHubCheckoutSetupViewModelTest.checkoutAndOpenRunsUnifiedRepositorySetupCommands' --no-build-cache` runs, then checkout setup executes in the selected worktree and writes the expected marker file there.

**Expected edits:**
- `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubCheckoutSetupViewModelTest.kt`
- Possibly `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModelTestFixtures.kt`

**Scope:**
- In: use OS-appropriate setup commands and path assertions in the real-shell checkout test.
- Out: tests that use fake or blocking setup runners.

### 10. Prevent CI from restoring test-task results

**Acceptance criteria:** Given any GitHub Actions build job, when it runs Gradle, then its test tasks execute for that job and are not satisfied by restored Gradle build-cache output from a prior run.

**Expected edits:**
- `.github/workflows/build.yml`

**Scope:**
- In: run Linux, macOS, and Windows builds with `./gradlew clean build --no-build-cache`.
- Out: disabling Gradle caching for local development or disabling dependency caching in `gradle/actions/setup-gradle`.

**Notes:**
- This trades CI speed for trustworthy test execution.
- Sequence this after the Windows fixes so it does not knowingly make the main branch red.

## Eng Hub validation gate

After story 9 and before story 10, run the complete Eng Hub JVM suite on Windows with `--no-build-cache`. The original run also failed these tests:

- `EngHubCheckoutSetupViewModelTest.checkoutAndOpenTracksCoordinatorStatusPerWorktreePath`
- `EngHubCheckoutSetupViewModelTest.matchingPullRequestAndNotificationRowsShareCheckoutSetupProgress`
- `EngHubExistingWorktreeOpenViewModelTest.openingExistingWorktreeRunsUnifiedRepositorySetupCommands`
- `EngHubExistingWorktreeOpenViewModelTest.openingExistingWorktreeRunsConfiguredSetupInSelectedWorktreePath`
- `EngHubExistingWorktreeFailureViewModelTest.openingExistingWorktreeSetupFailureSetsActionError`
- `EngHubExistingWorktreeProgressViewModelTest.concurrentDuplicateOpenAttemptsStartOneSetupJob`

Some may pass once real-shell failures stop consuming or disrupting the test suite. Do not assume that outcome. If any still fail, diagnose the observable behavior and create one story, acceptance test, ticket, and PR per remaining behavior before landing story 10.

## Delivery and sequencing

Each numbered story is a separate ticket and PR. A passing cached build is not acceptance evidence; attach a no-build-cache Windows execution of the story's named test to each PR.

1. Stories 1-2: isolated test-portability fixes.
2. Stories 3-7: PowerShell runner behavior, one acceptance test at a time.
3. Story 8: OS-aware Eng Hub default.
4. Story 9: Eng Hub end-to-end checkout proof.
5. Run the Eng Hub validation gate and add stories for any remaining failures.
6. Story 10: enforce no-build-cache execution in every CI build.
7. Final validation: a complete GitHub Actions run passes with no JVM test task reported as `FROM-CACHE`.
