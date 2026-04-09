# Add Dependency Injection

**Goal**: Introduce explicit dependency injection at the app boundaries so each app can be started and tested with supplied collaborators instead of constructing concrete services inside UI code or global singleton objects.

**Context**:

- The repo already has partial constructor injection in `utilities`, for example `GitWorktreeService` accepts a `GitCommandApi`, and `EngHubViewModel` already takes its collaborators as constructor parameters.
- `eng-hub` is still creating concrete implementations directly inside the composable startup path in `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHub.kt`.
- `user-metrics-publisher` currently mixes UI, config loading, concrete API construction, and singleton service usage in `user-metrics-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/metrics/ui/UserMetricPublisherApp.kt`.
- `summary-publisher` currently creates `LinearRestApi`, `GitHubRestApi`, `PagerDutyRestApi`, `TextSummarizerOpenAi`, and uses `ZapierService` directly from `summary-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/tools/ui/SummaryPublisherApp.kt`.
- There is no DI framework in the build today. The lowest-risk path is manual DI with explicit composition roots, interfaces, and default factories. A framework can be added later if the manual pattern becomes repetitive.

## Assumptions

- “Add dependency injection” means making collaborator wiring explicit and replaceable, not introducing Koin, Dagger, or another framework immediately.
- Runtime behavior should stay the same. This work is primarily about moving construction to composition roots and improving testability.
- Config file loading remains at the application edge. The DI work starts after config has been read.

## Acceptance Tests

1. Given `EngHub` is started with a test dependency provider, when startup runs, then the resulting `EngHubViewModel` is built only from the supplied collaborators and `EngHub.kt` no longer constructs concrete GitHub, worktree, or desktop-launcher services inline.
2. Given `UserMetricPublisherApp` is started with injected APIs and a fake metrics builder, when metrics are loaded, then the preview is generated from the injected collaborators without constructing `LinearRestApi`, `GitHubRestApi`, or using the `MetricsService` singleton directly from the UI layer.
3. Given `UserMetricPublisherApp` is started with an injected message publisher, when Publish is clicked, then Slack delivery goes through the injected publisher instead of calling `ZapierMetricService` directly.
4. Given `SummaryPublisherApp` is started with an injected summary builder, when summary data is loaded, then summary generation uses the injected collaborator instead of constructing `LinearRestApi`, `GitHubRestApi`, `PagerDutyRestApi`, and `TextSummarizerOpenAi` inside the UI layer.
5. Given `SummaryPublisherApp` is started with an injected summary publisher, when Publish is clicked, then Zapier delivery goes through the injected publisher instead of calling `ZapierService` directly.

## Stories

### 1. Establish the DI pattern in `eng-hub`

**Acceptance test:** Given `EngHub` is started with a test dependency provider, when startup runs, then the resulting `EngHubViewModel` is built only from the supplied collaborators and `EngHub.kt` no longer constructs concrete GitHub, worktree, or desktop-launcher services inline.

**Scope:** Add an `EngHubDependencies` type or small composition-root builder that owns construction of `GitHubApi`, `GitHubNotificationService`, `GitWorktreeApi`, and `DesktopLauncher`. Wire `EngHub` to accept default dependencies for production and override dependencies for tests. Keep the current runtime behavior unchanged.

**Notes:** This is the tracer bullet. Use it to settle naming and shape for manual DI in the repo. Do not introduce a framework in this story.

### 2. Inject the metrics-loading path in `user-metrics-publisher`

**Acceptance test:** Given `UserMetricPublisherApp` is started with injected APIs and a fake metrics builder, when metrics are loaded, then the preview is generated from the injected collaborators without constructing `LinearRestApi`, `GitHubRestApi`, or using the `MetricsService` singleton directly from the UI layer.

**Scope:** Introduce a `UserMetricPublisherDependencies` type and move metrics-building behavior behind an injected interface or function object. Refactor `loadConfiguration` and `loadMetrics` so the UI layer receives collaborators from the composition root instead of creating them itself.

**Notes:** Depend on story 1 for the pattern only. Keep config-file parsing where it is; use the parsed config to build the default dependencies.

### 3. Inject the publish path in `user-metrics-publisher`

**Acceptance test:** Given `UserMetricPublisherApp` is started with an injected message publisher, when Publish is clicked, then Slack delivery goes through the injected publisher instead of calling `ZapierMetricService` directly.

**Scope:** Replace direct `ZapierMetricService` usage with an injected publisher abstraction. The production implementation can still use the same HTTP behavior and payload shape. Add a focused test around the publish action.

**Notes:** This should stay separate from story 2 so the data-loading refactor and the outbound publishing refactor can be reviewed independently.

### 4. Inject the summary-loading path in `summary-publisher`

**Acceptance test:** Given `SummaryPublisherApp` is started with an injected summary builder, when summary data is loaded, then summary generation uses the injected collaborator instead of constructing `LinearRestApi`, `GitHubRestApi`, `PagerDutyRestApi`, and `TextSummarizerOpenAi` inside the UI layer.

**Scope:** Introduce a `SummaryPublisherDependencies` type and move summary-building behavior behind an injected interface or function object. The UI should stop owning concrete API construction and instead consume a composed dependency bundle.

**Notes:** Follow the same manual DI pattern as stories 1 and 2. Avoid changing the summary formatting rules in this story.

### 5. Inject the publish path in `summary-publisher`

**Acceptance test:** Given `SummaryPublisherApp` is started with an injected summary publisher, when Publish is clicked, then Zapier delivery goes through the injected publisher instead of calling `ZapierService` directly.

**Scope:** Replace direct `ZapierService` usage with an injected publisher abstraction. Preserve the existing `ZapierProjectSummary` payload and success/failure behavior.

**Notes:** Keep this separate from story 4 so summary generation and outbound publishing remain distinct reviewable changes.

## Ordering

1. Story 1 first to establish the manual DI convention in one app.
2. Story 2 next to move `user-metrics-publisher` data loading onto the same pattern.
3. Story 3 after story 2.
4. Story 4 can start after story 1 and run in parallel with stories 2 or 3 if needed.
5. Story 5 after story 4.

## Out of Scope

- Adding a DI framework such as Koin, Dagger, Hilt, Guice, or Kodein.
- Rewriting every existing utility class just to “look injectable” when constructor injection already exists.
- UI redesign, new features, or behavior changes in summary generation and metrics calculation.
- General config-system cleanup beyond what is required to compose dependencies cleanly.

## Trade-offs

- Manual DI is more verbose than a framework, but it matches the repo’s current scale and avoids introducing framework lifecycle complexity into Compose Desktop startup.
- Splitting by app flow keeps PRs small and reversible, but it means the repo will temporarily have mixed patterns until all five stories land.
- Keeping config loading at the edge limits scope now, even though a later cleanup may still want a more unified application bootstrap story.
