# Unit-test checkout setup orchestration

**Goal:** Make the Eng Hub checkout setup orchestration test deterministic and fast by testing the setup request contract without launching a native shell.

**Context:**

- `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubCheckoutSetupViewModelTest.kt` currently launches the operating system's native shell in `checkoutAndOpenRunsUnifiedRepositorySetupCommands`, writes a marker file, and allows 30 seconds for cold PowerShell startup. This makes a view-model orchestration test dependent on process startup, filesystem cleanup, and runner load.
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/CheckoutController.kt` delegates setup through `WorktreeSetupCoordinator` using a `WorktreeSetupRequest`. The behavior owned by this layer is construction and delegation of that request, not shell execution.
- `utilities/src/commonMain/kotlin/com/github/karlsabo/git/WorktreeSetupCoordinator.kt` exposes `WorktreeSetupCommandRunner` as the injection seam for setup execution.
- `utilities/src/jvmTest/kotlin/com/github/karlsabo/git/ShellWorktreeSetupCommandRunnerTest.kt` already owns native-shell behavior, including PowerShell command encoding, command output, failures, and shared shell state.
- This optimization deliberately trades duplicate end-to-end coverage for faster and less flaky orchestration coverage. Native-shell execution remains covered at the utility boundary where it is implemented.

## Scan findings

The scan looked for native process execution, temporary filesystem use, real-time delays/timeouts, and Compose harness startup across all 66 test files.

High-value optimization candidates:

- `EngHubCheckoutSetupViewModelTest.kt`: one test starts a native shell and waits up to 30 seconds.
- `EngHubExistingWorktreeOpenViewModelTest.kt`: both tests start a native shell, poll marker files, and coordinate completion through the filesystem.
- `WorktreeSetupCoordinatorTest.kt`: three tests launch POSIX or PowerShell processes even though their assertions concern placeholder expansion or coordinator delegation; one concurrency fixture also uses a fixed 100 ms delay.

Heavy tests that should remain integration tests unless profiling says otherwise:

- `utilities/src/jvmTest/kotlin/com/github/karlsabo/git/ShellWorktreeSetupCommandRunnerTest.kt` owns actual shell execution and is the correct place for limited PowerShell/POSIX integration coverage.
- `utilities/src/commonTest/kotlin/com/github/karlsabo/git/GitCommandServiceTest.kt` exercises the real Git command boundary.
- `utilities/src/jvmTest/kotlin/com/github/karlsabo/notifications/NotificationDatabaseMigrationTest.kt` and its macOS counterpart exercise real database migrations.
- `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/LlmSkillSyncTest.kt` and `summary-publisher/src/commonTest/kotlin/com/github/karlsabo/devlake/tools/ui/SummaryPublisherDependenciesTest.kt` use temporary files, but filesystem behavior is part of what they verify.
- Compose UI tests are heavier than plain unit tests, but their semantics and interaction assertions belong at the UI boundary. They should be optimized only from measured runtime data, not converted blindly.

## Acceptance tests

1. **Checkout setup delegates the complete request without starting a native shell**

   Given an Eng Hub checkout configured with a repository setup command and an injected recording setup runner, when `checkoutAndOpen` is invoked, then the runner receives the expected repository path, worktree path, clone URL, branch, setup shell, and setup commands, and the checkout job completes without creating files or launching an OS process.

2. **Existing-worktree setup is coordinated without shell or filesystem signaling**

   Given an existing worktree with configured setup commands and an injected controllable setup runner, when the worktree is opened, then setup progress is visible until the runner completes and the runner receives the normalized repository setup request without launching a shell or polling marker files.

3. **Placeholder expansion is verified as a pure transformation**

   Given setup requests containing root/worktree placeholders and shell-sensitive path characters, when commands are expanded for POSIX and PowerShell dialects, then the resulting command strings contain the expected literal paths without executing a shell.

4. **Repository serialization is synchronized by events rather than elapsed time**

   Given two setup requests for the same repository and different worktrees, when the first repository ensure operation is blocked, then both requests report the expected status before release without relying on a fixed sleep.

5. **Native shell coverage is minimal and behavior tests use a process seam**

   Given a shell command runner with an injected process executor, when setup succeeds or fails, then result mapping and failure formatting are verified without starting a process, while one minimal smoke test per supported shell dialect proves the generated invocation runs under the real interpreter.

## Stories

### 1. Unit-test checkout setup request delegation

**Acceptance criteria:** Given an Eng Hub checkout configured with a repository setup command and an injected recording setup runner, when `checkoutAndOpen` is invoked, then the runner receives the expected repository path, worktree path, clone URL, branch, setup shell, and setup commands, and the checkout job completes without creating files or launching an OS process.

**Expected edits:**

- `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubCheckoutSetupViewModelTest.kt`
- Possibly `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModelTestFixtures.kt` only if a recording runner is reusable by more than this test; otherwise keep the fake local to the test.

**Scope:**

- Replace marker-file and native-shell execution in `checkoutAndOpenRunsUnifiedRepositorySetupCommands` with an injected recording `WorktreeSetupCommandRunner`.
- Assert the complete `WorktreeSetupRequest` contract passed through checkout orchestration.
- Remove the native-shell startup timeout and imports/helpers that become unused by this test.
- Run the targeted Eng Hub JVM test and formatting checks.

**Out of scope:**

- Changing production checkout or setup behavior.
- Removing native-shell integration coverage from `ShellWorktreeSetupCommandRunnerTest`.
- Refactoring unrelated filesystem-based tests.
- Changing GitHub Actions or Gradle task selection.

**Notes:**

- Use the existing `createCheckoutSetupViewModel` helper in `EngHubCheckoutSetupViewModelTest.kt` so the test continues through the real `CheckoutController` and `WorktreeSetupCoordinator` path.
- Have the fake runner return a successful `WorktreeSetupCommandResult`; do not emulate shell behavior or write a marker file.
- Prefer a test-local recording runner unless a second concrete use appears. Avoid adding a shared abstraction speculatively.
- Derive expected paths with `buildWorktreePath` so assertions remain portable across operating systems.
- Keep the utility integration tests unchanged because `ShellWorktreeSetupCommandRunnerTest.kt` is the correct boundary for proving actual PowerShell/POSIX execution.

### 2. Unit-test existing-worktree setup coordination

**Acceptance criteria:** Given an existing worktree with configured setup commands and an injected controllable setup runner, when the worktree is opened, then setup progress is visible until the runner completes and the runner receives the normalized repository setup request without launching a shell or polling marker files.

**Expected edits:**

- `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubExistingWorktreeOpenViewModelTest.kt`
- `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModelTestFixtures.kt` only if an existing controllable runner cannot express the test cleanly.

**Scope:**

- Replace both native-shell/marker-file tests with a controllable or recording `WorktreeSetupCommandRunner`.
- Preserve assertions for running/completed status, selected worktree path, no repository/worktree ensure calls, trailing-slash repository matching, and configured commands.
- Remove temporary directories, marker polling, shell-specific commands, and filesystem cleanup from this test class.

**Out of scope:** Actual command execution and working-directory behavior, which remain owned by `ShellWorktreeSetupCommandRunnerTest.kt`.

**Notes:** Use request assertions to prove the selected `WorktreePath`; the shell runner's integration test already proves that `request.worktreePath` becomes the process working directory.

### 3. Test setup placeholder expansion without native processes

**Acceptance criteria:** Given setup requests containing root/worktree placeholders and shell-sensitive path characters, when commands are expanded for POSIX and PowerShell dialects, then the resulting command strings contain the expected literal paths without executing a shell.

**Expected edits:**

- `utilities/src/commonTest/kotlin/com/github/karlsabo/git/WorktreeSetupCoordinatorTest.kt`
- Possibly a new focused common test file beside `ShellWorktreeSetupCommandRunner.kt` if moving transformation tests makes ownership clearer.

**Scope:**

- Replace `setupRunsPlaceholderExpandedCommandsInWorktreeDirectory`, `setupScriptEscapesDoubleQuotedPlaceholderValuesBeforeShellParsing`, and `setupScriptEscapesSingleQuotedPlaceholderValuesBeforeShellParsing` with pure assertions against `expandedSetupCommands` and/or generated script text.
- Keep dialect-specific quoting cases and hostile path characters.
- Remove `executeSetupScript` if no tests still use it.

**Out of scope:**

- `powerShellSetupCommandsResolveShellSensitivePathsLiterally`, which is the remaining Windows integration proof that PowerShell interprets generated commands correctly.
- Native execution tests in `ShellWorktreeSetupCommandRunnerTest.kt`.

**Notes:** These tests currently mix coordinator, command transformation, filesystem, and shell behavior. The pure tests should live closest to the transformation functions while a small number of integration tests retain actual interpreter coverage.

### 4. Remove fixed-delay synchronization from repository setup tests

**Acceptance criteria:** Given two setup requests for the same repository and different worktrees, when the first repository ensure operation is blocked, then both requests report the expected status before release without relying on a fixed sleep.

**Expected edits:**

- `utilities/src/commonTest/kotlin/com/github/karlsabo/git/WorktreeSetupCoordinatorTest.kt`

**Scope:** Replace the `delay(100.milliseconds)` call in `RepositorySerializationFixture.awaitFirstRepositoryEnsureBlocked` with an explicit signal emitted after the second request reaches the relevant synchronization point.

**Out of scope:** Production coordinator behavior and unrelated coroutine tests that use timeouts only as deadlock guards.

**Notes:** A timeout around an explicit event is acceptable as a failure bound. A fixed delay is not evidence that the intended state transition occurred and makes the test slower and scheduler-sensitive.

### 5. Minimize native shell integration coverage

**Acceptance criteria:** Given a shell command runner with an injected process executor, when setup succeeds or fails, then result mapping and failure formatting are verified without starting a process, while one minimal smoke test per supported shell dialect proves the generated invocation runs under the real interpreter.

**Expected edits:**

- `utilities/src/commonMain/kotlin/com/github/karlsabo/git/ShellWorktreeSetupCommandRunner.kt`
- `utilities/src/jvmTest/kotlin/com/github/karlsabo/git/ShellWorktreeSetupCommandRunnerTest.kt`
- Potentially `utilities/src/commonMain/kotlin/com/github/karlsabo/system/ProcessExecutor.kt` or a new small process-execution contract beside it.

**Scope:**

- Introduce the smallest explicit process-execution seam needed to test runner result handling without a native process.
- Convert overlapping success, failure-output, and PowerShell-error tests to use a fake executor where interpreter behavior is not the subject.
- Retain one minimal real POSIX smoke test on POSIX and one minimal real PowerShell smoke test on Windows.
- Keep pure command encoding and parser tests as unit tests.

**Out of scope:** Moving shell smoke tests to a separate CI workflow or adding runtime profiling infrastructure.

**Notes:** Do not mock `ProcessBuilder` or add a general process framework. A narrow function/interface matching the runner's actual dependency is enough. Keep real interpreter coverage only for the contract that cannot be proven from generated strings and fake results.

## Decisions

- Preserve one minimal real shell integration test where interpreter behavior matters. Treat POSIX and PowerShell as separate supported dialects, so each dialect keeps one smoke test on its native platform.
- Do not add a profiling ticket for Compose, Git, database, or filesystem suites. Leave those suites unchanged unless concrete CI timing or failure evidence identifies a problem.
