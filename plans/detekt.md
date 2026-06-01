# Detekt cleanup plan

**Goal**: Make the repository pass detekt without inline suppressions or a committed detekt baseline.

**Context**:

- Detekt is wired at the root in `dev-lake-utils.gradle.kts`; it scans root Gradle scripts, `buildSrc`, all subproject build files, and all subproject `src` trees.
- `dev-lake-utils.gradle.kts` currently points detekt at `config/detekt/baseline.xml`, so CI can pass while 109 current issues remain hidden.
- `config/detekt/baseline.xml` contains 109 current issues. Biggest groups: `MaxLineLength` (41), `LongMethod` (17), `MagicNumber` (14), `LongParameterList` (10), `TooGenericExceptionCaught` (8), `TooGenericExceptionThrown` (5), plus smaller `ReturnCount`, `MatchingDeclarationName`, `NestedBlockDepth`, `TooManyFunctions`, `UseCheckOrError`, `CyclomaticComplexMethod`, `SpreadOperator`, and `TopLevelPropertyNaming` entries.
- There are 40 inline suppressions found by `rg '@Suppress|@file:Suppress|Suppress\('`, including `TooManyFunctions`, `LargeClass`, `LongParameterList`, `MagicNumber`, `MatchingDeclarationName`, `SameParameterValue`, and `unused`.
- `README.md` documents `./gradlew detekt` and `./gradlew check` as the verification path. The final state should keep that path, not weaken it.
- Trade-off: slice by code ownership/behavior, not by detekt rule. Rule-by-rule PRs would touch unrelated product areas in one PR and create more merge conflict risk.

## Definition of done

- `rg '@Suppress|@file:Suppress|Suppress\(' --glob '!build/**' --glob '!**/.gradle/**' --glob '!plans/**' --glob '!*.md'` returns no source suppressions.
- `config/detekt/baseline.xml` is deleted, empty, or no longer referenced by detekt.
- `./gradlew detekt` passes without a baseline.
- `./gradlew check` passes.
- No detekt rule is disabled or loosened to make this work.

## Acceptance tests to cover

1. Given utility helpers with `unused`/naming suppressions, when detekt runs without suppressions, then helper APIs are either used, tested, renamed/moved, or deleted as dead code.
2. Given broad API/client classes, when detekt runs, then each API surface is decomposed by responsibility and no class/interface trips `TooManyFunctions`.
3. Given long parameter lists, when callers invoke the same behavior, then they pass cohesive request/action objects instead of argument trains.
4. Given large test classes and long tests, when the relevant tests run, then behavior coverage remains but fixtures/helpers keep each test below detekt thresholds.
5. Given UI composables that currently mix state collection, actions, dialogs, and rows, when the app is used, then visible behavior is unchanged and composables pass detekt.
6. Given generic exception handling and magic numbers, when failure paths execute, then callers still get useful domain-specific errors and constants document non-obvious values.
7. Given the cleanup stories are complete, when the final verification PR runs, then detekt and check pass with no suppressions or baseline.

## Stories

### 1. Utility helper suppressions are removed (Finished 2026-06-01) - Done

**Acceptance criteria:** Given a clean checkout, when a developer runs `./gradlew detekt`, then these utility/config helper files produce no inline suppression or baseline issue: `utilities/utilities.gradle.kts`, `utilities/src/commonMain/kotlin/com/github/karlsabo/common/datetime/DateTimeFormatting.kt`, `utilities/src/commonMain/kotlin/com/github/karlsabo/common/pagination/CursorPagination.kt`, `utilities/src/commonMain/kotlin/com/github/karlsabo/github/GitHubUrlUtils.kt`, `utilities/src/commonMain/kotlin/com/github/karlsabo/github/config/GitHubConfig.kt`, `utilities/src/commonMain/kotlin/com/github/karlsabo/jira/config/JiraConfig.kt`, `utilities/src/commonMain/kotlin/com/github/karlsabo/linear/config/LinearConfig.kt`, `utilities/src/commonMain/kotlin/com/github/karlsabo/pagerduty/PagerDutyRestApi.kt`, and `utilities/src/commonMain/kotlin/com/github/karlsabo/tools/DirectoryUtils.kt`.

**Expected edits:**

- Remove or prove usage for `unused` helpers in `DateTimeFormatting.kt`, `GitHubUrlUtils.kt`, config save functions, `DirectoryUtils.kt`, and the Gradle task action in `utilities.gradle.kts`.
- Fix `CursorPagination.kt` by matching file/type naming or moving `CursorPageResult` to its own file.
- Add/adjust focused tests where retaining a public helper is intentional.

**Scope:** In: dead-code deletion, renames/moves, focused tests. Out: API client decomposition and exception taxonomy beyond files listed here.

**Notes:** Do not keep helpers just because they might be useful later. If no code or test needs them, delete them.

### 2. Git command and worktree APIs are split by responsibility (Finished 2026-06-01) - Done

**Acceptance criteria:** Given the worktree tests, when `./gradlew :utilities:allTests detekt` runs, then git clone/fetch/worktree/archive behavior still passes and no `@Suppress("TooManyFunctions")` or git-related baseline entry remains for `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitCommandApi.kt`, `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitCommandService.kt`, or `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitWorktreeService.kt`.

**Expected edits:**

- Split `GitCommandApi` into narrower protocols such as repository inspection, branch/ref, worktree, and raw command execution.
- Split `GitCommandService` along those same boundaries or delegate internally so no class owns every command.
- Split `GitWorktreeService` orchestration into focused collaborators for repository resolution, branch worktree creation, listing/parsing, dirty checks, and archive/removal.
- Move or delete the suppressed `sanitizeBranchName` companion alias in `GitWorktreeService.kt`; prefer direct top-level usage if compatibility is not needed.
- Update `utilities/src/commonTest/kotlin/com/github/karlsabo/git/GitWorktreeServiceTest.kt`, `utilities/src/commonTest/kotlin/com/github/karlsabo/git/GitCommandServiceTest.kt`, and `utilities/src/commonTest/kotlin/com/github/karlsabo/git/WorktreeSetupCoordinatorTest.kt` as needed.

**Scope:** In: git module structure and tests. Out: EngHub UI behavior.

**Notes:** Keep dependencies explicit. Avoid a hidden service locator that just moves dynamic coupling elsewhere.

### 3. GitHub API client is decomposed into focused ports

**Acceptance criteria:** Given metrics, summary, and EngHub notification flows, when their tests run with detekt, then GitHub search, notification, review, and CI behavior still works and no `TooManyFunctions` suppression remains in `utilities/src/commonMain/kotlin/com/github/karlsabo/github/GitHubApi.kt` or `utilities/src/commonMain/kotlin/com/github/karlsabo/github/GitHubRestApi.kt`.

**Expected edits:**

- Split `GitHubApi` into smaller interfaces for pull request metrics/search, notifications, pull request actions/reviews, and check-run/review summary reads.
- Split `GitHubRestApi` into focused REST clients or delegates that share HTTP setup without one class implementing every method.
- Update consumers in `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub`, `summary-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/tools`, `user-metrics-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/metrics`, and related tests.

**Scope:** In: GitHub port boundaries and consumer constructor types. Out: project-management clients.

**Notes:** A temporary compatibility facade is acceptable only if it does not trigger detekt and is removed or narrowed before the final baseline-removal story.

### 4. Jira and Linear clients are decomposed without changing project-management behavior

**Acceptance criteria:** Given project summary and user metrics tests, when detekt runs, then Jira/Linear issue, comment, milestone, and filter behavior is unchanged and no `TooManyFunctions` suppression remains in `utilities/src/commonMain/kotlin/com/github/karlsabo/jira/JiraRestApi.kt`, `utilities/src/commonMain/kotlin/com/github/karlsabo/linear/LinearRestApi.kt`, or `utilities/src/commonMain/kotlin/com/github/karlsabo/linear/query/LinearQueryBuilder.kt`.

**Expected edits:**

- Split Jira HTTP/JQL execution from `ProjectManagementApi` orchestration in `JiraRestApi.kt`.
- Split Linear GraphQL execution, query construction, pagination, issue reads, comments, and milestones in `LinearRestApi.kt`.
- Split `LinearQueryBuilder.kt` into focused query/filter builders or smaller files.
- Remove the `SameParameterValue` suppression on Jira page-size plumbing by making the page-size an explicit collaborator/config value or constant.
- Update project-management tests and mocks as needed.

**Scope:** In: Jira/Linear internals and call sites. Out: GitHub client boundaries.

**Notes:** Preserve `ProjectManagementApi` as the external abstraction unless a split has a clear consumer payoff.

### 5. Notification ignore persistence uses a cohesive request object

**Acceptance criteria:** Given EngHub notification persistence tests, when a notification is marked done, unsubscribed, or automatically hidden, then the same thread fields are persisted and no `LongParameterList`/`SameParameterValue` suppressions remain in notification ignore persistence.

**Expected edits:**

- Introduce a request/data object for saving ignored notification threads in `utilities/src/commonMain/kotlin/com/github/karlsabo/notifications/NotificationIgnoreStore.kt`.
- Update `utilities/src/commonMain/kotlin/com/github/karlsabo/notifications/SqlDelightNotificationIgnoreStore.kt` to accept the request object.
- Update `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/IgnoredNotificationPersistence.kt` to build that request for UI and GitHub notifications.
- Update tests in `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubNotificationPersistenceViewModelTest.kt`, `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/EngHubDependenciesTest.kt`, and notification store tests.

**Scope:** In: notification ignore save path only. Out: unrelated notification polling behavior.

**Notes:** This should shrink call sites and remove the temptation to suppress long parameter lists in tests.

### 6. EngHub worktree UI is split into state, actions, rows, and dialogs

**Acceptance criteria:** Given the Worktrees pane, when a user adds a repository, expands it, opens a worktree, creates a worktree, archives a clean worktree, or confirms force archive, then behavior is unchanged and `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreePanel.kt` has no `LongMethod`, `LongParameterList`, or `TooManyFunctions` baseline entry.

**Expected edits:**

- Introduce cohesive action/state types for worktree panel callbacks and dialog state.
- Split `WorktreePanel.kt` into smaller files, likely `WorktreePanel.kt`, `WorktreeRows.kt`, `WorktreeDialogs.kt`, and `WorktreeCreateValidation.kt`.
- Keep pure validation helpers tested in `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreePanelTest.kt`.
- Update `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/screen/EngHubScreen.kt` call sites.

**Scope:** In: Worktrees pane UI structure. Out: ViewModel worktree orchestration.

**Notes:** Do not bury callbacks in a global object; pass explicit action objects from the screen.

### 7. EngHub screen state collection is extracted from rendering

**Acceptance criteria:** Given the EngHub desktop app, when a user switches between Pull Requests, Notifications, and Worktrees panes, then visible behavior is unchanged and no baseline entry remains for `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/screen/EngHubScreen.kt` or nearby EngHub UI max-line-length entries.

**Expected edits:**

- Extract state collection from `engHubScreen` into a UI state holder and actions object.
- Extract pane rendering functions for pull requests, notifications, and worktrees.
- Wrap or extract long expressions in `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHub.kt`, `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/NotificationPolling.kt`, `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/IgnoredNotificationHelpers.kt`, and `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/LocalRepositoryExpansionTracker.kt`.

**Scope:** In: EngHub screen composition and line-length cleanup around EngHub UI. Out: Worktree panel internals covered by Story 6.

**Notes:** The acceptance test is user-visible pane behavior; line wrapping alone is not the story.

### 8. EngHub ViewModel tests are split by behavior

**Acceptance criteria:** Given EngHub ViewModel tests, when `./gradlew :eng-hub:allTests detekt` runs, then checkout, create-worktree, archive, and notification behavior remains covered and no `LargeClass`, `LongMethod`, `LongParameterList`, or `SameParameterValue` suppression remains in `EngHubViewModelTest.kt` or `EngHubNotificationPersistenceViewModelTest.kt`.

**Expected edits:**

- Split `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModelTest.kt` into behavior-specific test classes matching existing controllers.
- Split `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubNotificationPersistenceViewModelTest.kt` by persistence behavior.
- Replace long fixture factories with builders/defaulted data classes so test helpers do not need suppressions.
- Remove unused test seams in `EngHubViewModel.kt` or move them behind explicit test-only collaborators.

**Scope:** In: EngHub ViewModel test structure and fixture APIs. Out: production behavior changes except removing test-only seams.

**Notes:** Avoid one giant shared fixture object; prefer small builders local to each test class.

### 9. Git and migration tests are split and fixture constants are named

**Acceptance criteria:** Given utility tests, when `./gradlew :utilities:allTests detekt` runs, then git worktree behavior and notification database migration behavior are still covered and no large-class, long-method, magic-number, max-line-length, or top-level-property-naming baseline entry remains for utility tests.

**Expected edits:**

- Split `utilities/src/commonTest/kotlin/com/github/karlsabo/git/GitWorktreeServiceTest.kt` into focused ensure/create/archive/parse tests.
- Extract or simplify the long serialized behavior test in `utilities/src/commonTest/kotlin/com/github/karlsabo/git/WorktreeSetupCoordinatorTest.kt`.
- Name constants in `utilities/src/jvmTest/kotlin/com/github/karlsabo/notifications/NotificationDatabaseMigrationTest.kt` and `utilities/src/macosArm64Test/kotlin/com/github/karlsabo/notifications/NotificationDatabaseMigrationTest.kt`.
- Rename the lower-case JSON constant in `utilities/src/commonTest/kotlin/com/github/karlsabo/pagerduty/PagerDutyIncidentTest.kt`.
- Wrap long command invocations in `utilities/src/commonTest/kotlin/com/github/karlsabo/git/GitCommandServiceTest.kt`.

**Scope:** In: utility tests. Out: production git service decomposition covered by Story 2.

**Notes:** Test splitting should preserve names that explain behavior; do not hide assertions in overly clever helpers.

### 10. Summary generation uses request objects and smaller collaborators

**Acceptance criteria:** Given summary creation tests, when a summary is created for configured projects with miscellaneous work and PagerDuty incidents, then the resulting `MultiProjectSummary` is unchanged and no `LongMethod`, `LongParameterList`, `MagicNumber`, or `SpreadOperator` baseline entry remains for `SummaryOrchestrator.kt` or `ProjectSummaryBuilder.kt`.

**Expected edits:**

- Introduce a `CreateSummaryRequest` and grouped dependency/context object in `utilities/src/commonMain/kotlin/com/github/karlsabo/tools/SummaryOrchestrator.kt`.
- Extract miscellaneous work collection and PagerDuty incident loading into focused collaborators/functions.
- Introduce a `ProjectSummaryRequest` or context object in `utilities/src/commonMain/kotlin/com/github/karlsabo/tools/ProjectSummaryBuilder.kt`.
- Replace magic numeric IDs/thresholds with named constants.
- Replace the spread-operator set initialization with `initialPullRequests.toMutableSet()` or equivalent.
- Update tests in `summary-publisher/src/commonTest/kotlin/com/github/karlsabo/devlake/tools/SummaryDetailTest.kt`.

**Scope:** In: summary-building internals and tests. Out: Slack formatting and publisher UI.

**Notes:** Keep this as an internal API refactor unless external callers force migration.

### 11. Summary publisher dependencies, UI, and tests are narrowed

**Acceptance criteria:** Given summary publisher tests, when configuration loading and publishing flows run, then behavior is unchanged and no detekt baseline entry remains for summary publisher dependency loading, screen parameters, UI demos, or tests.

**Expected edits:**

- Bundle dependency loader lambdas and component factory inputs in `summary-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/tools/SummaryPublisherDependencies.kt`.
- Replace the long `summaryPublisherScreen` parameter list in `summary-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/tools/ui/SummaryPublisherScreen.kt` with state/actions objects.
- Split or shorten `summary-publisher/src/commonTest/kotlin/com/github/karlsabo/devlake/tools/SummaryDetailTest.kt` and `summary-publisher/src/commonTest/kotlin/com/github/karlsabo/devlake/tools/ui/SummaryPublisherDependenciesTest.kt`.
- Fix `summary-publisher/src/commonTest/kotlin/com/github/karlsabo/devlake/tools/UiDemo.kt` by extracting demo data/types to matching files and shortening `main`.
- Replace `throw IllegalStateException("boom")` assertions with `error("boom")` or test-specific exceptions where detekt expects it.

**Scope:** In: summary publisher app/dependency/UI/test cleanup. Out: core summary algorithm covered by Story 10.

**Notes:** State/action objects are useful here because they map directly to UI behavior and reduce Compose churn.

### 12. User metrics publisher app, model, services, demo, and tests pass detekt

**Acceptance criteria:** Given user metrics publisher tests, when metrics are loaded, previewed, published, and publish failures occur, then behavior is unchanged and no baseline entry remains for the user metrics publisher module.

**Expected edits:**

- Bundle runtime dependencies for `user-metrics-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/metrics/ui/UserMetricPublisherApp.kt` to remove the long parameter list.
- Replace generic `Exception` throws/catches with domain-specific exceptions in `UserMetricPublisherApp.kt`.
- Name expectation constants and wrap long Slack lines in `user-metrics-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/metrics/model/UserMetrics.kt`.
- Extract HTTP success constants and typed failures in `user-metrics-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/metrics/service/ZapierMetricService.kt` and `user-metrics-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/metrics/service/UserMetricMessagePublisherService.kt`.
- Split/shorten `user-metrics-publisher/src/commonTest/kotlin/com/github/karlsabo/devlake/metrics/ui/UserMetricPublisherDependenciesTest.kt`.
- Simplify `user-metrics-publisher/src/commonTest/kotlin/com/github/karlsabo/devlake/metrics/UserEpicsWithIssuesDemo.kt` into argument parsing plus execution helpers with typed argument errors.

**Scope:** In: metrics publisher module. Out: shared GitHub API decomposition covered by Story 3.

**Notes:** Keep failure messages stable enough that current UI tests can assert useful text.

### 13. Shared exception and parsing utilities use domain errors and single-exit flow

**Acceptance criteria:** Given desktop bootstrap, serialization, directory loading, markdown extraction, text summarization, and IntelliJ `.iml` parsing behavior, when success and failure tests run, then behavior is unchanged except for more specific error types and no generic-exception, return-count, nested-block-depth, max-line-length, or matching-declaration baseline entries remain in the listed shared utility files.

**Expected edits:**

- Replace broad catches/throws in `utilities/src/commonMain/kotlin/com/github/karlsabo/system/DesktopAppBootstrap.kt`, `utilities/src/commonMain/kotlin/com/github/karlsabo/system/DesktopLauncherService.kt`, `utilities/src/commonMain/kotlin/com/github/karlsabo/serialization/SerializationTools.kt`, `utilities/src/commonMain/kotlin/com/github/karlsabo/markdown/MarkdownImageExtractor.kt`, `utilities/src/commonMain/kotlin/com/github/karlsabo/github/GitHubNotificationService.kt`, and `utilities/src/commonMain/kotlin/com/github/karlsabo/text/TextSummarizerOpenAi.kt` with domain-specific exception types or `runCatching` boundaries.
- Refactor `utilities/src/commonMain/kotlin/com/github/karlsabo/tools/DirectoryUtils.kt` and `utilities/src/commonMain/kotlin/com/github/karlsabo/serialization/SerializationTools.kt` to avoid multiple returns while keeping readable failure handling.
- Extract helper functions from `utilities/src/commonMain/kotlin/com/github/karlsabo/intellij/ImlExcludeFolderManager.kt` to reduce nested block depth and return count.
- Split long setup helpers in `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/LlmSkillSyncTest.kt` if needed.

**Scope:** In: shared failure/parsing utilities. Out: publisher-specific Zapier failures covered by Stories 11 and 12.

**Notes:** Do not convert every exception to one mega `DevLakeException`; specific boundary errors make failures easier to handle.

### 14. Slack and formatting helpers are made small and constant-driven

**Acceptance criteria:** Given Slack output tests or golden examples, when milestone, summary, and metrics Slack text is generated, then output remains intentional and no detekt baseline entry remains for Slack/formatting helper files.

**Expected edits:**

- Split `utilities/src/commonMain/kotlin/com/github/karlsabo/tools/formatting/SlackMarkdownFormatter.kt` by formatting concern to remove `TooManyFunctions`.
- Wrap/extract long helper names and strings in `utilities/src/commonMain/kotlin/com/github/karlsabo/tools/formatting/SlackMilestoneFormatting.kt`.
- Coordinate with `user-metrics-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/metrics/model/UserMetrics.kt` from Story 12 if constants are shared.

**Scope:** In: Slack formatting utilities. Out: publisher UI and HTTP services.

**Notes:** If output changes, add explicit test expectations; do not let detekt cleanup silently alter message content.

### 15. EngHub app icon drawing avoids magic-number suppression

**Acceptance criteria:** Given the EngHub desktop launcher, when the app icon is rendered, then the icon is visually equivalent and `eng-hub/src/jvmMain/kotlin/com/github/karlsabo/devlake/enghub/main.kt` has no `MagicNumber` or `SameParameterValue` suppression.

**Expected edits:**

- Replace raw coordinate/color literals in `main.kt` with named constants or a small icon geometry model.
- Remove the same-parameter suppression by inlining `DOCK_ICON_SIZE` at the only call site or making rendering size a tested parameter.
- Keep `setDockIcon` failure behavior unchanged.

**Scope:** In: EngHub JVM launcher icon only. Out: Compose UI.

**Notes:** This is intentionally isolated because icon drawing magic numbers are noisy and easy to conflict with UI refactors.

### 16. Final no-baseline verification

**Acceptance criteria:** Given all prior stories are merged, when a developer runs `rg '@Suppress|@file:Suppress|Suppress\(' --glob '!build/**' --glob '!**/.gradle/**' --glob '!plans/**' --glob '!*.md'`, `./gradlew detekt`, and `./gradlew check`, then the grep finds no suppressions and both Gradle commands pass without `config/detekt/baseline.xml` being referenced.

**Expected edits:**

- Remove `baseline = file("config/detekt/baseline.xml")` from `dev-lake-utils.gradle.kts`.
- Delete `config/detekt/baseline.xml` or replace it with an intentionally empty generated baseline only if the tooling requires the file.
- Update any README/developer docs if they mention the baseline.
- Run final repository-wide formatting and tests.

**Scope:** In: build configuration and final verification only. Out: feature refactors; any new detekt failure found here should go back to the responsible earlier story.

**Notes:** This PR should be boring. If it contains significant refactoring, the previous slices were too broad or incomplete.
