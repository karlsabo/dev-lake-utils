# CI build performance plan

**Goal:** Reduce the successful GitHub Actions Build workflow wall-clock time to below 10 minutes on macOS without weakening the full cross-platform build.

## Context

- GitHub Actions run [31021450204](https://github.com/karlsabo/dev-lake-utils/actions/runs/31021450204) completed successfully in 10m32s on macOS, 4m58s on Windows, and 2m50s on Linux. The macOS `Build` step took 8m47s; Gradle reported 8m44s.
- Linux, macOS, and Windows must remain required for every pull request, and each must continue running `clean build --no-build-cache` (`.github/workflows/build.yml`). Task selection that turns the macOS job into a partial verification build is therefore out of scope.
- The macOS log shows that ordinary JVM tests are not the primary bottleneck. `:utilities:build` compiles Kotlin/Native, links the native test executable, and assembles debug and release framework, shared-library, and static-library variants.
- Those binary types are declared in `utilities/utilities.gradle.kts`. They are not consumed yet, but they remain part of the required full build.
- Native linking begins around 2m54s into the Gradle build and ends around 8m36s. The longest apparent interval is `linkDebugTestMacosArm64`, while production binary links also occupy several minutes of the critical path.
- Gradle parallel execution and configuration cache are already enabled (`gradle.properties`). Build-output caching remains disabled by the required command-line flag.
- GitHub Actions setup and post-setup account for roughly 1m35s, but changes must not merely hide build work or replace `--no-build-cache` with another cache mechanism.

## Constraints and decisions

1. MacOS framework, shared-library, and static-library outputs do not have consumers yet.
2. Every pull request must continue to perform the full build, including all declared native binary variants.
3. The target is a macOS job duration below 10 minutes.
4. Linux, macOS, and Windows remain required on every pull request.
5. Both `clean` and `--no-build-cache` must remain.

## Fitness functions

Use the median of at least three successful pull-request or equivalent branch runs to reduce hosted-runner noise.

- Primary: macOS job median duration is below 10 minutes.
- Guardrail: the workflow still invokes `./gradlew clean build --no-build-cache` on all three operating systems.
- Guardrail: macOS still compiles and tests Kotlin/Native code and assembles every declared debug and release binary.
- Guardrail: Linux and Windows job medians do not regress by more than 10%.
- Guardrail: no tests, static analysis, migration verification, or binary formats are skipped.

## Acceptance tests

1. **Given** any local or CI Gradle build, **when** task timing is enabled, **then** the build produces a machine-readable and human-readable report containing every executed task's duration and outcome without changing task behavior.
2. **Given** an unchanged full macOS build, **when** CI runs it three times with task timing enabled, **then** the reports identify the critical path and attribute at least 90% of the Gradle build duration to named tasks or configuration work.
3. **Given** a task identified as a material critical-path hotspot, **when** that task is optimized, **then** its median duration improves by at least 20% without changing its outputs or verification behavior.
4. **Given** the measured macOS native critical path, **when** Gradle worker and Kotlin/Native execution settings are tuned, **then** all native binaries and tests are produced and the median macOS job duration across three successful runs is below 10 minutes.
5. **Given** measured slow test suites, **when** deterministic test hotspots are optimized, **then** all tests retain their assertions and the affected test phase is at least 20% faster across three clean, uncached runs.

## Stories

### 1. Add reusable Gradle task timing

**Acceptance criteria:** Given any local or CI Gradle build, when task timing is enabled, then the build produces a machine-readable and human-readable report containing every executed task's duration and outcome without changing task behavior.

**Expected edits:** a convention plugin under `buildSrc/src/main/kotlin/`; `dev-lake-utils.gradle.kts` or `settings.gradle.kts`; automated verification under `buildSrc/src/test`; optionally `gradle.properties` if timing is controlled by a documented property.

**Scope:**

- In: record task path, start time, end time, elapsed duration, outcome, and whether work was skipped or up-to-date; sort a summary by duration; write structured output for later comparison; make instrumentation configuration-cache compatible; document how to enable it.
- Out: external build-scan services, cache changes, task-graph mutations, or making every local build noisy by default.

**Notes:** Implement timing once in Gradle rather than parsing timestamped GitHub logs. Prefer a shared build service and supported Gradle operation/task APIs over deprecated listeners. Keep the report under `build/reports/` and ensure instrumentation has negligible overhead. Add a functional test proving that a known task appears with a non-negative duration and correct outcome.

### 2. Establish the clean, uncached macOS baseline

**Acceptance criteria:** Given an unchanged full macOS build, when CI runs it three times with task timing enabled, then the reports identify the critical path and attribute at least 90% of the Gradle build duration to named tasks or configuration work.

**Expected edits:** `.github/workflows/build.yml`; a version-controlled comparison script under `scripts/`; optionally `plans/ci-build-performance-results.md` for baseline results.

**Scope:**

- In: enable Story 1's timing in CI; retain timing reports as artifacts; collect configuration time, test-suite time, runner CPU count, and memory; preserve `./gradlew clean build --no-build-cache`; publish the timing report even on failure.
- Out: enabling caches, changing task selection, or optimizing before the baseline exists.

**Notes:** Record setup and post-setup separately because they are outside Gradle. Use three successful runs and report medians rather than selecting the fastest run. The existing GitHub log is enough to suspect native linking, but Story 1's data must determine subsequent optimization priorities.

### 3. Optimize measured critical-path Gradle tasks

**Acceptance criteria:** Given a task identified as a material critical-path hotspot, when that task is optimized, then its median duration improves by at least 20% without changing its outputs or verification behavior.

**Expected edits:** the owning module's `*.gradle.kts` file, relevant convention plugin under `buildSrc/src/main/kotlin/`, and production or test sources responsible for the task's inputs; `plans/ci-build-performance-results.md` for before/after evidence.

**Scope:**

- In: rank tasks by critical-path contribution; select one hotspot per PR; inspect unnecessary inputs, repeated generation, avoidable process startup, compiler configuration, and serialization caused by task dependencies; compare three clean, uncached runs before and after.
- Out: bundling unrelated hotspot changes, skipping task work, weakening outputs, or accepting an optimization that merely shifts time into another task.

**Notes:** Each hotspot should become its own follow-up ticket and PR with a task-specific acceptance test. Optimize wall-clock critical-path contribution, not merely aggregate task duration. Stop when the macOS job is reliably below 10 minutes rather than optimizing every slow task.

### 4. Tune native build concurrency without reducing outputs

**Acceptance criteria:** Given the measured macOS native critical path, when Gradle worker and Kotlin/Native execution settings are tuned, then all native binaries and tests are produced and the median macOS job duration across three successful runs is below 10 minutes.

**Expected edits:** `gradle.properties`; `.github/workflows/build.yml` only if runner-specific worker settings need an explicit environment variable; `utilities/utilities.gradle.kts` only where task configuration is required.

**Scope:**

- In: benchmark bounded Gradle worker counts against the macOS runner's CPU and memory; reduce oversubscription among concurrent native links; verify Kotlin daemon/native compiler memory sizing; remove duplicated native compilation only if profiling proves the same inputs are compiled more than once due to build configuration rather than because distinct required outputs need them.
- Out: removing binaries, changing debug/release semantics, skipping native tests, enabling the build cache, removing `clean`, or replacing `build` with narrower tasks.

**Notes:** More parallelism can make native linking slower through CPU and memory contention. Benchmark at least the current default and two bounded worker counts; do not commit a conventional value such as `--max-workers=2` without CI evidence. Prefer settings in `gradle.properties` when they are valid across environments; isolate macOS-only tuning when Linux or Windows regress. Verify output existence for all framework/shared/static debug and release variants after every candidate change.

### 5. Optimize only measured test hotspots

**Acceptance criteria:** Given measured slow test suites, when deterministic test hotspots are optimized, then all tests retain their assertions and the affected test phase is at least 20% faster across three clean, uncached runs.

**Expected edits:** measured files under `utilities/src/commonTest`, `utilities/src/jvmTest`, `eng-hub/src/commonTest`, or other identified test source sets; possibly the owning module's `*.gradle.kts` file.

**Scope:**

- In: replace real delays with virtual time, eliminate polling where synchronization is available, reduce repeated process/filesystem setup, reuse immutable fixtures safely, and configure JVM test forking only where measurements show a gain.
- Out: deleting scenarios, shortening timeouts without removing the underlying wait, sharing mutable state, making tests order-dependent, or broad speculative rewrites.

**Notes:** The current evidence says native linking—not test execution—is the dominant macOS cost. This story is conditional: if Story 1 shows no test phase large enough to save meaningful wall-clock time, close it without code changes. JVM test forking will not improve Kotlin/Native executable linking.

## Sequence and trade-offs

1. Add reusable Gradle timing, then establish a three-run baseline. A 32-second reduction meets the stated threshold, but hosted-runner variance means the implementation should target at least one minute of median headroom.
2. Create one follow-up story per measured critical-path hotspot and optimize the highest-impact task first.
3. Tune native concurrency if timing confirms native links dominate while overlapping.
4. Optimize tests only if the timing report identifies a meaningful test hotspot.

Keeping all currently unconsumed native variants in every pull-request build preserves maximal packaging coverage, but it spends several macOS runner-minutes per change. This plan accepts that cost as a constraint and optimizes execution rather than narrowing verification. If that decision changes later, removing or relocating unconsumed binary assembly would provide a much larger and simpler reduction.
