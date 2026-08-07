# CI build performance plan

**Goal:** Reduce the successful macOS GitHub Actions Build job below 10 minutes without weakening the full cross-platform build.

## Context

- GitHub Actions run [31021450204](https://github.com/karlsabo/dev-lake-utils/actions/runs/31021450204) took 10m32s on macOS, 4m58s on Windows, and 2m50s on Linux.
- Every job must continue to run `./gradlew clean build --no-build-cache` (`.github/workflows/build.yml`).
- The build already provides opt-in task timing through `buildSrc/src/main/kotlin/devlake/gradle/timing/TaskTimingPlugin.kt`. With `-PtaskTiming=true`, it writes machine-readable and duration-sorted reports under `build/reports/`.
- `:utilities:build` produces Kotlin/Native tests and debug/release framework, shared-library, and static-library variants declared in `utilities/utilities.gradle.kts`. Those outputs remain required.

## Story

### Identify the first macOS build hotspot

**Status:** Complete. Measured in [Build run 31217431025](https://github.com/karlsabo/dev-lake-utils/actions/runs/31217431025); results are recorded in `plans/ci-build-performance-results.md`.

**Acceptance criteria:** Given a full macOS CI build with task timing enabled, when its retained timing report is parsed, then `plans/ci-build-performance-results.md` names the longest material task and records enough timing evidence to choose one optimization experiment.

**Expected edits:**

- `.github/workflows/build.yml`
- `plans/ci-build-performance-results.md`

**Scope:**

- In: add `-PtaskTiming=true` to the existing clean, uncached build; upload `build/reports/task-timing.json` and `build/reports/task-timing.txt` even when the build fails; parse successful macOS reports; record the observed hotspot.
- Out: custom baseline applications, GitHub API integration, runner-resource capture, generated Markdown, cache changes, narrower task selection, or optimization before timing evidence exists.

**Notes:**

- Start with one successful macOS report. Additional runs are useful only if hosted-runner variance or task overlap makes the first result inconclusive.
- Task duration is enough to select the first investigation. If parallel tasks make ownership of wall-clock time ambiguous, inspect their timestamps before adding more instrumentation.
- The report parser may be a disposable local command. Check in a script only if repeated analysis demonstrates a continuing need.

## Follow-up rule

Create one follow-up story for the measured hotspot. Its acceptance criterion must name the task, the proposed reversible change, and the output or verification behavior that remains unchanged. Measure overall macOS job time before retaining the optimization so work is not merely shifted elsewhere.

Stop when the macOS job is reliably below 10 minutes. Do not build permanent performance infrastructure unless a concrete recurring use justifies it.
