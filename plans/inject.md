# Dependency Injection Review

## Bottom line

The repository is using dependency injection in a reasonable way, but not in the most idiomatic and maintainable way yet.

My short take:

- The current approach is better than ad hoc construction scattered through UI code.
- The composition roots are clear and testable.
- The repository is not getting enough value from `kotlin-inject` + Anvil to justify all of the extra graph and build plumbing yet.
- The biggest maintainability issue is not "bad DI", it is "more DI machinery than the current app size really needs".

If the apps stay roughly this size, I would simplify the wiring. If you expect the graphs to grow, I would keep the framework but tighten the boundaries and remove some of the wrapper abstractions.

## What is good

### 1. DI is mostly kept at the app composition roots

Each app has a top-level component and the rest of the app consumes constructed dependencies rather than reaching out to build concrete services itself:

- [eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHubComponent.kt](/Users/karl.sabo/git/dev-lake-utils/eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHubComponent.kt:20)
- [summary-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/tools/SummaryPublisherDependencies.kt](/Users/karl.sabo/git/dev-lake-utils/summary-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/tools/SummaryPublisherDependencies.kt:52)
- [user-metrics-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/metrics/UserMetricPublisherComponent.kt](/Users/karl.sabo/git/dev-lake-utils/user-metrics-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/metrics/UserMetricPublisherComponent.kt:20)

That is the right general direction. The UI entry points load config and then hand off to a single loader/factory instead of constructing every service inline:

- [eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHub.kt](/Users/karl.sabo/git/dev-lake-utils/eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHub.kt:32)
- [summary-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/tools/ui/SummaryPublisherApp.kt](/Users/karl.sabo/git/dev-lake-utils/summary-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/tools/ui/SummaryPublisherApp.kt:54)
- [user-metrics-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/metrics/ui/UserMetricPublisherApp.kt](/Users/karl.sabo/git/dev-lake-utils/user-metrics-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/metrics/ui/UserMetricPublisherApp.kt:36)

### 2. Constructor injection is used for real consumers

This is the cleanest part of the current setup. `EngHubViewModel` and `ZapierSummaryPublisher` take dependencies via constructors instead of reaching into globals or static factories:

- [eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModel.kt](/Users/karl.sabo/git/dev-lake-utils/eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModel.kt:46)
- [summary-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/tools/ZapierSummaryPublisher.kt](/Users/karl.sabo/git/dev-lake-utils/summary-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/tools/ZapierSummaryPublisher.kt:7)

That is idiomatic and maintainable.

### 3. Tests are written against seams, not against the generated graph

This is important. The tests inject fake component factories or fake dependency loaders rather than depending on the generated DI container:

- [eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/EngHubDependenciesTest.kt](/Users/karl.sabo/git/dev-lake-utils/eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/EngHubDependenciesTest.kt:25)
- [summary-publisher/src/commonTest/kotlin/com/github/karlsabo/devlake/tools/ui/SummaryPublisherDependenciesTest.kt](/Users/karl.sabo/git/dev-lake-utils/summary-publisher/src/commonTest/kotlin/com/github/karlsabo/devlake/tools/ui/SummaryPublisherDependenciesTest.kt:30)
- [user-metrics-publisher/src/commonTest/kotlin/com/github/karlsabo/devlake/metrics/ui/UserMetricPublisherDependenciesTest.kt](/Users/karl.sabo/git/dev-lake-utils/user-metrics-publisher/src/commonTest/kotlin/com/github/karlsabo/devlake/metrics/ui/UserMetricPublisherDependenciesTest.kt:39)

This is a strong sign that your code is still testable without the framework becoming the center of the design.

## Where it is less idiomatic or less maintainable

### 1. The framework is carrying a very small graph

The build convention adds KSP, `kotlin-inject`, and Anvil support across modules:

- [buildSrc/src/main/kotlin/devlake.kotlin-inject-conventions.gradle.kts](/Users/karl.sabo/git/dev-lake-utils/buildSrc/src/main/kotlin/devlake.kotlin-inject-conventions.gradle.kts:1)

But most providers are simple constructor calls:

- `GitHubApi = GitHubRestApi(config)`
- `ProjectManagementApi = LinearRestApi(config)`
- `PagerDutyApi = PagerDutyRestApi(config)`
- `DesktopLauncher = DesktopLauncherService()`
- `GitWorktreeApi = GitWorktreeService()`

Examples:

- [eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHubComponent.kt](/Users/karl.sabo/git/dev-lake-utils/eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHubComponent.kt:23)
- [summary-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/tools/SummaryPublisherDependencies.kt](/Users/karl.sabo/git/dev-lake-utils/summary-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/tools/SummaryPublisherDependencies.kt:55)
- [user-metrics-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/metrics/UserMetricPublisherComponent.kt](/Users/karl.sabo/git/dev-lake-utils/user-metrics-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/metrics/UserMetricPublisherComponent.kt:23)

That is valid DI usage, but it is the point where I start asking whether a framework is buying enough to offset compile-time complexity, generated code, conventions, and reader overhead.

For a small set of desktop tools, explicit factory functions would be nearly as clean and likely easier to reason about.

### 2. Config loading still happens outside the DI graph

The graph is not really responsible for bootstrapping the app. Each app manually loads config files first, then calls a component factory:

- [eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHubDependencies.kt](/Users/karl.sabo/git/dev-lake-utils/eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHubDependencies.kt:10)
- [summary-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/tools/SummaryPublisherDependencies.kt](/Users/karl.sabo/git/dev-lake-utils/summary-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/tools/SummaryPublisherDependencies.kt:142)
- [user-metrics-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/metrics/UserMetricPublisherDependencies.kt](/Users/karl.sabo/git/dev-lake-utils/user-metrics-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/metrics/UserMetricPublisherDependencies.kt:62)

That is not wrong, but it means the framework is only handling the last step of assembly. In practice, you still have a fairly manual bootstrap layer in every app.

So today the system is in an awkward middle state:

- not fully manual wiring
- not deeply framework-driven composition either

That middle state is usually the least satisfying long-term.

### 3. Some abstractions exist mainly to fit the graph, not the domain

The clearest examples are the `fun interface` wrappers:

- `SummaryBuilder`
- `SummaryMessagePublisher`
- `UserMetricsBuilder`
- `UserMetricMessagePublisher`

References:

- [summary-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/tools/SummaryPublisherDependencies.kt](/Users/karl.sabo/git/dev-lake-utils/summary-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/tools/SummaryPublisherDependencies.kt:42)
- [user-metrics-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/metrics/UserMetricPublisherDependencies.kt](/Users/karl.sabo/git/dev-lake-utils/user-metrics-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/metrics/UserMetricPublisherDependencies.kt:42)

Some of these are useful because they create a test seam. But some are also just closures around one function call or one config value:

- `provideMetricsBuilder()` wraps `MetricsService.createUserMetrics(...)`
- `provideMessagePublisher(config)` wraps `ZapierMetricService.sendMessage(...)`

Reference:

- [user-metrics-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/metrics/UserMetricPublisherComponent.kt](/Users/karl.sabo/git/dev-lake-utils/user-metrics-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/metrics/UserMetricPublisherComponent.kt:31)

This is the kind of abstraction that often makes DI graphs look cleaner than the runtime model actually is. If a thing is really just a pure function or a thin infrastructure adapter, I would either:

- inject the concrete service directly, or
- make the abstraction a real domain port with stable behavior and multiple meaningful implementations

Right now a few of these types feel like "DI-friendly aliases" rather than domain concepts.

### 4. Some dependency bags are awkward and leaky

`UserMetricPublisherDependencies` contains `previewDependencies` and then re-exposes its fields via forwarding getters:

- [user-metrics-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/metrics/UserMetricPublisherDependencies.kt](/Users/karl.sabo/git/dev-lake-utils/user-metrics-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/metrics/UserMetricPublisherDependencies.kt:18)

That is a smell. It suggests the graph shape is being driven by UI convenience or preview reuse rather than by clean application boundaries.

I would prefer one of these:

- a single flat dependency object for the screen/app use case
- explicit constructor parameters for the coordinator/service that needs them
- separate preview wiring outside the main dependency type

### 5. There is repetitive bootstrap code across apps

All three apps repeat the same pattern:

- load config
- build dependencies/component
- store dependencies in mutable UI state
- use dependency bag later

References:

- [eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHub.kt](/Users/karl.sabo/git/dev-lake-utils/eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHub.kt:32)
- [summary-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/tools/ui/SummaryPublisherApp.kt](/Users/karl.sabo/git/dev-lake-utils/summary-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/tools/ui/SummaryPublisherApp.kt:101)
- [user-metrics-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/metrics/ui/UserMetricPublisherApp.kt](/Users/karl.sabo/git/dev-lake-utils/user-metrics-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/metrics/ui/UserMetricPublisherApp.kt:77)

This repetition is a sign that your maintainability issue is now more about application bootstrap consistency than about missing DI.

### 6. The repo docs are already drifting from reality

The root README still says:

- `Add dependency injection`

Reference:

- [README.md](/Users/karl.sabo/git/dev-lake-utils/README.md:10)

That is small, but it matters. DI conventions add cognitive load. If the docs lag immediately after adoption, that is usually evidence that the chosen shape is not yet settled enough.

## Is it idiomatic?

### Idiomatic for `kotlin-inject` + Anvil

Mostly yes.

What you are doing is recognizable:

- top-level `@MergeComponent`
- constructor injection for consumers
- `@Provides` bindings for infrastructure
- scope object per app

That part is fine.

### Idiomatic for this repository

Not quite.

For a repo with three relatively small app roots and straightforward infrastructure clients, the most maintainable pattern is usually one of these:

1. Plain composition-root factory functions, no DI framework.
2. A DI framework, but only after the graphs are large enough that manual wiring is genuinely painful.

Right now you are in between those two states.

## What I would change

### Option A: keep DI, but make it earn its keep

This is the path I would take if you expect more services, more implementations, more platform-specific bindings, or more module-level contribution over time.

Changes I would make:

1. Keep DI only at the outermost composition roots.
2. Prefer constructor-injected classes over `fun interface` wrappers when the dependency is really a service.
3. Flatten dependency bags like `UserMetricPublisherDependencies` unless the nesting expresses a real boundary.
4. Introduce a shared app bootstrap pattern so all three tools load config and build components the same way.
5. Keep concrete client construction in one place only and avoid adding more direct instantiation elsewhere.

In other words: less clever graph shaping, more obvious application services.

### Option B: remove the DI framework and use explicit factories

This is the path I would take if these tools are expected to stay small and mostly single-purpose.

You already have explicit loaders such as:

- [eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHubDependencies.kt](/Users/karl.sabo/git/dev-lake-utils/eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHubDependencies.kt:10)
- [summary-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/tools/SummaryPublisherDependencies.kt](/Users/karl.sabo/git/dev-lake-utils/summary-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/tools/SummaryPublisherDependencies.kt:142)
- [user-metrics-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/metrics/UserMetricPublisherDependencies.kt](/Users/karl.sabo/git/dev-lake-utils/user-metrics-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/metrics/UserMetricPublisherDependencies.kt:62)

Those are already halfway to manual composition roots. Replacing generated components with explicit constructors/factory functions would simplify the mental model a lot and probably reduce build overhead too.

## Recommendation

I would not call the current setup "wrong". It is competent and testable.

I also would not call it the most maintainable shape for this repo today.

My recommendation:

- If you expect these apps to grow substantially, keep `kotlin-inject` + Anvil, but simplify the graph surface area and remove wrapper abstractions that do not represent real domain boundaries.
- If you do not expect large graph growth, remove the DI framework and keep explicit composition-root factory functions.

If forced to choose based on the repository as it exists today, I would lean toward simpler explicit factories over framework DI. The current codebase is small enough that manual wiring would likely be clearer than the generated graph, while preserving the same good test seams you already have.
