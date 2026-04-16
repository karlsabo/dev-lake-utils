## Goal

Adopt `kotlin-inject` and `kotlin-inject-anvil` in this repository so the desktop apps use generated composition roots instead of hand-written dependency providers, while preserving the current app behavior and test seams.

## Context

- The repo is a Kotlin Multiplatform multi-project build with three desktop app modules and shared utilities in `settings.gradle.kts`, `eng-hub/eng-hub.gradle.kts`, `user-metrics-publisher/user-metrics-publisher.gradle.kts`, `summary-publisher/summary-publisher.gradle.kts`, and `utilities/utilities.gradle.kts`.
- The shared Gradle conventions currently apply Kotlin Multiplatform and serialization, but not KSP, in `buildSrc/src/main/kotlin/devlake.kotlin-multiplatform-conventions.gradle.kts` and `buildSrc/src/main/kotlin/devlake.kotlin-multiplatform-compose-conventions.gradle.kts`.
- The version catalog pins Kotlin `2.2.21` and does not yet declare KSP, `kotlin-inject`, or `kotlin-inject-anvil` in `gradle/libs.versions.toml`.
- `eng-hub` already has a clean manual composition seam in `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHubDependencies.kt`, and the app entrypoint still calls that seam from `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHub.kt`.
- `user-metrics-publisher` already has a manual dependency bundle and publisher abstraction in `user-metrics-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/metrics/UserMetricPublisherDependencies.kt`, wired from `user-metrics-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/metrics/ui/UserMetricPublisherApp.kt`.
- `summary-publisher` has local in-progress manual-DI work in `summary-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/tools/SummaryPublisherDependencies.kt`, `summary-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/tools/ui/SummaryPublisherApp.kt`, and `summary-publisher/src/commonTest/kotlin/com/github/karlsabo/devlake/tools/ui/SummaryPublisherDependenciesTest.kt`; those files are currently uncommitted in the worktree.
- Shared interfaces that are good DI targets already exist in `utilities`, including `GitWorktreeApi` in `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitWorktreeApi.kt`, `ProjectManagementApi` in `utilities/src/commonMain/kotlin/com/github/karlsabo/projectmanagement/ProjectManagementApi.kt`, `PagerDutyApi` in `utilities/src/commonMain/kotlin/com/github/karlsabo/pagerduty/PagerDutyApi.kt`, `GitHubApi` in `utilities/src/commonMain/kotlin/com/github/karlsabo/github/GitHubApi.kt`, and `DesktopLauncher` in `utilities/src/commonMain/kotlin/com/github/karlsabo/system/DesktopLauncher.kt`.
- The official `kotlin-inject` docs describe generated `@Component` graphs, constructor injection with `@Inject`, and `.create()` component construction: https://github.com/evant/kotlin-inject
- The official `kotlin-inject-anvil` docs add `@MergeComponent`, `@ContributesTo`, `@ContributesBinding`, and recommend `kspCommonMainMetadata` plus `runtime-optional` for `@SingleIn`: https://github.com/amzn/kotlin-inject-anvil
- The official `kotlin-inject-anvil` Kotlin Multiplatform guidance says common code should use an `expect` create function next to the component because generated code is not reliably visible from `commonMain`: https://github.com/amzn/kotlin-inject-anvil
- The official KSP multiplatform docs say KMP builds should use per-source-set configurations such as `kspCommonMainMetadata` instead of the older unified `ksp(...)`: https://kotlinlang.org/docs/ksp-multiplatform.html
- The official KSP releases page shows a Kotlin `2.2.21`-aligned release (`2.2.21-2.0.4`) published on October 28, 2025. Implementation should pin a version that is confirmed compatible with the repo’s Kotlin `2.2.21` baseline before code lands: https://github.com/google/ksp/releases

## Assumptions

- Keep config file loading at the application edge. Components should receive loaded config objects or derived values rather than own file I/O directly.
- Use one app scope per desktop app instead of introducing a shared repo-wide singleton scope.
- Use plain `kotlin-inject` for the component root and constructor injection, and use `kotlin-inject-anvil` where it removes binding boilerplate or provider-interface glue.
- Prefer app-local provider contributions for config-backed REST clients like `GitHubRestApi`, `LinearRestApi`, and `PagerDutyRestApi`; do not rewrite every utility constructor just to make it injectable.
- Treat the current manual dependency-provider types as migration scaffolding. Each app story should remove or shrink that scaffolding once the generated component is in place.

## Acceptance Tests

1. Given `EngHub` starts with a valid GitHub config, when startup runs, then `EngHubViewModel` is created from a generated `kotlin-inject`/Anvil component in `commonMain` instead of `defaultEngHubDependencyProvider`, and the window still opens with the same behavior.
2. Given `UserMetricPublisherApp` starts with a valid config, when metrics are loaded, then the preview is built from collaborators resolved by a generated component instead of `defaultUserMetricPublisherDependencyProvider`, and the preview text is unchanged.
3. Given `UserMetricPublisherApp` has loaded metrics, when Publish is clicked, then Slack delivery goes through a publisher bound in the generated graph and the existing success/failure button behavior is preserved.
4. Given `SummaryPublisherApp` starts with a valid config, when summary data is loaded, then the summary preview is built from collaborators resolved by a generated component instead of `defaultSummaryPublisherDependencyProvider`, and the preview text is unchanged.
5. Given `SummaryPublisherApp` has loaded a summary, when Publish is clicked, then Zapier delivery goes through a publisher bound in the generated graph instead of calling `ZapierService` directly from the composable.

## Stories

### 1. Replace `eng-hub` manual wiring with the first generated app component

**Acceptance test:** Given `EngHub` starts with a valid GitHub config, when startup runs, then `EngHubViewModel` is created from a generated `kotlin-inject`/Anvil component in `commonMain` instead of `defaultEngHubDependencyProvider`, and the window still opens with the same behavior.

**Expected edits:** `gradle/libs.versions.toml`; `buildSrc/src/main/kotlin/devlake.kotlin-multiplatform-conventions.gradle.kts` or a new inject-focused convention plugin under `buildSrc/src/main/kotlin/`; `eng-hub/eng-hub.gradle.kts`; `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHub.kt`; `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHubDependencies.kt`; new DI files under `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/`; `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/EngHubDependenciesTest.kt`; likely utility bindings in `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitWorktreeService.kt`, `utilities/src/commonMain/kotlin/com/github/karlsabo/github/GitHubNotificationService.kt`, and `utilities/src/commonMain/kotlin/com/github/karlsabo/system/DesktopLauncherService.kt`.

**Scope:** Introduce the repo’s KSP + `kotlin-inject` + Anvil bootstrap and prove the pattern in the smallest existing app graph. The implementation should include the KMP-safe component creation pattern (`expect` create function next to the component) and remove production use of the manual `EngHubDependencyProvider`.

**Notes:** This is the tracer-bullet PR. Keep the graph app-local with an `EngHubScope`. Use Anvil where it eliminates interface binding boilerplate, but do not generalize into a cross-app DI framework yet.

### 2. Move `user-metrics-publisher` preview loading onto a generated component

**Acceptance test:** Given `UserMetricPublisherApp` starts with a valid config, when metrics are loaded, then the preview is built from collaborators resolved by a generated component instead of `defaultUserMetricPublisherDependencyProvider`, and the preview text is unchanged.

**Expected edits:** `user-metrics-publisher/user-metrics-publisher.gradle.kts`; `user-metrics-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/metrics/UserMetricPublisherDependencies.kt`; `user-metrics-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/metrics/ui/UserMetricPublisherApp.kt`; new DI files under `user-metrics-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/metrics/`; `user-metrics-publisher/src/commonTest/kotlin/com/github/karlsabo/devlake/metrics/ui/UserMetricPublisherDependenciesTest.kt`; possibly app-local provider interfaces for `GitHubApi`, `ProjectManagementApi`, `UsersConfig`, and `UserMetricsBuilder`.

**Scope:** Replace the manual dependency bundle for the preview path with a generated component, but keep the publish path as a separate story. Preserve the existing config-loading and concurrency behavior in `loadMetrics(...)`.

**Notes:** `LinearRestApi` and `GitHubRestApi` are config-backed, so prefer app-local `@Provides` contributions over broad constructor rewrites in `utilities`. If Story 1 introduces a reusable inject convention plugin, this story should only apply it and add app-specific graph files.

### 3. Move `user-metrics-publisher` publish delivery onto the generated graph

**Acceptance test:** Given `UserMetricPublisherApp` has loaded metrics, when Publish is clicked, then Slack delivery goes through a publisher bound in the generated graph and the existing success/failure button behavior is preserved.

**Expected edits:** `user-metrics-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/metrics/UserMetricPublisherDependencies.kt`; `user-metrics-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/metrics/ui/UserMetricPublisherApp.kt`; new publisher adapter files under `user-metrics-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/metrics/`; `user-metrics-publisher/src/commonTest/kotlin/com/github/karlsabo/devlake/metrics/ui/UserMetricPublisherDependenciesTest.kt`; possibly `user-metrics-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/metrics/service/ZapierMetricService.kt` if a thin adapter is cleaner than binding the object directly.

**Scope:** Finish the `user-metrics-publisher` migration by moving the outbound publisher into the generated graph and deleting the remaining production use of the manual message-publisher scaffolding.

**Notes:** Keep the payload shape and retry/failure semantics unchanged. If `ZapierMetricService` remains a static/object API, introduce an injected adapter rather than forcing static calls into the component.

### 4. Move `summary-publisher` preview loading onto a generated component

**Acceptance test:** Given `SummaryPublisherApp` starts with a valid config, when summary data is loaded, then the summary preview is built from collaborators resolved by a generated component instead of `defaultSummaryPublisherDependencyProvider`, and the preview text is unchanged.

**Expected edits:** `summary-publisher/summary-publisher.gradle.kts`; `summary-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/tools/SummaryPublisherDependencies.kt`; `summary-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/tools/SummaryPublisherState.kt`; `summary-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/tools/ui/SummaryPublisherApp.kt`; new DI files under `summary-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/tools/`; `summary-publisher/src/commonTest/kotlin/com/github/karlsabo/devlake/tools/ui/SummaryPublisherDependenciesTest.kt`.

**Scope:** Replace the manual summary preview dependency provider with a generated component and preserve the existing summary formatting path. Do not mix the publish-path migration into this story.

**Notes:** This story must reconcile with the current uncommitted worktree files in `summary-publisher`. Either absorb that work into this PR or land it before starting; do not plan against the older pre-DI version of `SummaryPublisherApp.kt`.

### 5. Move `summary-publisher` publish delivery onto the generated graph

**Acceptance test:** Given `SummaryPublisherApp` has loaded a summary, when Publish is clicked, then Zapier delivery goes through a publisher bound in the generated graph instead of calling `ZapierService` directly from the composable.

**Expected edits:** `summary-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/tools/ui/SummaryPublisherApp.kt`; new publisher adapter files under `summary-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/tools/`; `summary-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/tools/service/ZapierService.kt` only if a wrapper is cleaner than binding the object directly; `summary-publisher/src/commonTest/kotlin/com/github/karlsabo/devlake/tools/ui/SummaryPublisherDependenciesTest.kt` or a new focused publish-path test.

**Scope:** Finish the `summary-publisher` migration by injecting the publish path and deleting the last direct production call to `ZapierService` from the UI layer.

**Notes:** Keep the existing `ZapierProjectSummary` payload unchanged. This should be reviewable as a behavior-preserving DI change, not a summary-formatting change.

## Ordering

1. Story 1 first. It establishes the Gradle/KSP pattern, the `expect`/generated-create pattern, and the first merged component.
2. Story 2 after Story 1. It reuses the same build convention and KMP component pattern in a second app.
3. Story 3 after Story 2.
4. Story 4 after Story 1. If the current local `summary-publisher` DI files are intended to land separately, land or rebase them before starting this story.
5. Story 5 after Story 4.

## Out of Scope

- Rewriting every config-backed utility client in `utilities` to use constructor injection if app-local providers are simpler.
- Introducing a shared repo-wide DI module before there is repeated pressure for one.
- Changing summary formatting, metrics formatting, or desktop UI layout as part of the migration.
- Replacing file-based config loading with a different config system.

## Trade-offs

- `kotlin-inject` + Anvil is a better fit for this repo than a runtime DI framework because the project is KMP and already leans on constructor injection, but it adds KSP build complexity that the current build does not have.
- Using app-local scopes keeps the graphs small and reversible, but it means some provider patterns may repeat across `eng-hub`, `user-metrics-publisher`, and `summary-publisher`.
- Keeping config loading at the edge minimizes scope and preserves the current startup behavior, but it means components will still accept some already-loaded values instead of owning all bootstrap logic.
