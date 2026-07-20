# GitHub Actions Gradle Test Diagnostics

**Goal:** Make failed Gradle tests diagnosable from GitHub Actions while retaining the existing, verified Gradle wrapper distribution caching.

## Context

- `.github/workflows/build.yml` runs `./gradlew clean build` in separate Linux, macOS, and Windows jobs, but it has no step that publishes Gradle test results after `Build` fails.
- Gradle produces machine-readable JUnit XML under `*/build/test-results/**/TEST-*.xml` and browsable HTML under `*/build/reports/tests/**`. For example, the current checkout contains `eng-hub/build/test-results/jvmTest/` and `eng-hub/build/reports/tests/allTests/`.
- The `file:///Users/runner/...` URL printed by Gradle points into an ephemeral runner. It cannot be opened after the job ends unless CI uploads the report.
- `.github/workflows/build.yml` already uses `gradle/actions/setup-gradle@v4` before invoking the wrapper in every build job.
- `gradle/wrapper/gradle-wrapper.properties` pins Gradle 9.4.1 and stores wrapper distributions below `GRADLE_USER_HOME/wrapper/dists`.
- `setup-gradle` enables Gradle User Home caching by default, including a dedicated wrapper-distribution cache. It does not guarantee a cache hit; a cold or unavailable cache still requires `./gradlew` to download the pinned distribution.
- Existing run [28900549449](https://github.com/karlsabo/dev-lake-utils/actions/runs/28900549449) confirms caching works on all three operating systems: each setup step reported a `gradle-wrapper-zips` cache hit, restored `gradle-9.4.1-all`, and its build step did not download Gradle.
- Runs [29781681678](https://github.com/karlsabo/dev-lake-utils/actions/runs/29781681678) and [29782046231](https://github.com/karlsabo/dev-lake-utils/actions/runs/29782046231) demonstrate cold and warm behavior. The first Linux job downloaded and then saved the Gradle 9.4.1 wrapper distribution; the next Linux job restored it and did not download it. The macOS and Windows jobs in the second run began before their corresponding post-job cache writes in the first run completed, so their misses are consistent with overlapping workflow runs rather than caching being disabled.

## Decisions

1. Publish a concise failed-test summary in each operating system job's GitHub Actions job summary.
2. Upload the complete Gradle HTML and JUnit XML reports so developers can inspect details not shown in the summary.
3. Run reporting steps even when `./gradlew clean build` fails.
4. Give artifacts operating-system-specific names so the three jobs cannot collide.
5. Keep `./gradlew` as the build entry point so the repository's pinned wrapper remains authoritative.
6. Keep `gradle/actions/setup-gradle@v4`; do not add a second Gradle cache or an explicit `actions/cache` step.
7. Do not add meaningless configuration such as `cache-disabled: false`, because caching is already enabled by default and run evidence confirms it works.
8. Accept a Gradle distribution download after cache eviction, a wrapper version change, or another genuine cache miss. The requirement is to avoid downloading on a warm cache, not to make cold runners network-free.
9. Test summaries and complete report artifacts are separate user-visible outcomes, so they are separate stories and PRs.

## Stories

### 1. Show failed Gradle tests in each job summary

**Acceptance criteria:** Given a test named `exampleFailure` fails during one of the Linux, macOS, or Windows Gradle build jobs, when a developer opens that job's GitHub Actions summary, then the summary identifies the failed test suite and test name without requiring access to the runner filesystem.

**Expected edits:**

- `.github/workflows/build.yml`

**Scope:**

- In: add `test-summary/action@v2` after each `Build` step; read `**/build/test-results/**/TEST-*.xml`; show failed tests; use `if: always()` so the summary is produced after a failed build.
- In: produce one summary within each existing OS job so results are not incorrectly combined across runners.
- Out: uploading HTML reports, PR comments, check-run annotations, changing Gradle test logging, and changing application or test code.

**Notes:**

- Gradle's JUnit XML is already available under module-specific `build/test-results` directories; no Gradle build-script change is expected.
- Writing to `GITHUB_STEP_SUMMARY` does not require increasing the workflow's current `contents: read` permission.
- Validate with a temporary failing test commit on a draft PR, retain the failed run URL as evidence, and revert the intentional failure before merging. Confirm all three jobs still reach the summary step after `Build` fails.
- If the third-party action cannot parse the repository's Gradle XML, replace it with an equivalent JUnit-summary action rather than building a custom parser in the workflow.

### 2. Preserve complete Gradle test reports from failed jobs

**Acceptance criteria:** Given a Gradle test fails in a Linux, macOS, or Windows build job, when a developer downloads that job's test-report artifact, then the archive contains the generated HTML report and JUnit XML for the failed test.

**Expected edits:**

- `.github/workflows/build.yml`

**Scope:**

- In: add `actions/upload-artifact@v4` after reporting in each build job; upload `**/build/reports/tests/**` and `**/build/test-results/**`; use `if: failure()`; use an OS-specific artifact name such as `gradle-test-reports-linux`; tolerate no matching files when a non-test build failure occurs.
- In: preserve directory structure so a downloaded HTML report can be opened locally from its `index.html` with supporting CSS, JavaScript, and linked pages intact.
- Out: publishing reports from `.github/workflows/release.yml`, retaining unrelated build outputs, changing GitHub artifact retention policy, and hosting reports as a website.

**Notes:**

- `.github/workflows/build.yml` has three independent jobs, so each job must upload its own artifact. An artifact from one runner cannot collect files from another runner without adding a separate aggregation job, which is unnecessary here.
- Upload both HTML and XML: HTML is convenient for investigation, while XML remains portable for tooling and verifies the job summary's source data.
- Validate using the same temporary failing-test workflow run as Story 1. Download each produced artifact and open the relevant `*/build/reports/tests/**/index.html` locally.

## Gradle Cache Validation — No Implementation Story

No Gradle caching PR is justified. The requested behavior already exists and run 28900549449 demonstrates a warm-cache restore on Linux, macOS, and Windows without a wrapper download.

For operational verification after these workflow changes:

1. Let one build workflow finish completely, including each `Post Set up Gradle` step.
2. Start a later build without changing `gradle/wrapper/gradle-wrapper.properties`.
3. In each `Set up Gradle` log, confirm a `gradle-wrapper-zips` cache hit and restoration into `GRADLE_USER_HOME/wrapper/dists/gradle-9.4.1-all/...`.
4. In each `Build` log, confirm there is no `Downloading https://services.gradle.org/distributions/gradle-9.4.1-all.zip` line.
5. Treat a miss as actionable only if it repeats after a completed cache-writing run; an isolated cold miss is expected cache behavior.

Adding another cache layer would duplicate knowledge already owned by `setup-gradle`, increase cache-key and invalidation complexity, and risk conflicting writes without eliminating legitimate cold downloads.
