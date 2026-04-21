# DI Cleanup Stories

**Goal**: Keep `kotlin-inject` + Anvil, but clean up the DI edges so app bootstrap is consistent, dependency bags are flatter, and DI-only wrapper types are reduced.

**Context**:

- The repo already uses DI at the app composition roots in `eng-hub`, `summary-publisher`, and `user-metrics-publisher`, with top-level components and constructor injection for some consumers: [eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHubComponent.kt](/Users/karl.sabo/git/dev-lake-utils/eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHubComponent.kt:20), [summary-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/tools/SummaryPublisherDependencies.kt](/Users/karl.sabo/git/dev-lake-utils/summary-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/tools/SummaryPublisherDependencies.kt:52), [user-metrics-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/metrics/UserMetricPublisherComponent.kt](/Users/karl.sabo/git/dev-lake-utils/user-metrics-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/metrics/UserMetricPublisherComponent.kt:20).
- Bootstrap is still repeated in each app entry point. `EngHub`, `SummaryPublisherApp`, and `UserMetricPublisherApp` each load config, create dependencies, manage startup state, and show error UI independently: [eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHub.kt](/Users/karl.sabo/git/dev-lake-utils/eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHub.kt:25), [summary-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/tools/ui/SummaryPublisherApp.kt](/Users/karl.sabo/git/dev-lake-utils/summary-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/tools/ui/SummaryPublisherApp.kt:45), [user-metrics-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/metrics/ui/UserMetricPublisherApp.kt](/Users/karl.sabo/git/dev-lake-utils/user-metrics-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/metrics/ui/UserMetricPublisherApp.kt:31).
- `UserMetricPublisherDependencies` is the least clean DI surface today because it nests `previewDependencies` and forwards most members back out through getters: [user-metrics-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/metrics/UserMetricPublisherDependencies.kt](/Users/karl.sabo/git/dev-lake-utils/user-metrics-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/metrics/UserMetricPublisherDependencies.kt:18).
- Some abstractions look more like DI-shaping helpers than stable domain concepts, especially `SummaryBuilder`, `SummaryMessagePublisher`, `UserMetricsBuilder`, and `UserMetricMessagePublisher`: [summary-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/tools/SummaryPublisherDependencies.kt](/Users/karl.sabo/git/dev-lake-utils/summary-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/tools/SummaryPublisherDependencies.kt:42), [user-metrics-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/metrics/UserMetricPublisherDependencies.kt](/Users/karl.sabo/git/dev-lake-utils/user-metrics-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/metrics/UserMetricPublisherDependencies.kt:42).
- Tests already inject fake loaders or fake component factories rather than relying on generated DI code, which is a good base for this cleanup: [eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/EngHubDependenciesTest.kt](/Users/karl.sabo/git/dev-lake-utils/eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/EngHubDependenciesTest.kt:25), [summary-publisher/src/commonTest/kotlin/com/github/karlsabo/devlake/tools/ui/SummaryPublisherDependenciesTest.kt](/Users/karl.sabo/git/dev-lake-utils/summary-publisher/src/commonTest/kotlin/com/github/karlsabo/devlake/tools/ui/SummaryPublisherDependenciesTest.kt:30), [user-metrics-publisher/src/commonTest/kotlin/com/github/karlsabo/devlake/metrics/ui/UserMetricPublisherDependenciesTest.kt](/Users/karl.sabo/git/dev-lake-utils/user-metrics-publisher/src/commonTest/kotlin/com/github/karlsabo/devlake/metrics/ui/UserMetricPublisherDependenciesTest.kt:39).
- Constraint: this plan keeps DI. No story should remove `kotlin-inject`, Anvil, or the app components defined under `buildSrc/src/main/kotlin/devlake.kotlin-inject-conventions.gradle.kts` and the app component files above.

## Planning Review Status

- Stories 2-7 completed a planning + skeptical review pass on **2026-04-20**.
- All stories in this plan are now implemented in the workspace.

## Acceptance Tests

1. Given `SummaryPublisherApp` starts with a missing `summary-publisher-config.json`, when startup fails, then the app still creates the same template files and shows the same error dialog, but does so through one reusable bootstrap path instead of app-specific startup code.
2. Given `UserMetricPublisherApp` loads successfully, when metrics are calculated and published, then the app still works without `previewDependencies` forwarding getters because one flat dependency object is injected and consumed directly.
3. Given `EngHub` starts with a valid config, when the screen opens, then it uses the same shared bootstrap pattern as the other desktop apps and still exposes the same `EngHubViewModel` behavior.
4. Given `UserMetricPublisherApp` calculates and publishes metrics, when DI constructs the graph, then it injects concrete application services instead of `UserMetricsBuilder` and `UserMetricMessagePublisher` function wrappers, while preserving the same user-visible output.
5. Given `SummaryPublisherApp` loads summaries and publishes them, when DI constructs the graph, then it injects concrete application services instead of `SummaryBuilder` and `SummaryMessagePublisher` wrappers, while preserving the same user-visible output.
6. Given a maintainer reads the root project docs after the cleanup lands, when they check the TODO list, then it no longer claims DI is missing.

## Stories

### 1. Shared Bootstrap Tracer Bullet In Summary Publisher

Status: **done 2026-04-17**

**Acceptance test:** Given `SummaryPublisherApp` starts with a missing `summary-publisher-config.json`, when startup fails, then the app still creates the same template files and shows the same error dialog, but does so through one reusable bootstrap path instead of app-specific startup code.

**Expected edits:** `summary-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/tools/ui/SummaryPublisherApp.kt`, new shared bootstrap helper under `utilities/src/commonMain/kotlin/com/github/karlsabo/...` or a new shared app-bootstrap module, `summary-publisher/src/commonTest/kotlin/com/github/karlsabo/devlake/tools/ui/SummaryPublisherDependenciesTest.kt`.

**Scope:** Introduce a shared bootstrap abstraction and migrate only `SummaryPublisherApp` to it. Preserve current startup behavior, template file creation, error text shape, and dependency loading behavior.

**Notes:** Start here because [summary-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/tools/ui/SummaryPublisherApp.kt](/Users/karl.sabo/git/dev-lake-utils/summary-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/tools/ui/SummaryPublisherApp.kt:101) already has the fullest bootstrap flow, so it is the best tracer bullet. Keep DI at the edge by continuing to call `loadSummaryPublisherDependencies(...)`, but move the startup-state mechanics into shared code. This story creates the reusable pattern for later stories.

### 2. Flatten User Metrics Dependencies

Status: **done 2026-04-20**

**Acceptance test:** Given `UserMetricPublisherApp` loads successfully, when metrics are calculated and published, then the app still works without `previewDependencies` forwarding getters because one flat dependency object is injected and consumed directly.

**Expected edits:** `user-metrics-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/metrics/UserMetricPublisherDependencies.kt`, `user-metrics-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/metrics/ui/UserMetricPublisherApp.kt`, `user-metrics-publisher/src/commonTest/kotlin/com/github/karlsabo/devlake/metrics/ui/UserMetricPublisherDependenciesTest.kt`.

**Scope:** Flatten `UserMetricPublisherDependencies`, remove the forwarding getters, and update the app/tests to consume the flatter shape. Do not yet replace the `fun interface` wrappers in this story.

**Notes:** This addresses the awkward nesting at [user-metrics-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/metrics/UserMetricPublisherDependencies.kt](/Users/karl.sabo/git/dev-lake-utils/user-metrics-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/metrics/UserMetricPublisherDependencies.kt:18). Keep the user-visible metric preview and publish behavior unchanged, as exercised by [user-metrics-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/metrics/ui/UserMetricPublisherApp.kt](/Users/karl.sabo/git/dev-lake-utils/user-metrics-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/metrics/ui/UserMetricPublisherApp.kt:101).

**Implementation dependency:** Can start now.

**Parallelism:** Safe to run in parallel with Story 4 and Story 6.

### 3. Migrate User Metrics Publisher To Shared Bootstrap

Status: **done 2026-04-20**

**Acceptance test:** Given `UserMetricPublisherApp` starts with a missing config file, when startup fails, then it uses the shared bootstrap path and still creates the default config file and shows the error dialog.

**Expected edits:** `user-metrics-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/metrics/ui/UserMetricPublisherApp.kt`, the shared bootstrap helper introduced in Story 1, `user-metrics-publisher/src/commonTest/kotlin/com/github/karlsabo/devlake/metrics/ui/UserMetricPublisherDependenciesTest.kt`.

**Scope:** Migrate only user-metrics-publisher onto the shared bootstrap helper. Preserve current config loading, config file creation, and startup error behavior.

**Notes:** This story should come after Story 1 so the shared helper already exists, and after Story 2 so the app migrates on top of the flatter dependency shape. The current duplicated bootstrap is at [user-metrics-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/metrics/ui/UserMetricPublisherApp.kt](/Users/karl.sabo/git/dev-lake-utils/user-metrics-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/metrics/ui/UserMetricPublisherApp.kt:77).

**Implementation dependency:** Requires Story 1 and Story 2.

**Parallelism:** After Story 2 lands, this can run independently of Story 4 and Story 6. Do not plan to run it in parallel with Story 5 unless the `user-metrics-publisher` write set is split more cleanly first.

### 4. Migrate Eng Hub To Shared Bootstrap

Status: **done 2026-04-20**

**Acceptance test:** Given `EngHub` starts with a valid config, when the app opens, then it uses the shared bootstrap pattern and still exposes the same `EngHubViewModel` behavior for opening URLs, checking out worktrees, and marking notifications done.

**Expected edits:** `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHub.kt`, `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHubDependencies.kt`, the shared bootstrap helper introduced in Story 1, `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/EngHubDependenciesTest.kt`.

**Scope:** Migrate only Eng Hub to the shared bootstrap helper. Preserve existing `loadEngHubViewModel()` semantics and screen behavior.

**Notes:** This completes the bootstrap cleanup across all three apps. The current Eng Hub bootstrap is the lightest but still duplicated at [eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHub.kt](/Users/karl.sabo/git/dev-lake-utils/eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHub.kt:32). Use the existing test seam in [eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/EngHubDependenciesTest.kt](/Users/karl.sabo/git/dev-lake-utils/eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/EngHubDependenciesTest.kt:25) to preserve behavior.

**Implementation dependency:** Requires Story 1 only, which is already done.

**Parallelism:** Safe to run in parallel with Story 2 and Story 6.

### 5. Replace User Metrics Function Wrappers With Injected Services

Status: **done 2026-04-20**

**Acceptance test:** Given `UserMetricPublisherApp` calculates and publishes metrics, when DI constructs the graph, then it injects concrete application services instead of `UserMetricsBuilder` and `UserMetricMessagePublisher` function wrappers, while preserving the same preview text and publish results.

**Expected edits:** `user-metrics-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/metrics/UserMetricPublisherComponent.kt`, `user-metrics-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/metrics/UserMetricPublisherDependencies.kt`, likely new service classes under `user-metrics-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/metrics/service/`, `user-metrics-publisher/src/commonTest/kotlin/com/github/karlsabo/devlake/metrics/ui/UserMetricPublisherDependenciesTest.kt`.

**Scope:** Replace `UserMetricsBuilder` and `UserMetricMessagePublisher` with constructor-injected service types. Preserve all current metric calculation and Slack publishing behavior. Do not touch summary-publisher in this story.

**Notes:** The DI-only wrappers are created in [user-metrics-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/metrics/UserMetricPublisherComponent.kt](/Users/karl.sabo/git/dev-lake-utils/user-metrics-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/metrics/UserMetricPublisherComponent.kt:31). Prefer small, concrete services with explicit method names over lambdas stored inside dependency bags. This story should come after Story 2 so the dependency surface is already flat.

**Implementation dependency:** Requires Story 2.

**Parallelism:** After Story 2 lands, this can run independently of Story 4 and Story 6. Do not plan to run it in parallel with Story 3 unless the `user-metrics-publisher` write set is split more cleanly first.

### 6. Replace Summary Publisher Function Wrappers With Injected Services

Status: **done 2026-04-20**

**Acceptance test:** Given `SummaryPublisherApp` loads summaries and publishes them, when DI constructs the graph, then it injects concrete application services instead of `SummaryBuilder` and `SummaryMessagePublisher` wrappers, while preserving the same summary text and publish behavior.

**Expected edits:** `summary-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/tools/SummaryPublisherDependencies.kt`, `summary-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/tools/ZapierSummaryPublisher.kt`, likely new service classes under `summary-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/tools/service/`, `summary-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/tools/ui/SummaryPublisherApp.kt`, `summary-publisher/src/commonTest/kotlin/com/github/karlsabo/devlake/tools/ui/SummaryPublisherDependenciesTest.kt`.

**Scope:** Replace `SummaryBuilder` and `SummaryMessagePublisher` with constructor-injected service types. Preserve current summary creation and publishing behavior.

**Notes:** The current wrappers live in [summary-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/tools/SummaryPublisherDependencies.kt](/Users/karl.sabo/git/dev-lake-utils/summary-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/tools/SummaryPublisherDependencies.kt:42). Keep `ZapierSummaryPublisher` if it remains a useful adapter, but inject a concrete publisher service rather than a wrapper interface if the interface is no longer pulling its weight.

**Implementation dependency:** Can start now.

**Parallelism:** Safe to run in parallel with Story 2 and Story 4.

### 7. Update README To Match The Chosen DI Direction

Status: **done 2026-04-20**

**Acceptance test:** Given a maintainer reads the root project docs after the cleanup lands, when they check the TODO list, then it no longer claims DI is missing.

**Expected edits:** `README.md`.

**Scope:** Remove or rewrite the stale `Add dependency injection` TODO. Do not broaden this into a larger docs rewrite.

**Notes:** The stale note is at [README.md](/Users/karl.sabo/git/dev-lake-utils/README.md:10). Keep this story last so the docs reflect the actual end state rather than the intent mid-refactor.

**Implementation dependency:** Requires Stories 2-6 to be merged first so the docs describe the settled code shape.

**Parallelism:** Do not run in parallel with the code stories. Keep this as the final cleanup.

## Ordering

1. Story 1 first. It creates the shared bootstrap tracer bullet.
2. Story 2 second. It simplifies the worst dependency bag before more migration work piles on.
3. Story 3 third. It validates the shared bootstrap on a second app with simple behavior.
4. Story 4 fourth. It finishes bootstrap consistency across all app roots.
5. Story 5 fifth. It removes DI-only wrappers in user-metrics-publisher after the dependency shape is flatter.
6. Story 6 sixth. It applies the same cleanup to summary-publisher.
7. Story 7 last. It updates docs after the code shape is settled.

## Implementation Parallelism

1. **Batch A after Story 1:** Story 2, Story 4, and Story 6 can run in parallel.
2. **Batch B after Story 2:** Story 3 and Story 5 both become available, but they should be sequenced unless their `user-metrics-publisher` write sets are split more cleanly.
3. **Batch C last:** Story 7 runs after the code stories are merged.

## Out Of Scope For This Slice

- Removing `kotlin-inject`, Anvil, or KSP.
- Replacing app components with explicit factory functions.
- Broad architecture changes in `utilities`.
- UI redesign, performance work, or new product features.
