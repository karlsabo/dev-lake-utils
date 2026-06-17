# Kotlin 2.3.21 upgrade plan

**Goal**: Upgrade dev-lake-utils to Kotlin 2.3.21 and move Kotlin-sensitive dependencies to versions that resolve, compile, and pass the repository checks together.

**Context**:
- The central version catalog currently pins Kotlin `2.2.21`, KSP `2.2.21-2.0.5`, Ktor `3.1.3`, Compose `1.10.0`, Skiko `0.9.37.3`, kotlin-inject `0.9.0`, kotlin-inject-anvil `0.1.7`, kotlinx IO/coroutines/serialization/datetime, and SQLDelight `2.1.0` in `/Users/karl.sabo/git/dev-lake-utils/gradle/libs.versions.toml`.
- `/Users/karl.sabo/git/dev-lake-utils/gradle/libs.versions.toml` already documents SQLDelight as intentionally held on a Kotlin 2.2-compatible line: `# Keep SQLDelight on a Kotlin 2.2-compatible line until the repo toolchain moves forward.` Moving to Kotlin 2.3.21 should revisit that pin.
- Kotlin, serialization, Compose, KSP, and SQLDelight plugins are applied through the catalog in `/Users/karl.sabo/git/dev-lake-utils/gradle/libs.versions.toml` and buildSrc convention plugins in `/Users/karl.sabo/git/dev-lake-utils/buildSrc/src/main/kotlin/devlake.kotlin-multiplatform-conventions.gradle.kts`, `/Users/karl.sabo/git/dev-lake-utils/buildSrc/src/main/kotlin/devlake.kotlin-multiplatform-compose-conventions.gradle.kts`, and `/Users/karl.sabo/git/dev-lake-utils/buildSrc/src/main/kotlin/devlake.kotlin-inject-conventions.gradle.kts`.
- buildSrc directly loads the Kotlin Gradle plugin, Compose Gradle plugin, KSP Gradle plugin, Kotlin serialization plugin, and Compose compiler plugin using catalog versions in `/Users/karl.sabo/git/dev-lake-utils/buildSrc/build.gradle.kts`.
- Compose Desktop is used by `/Users/karl.sabo/git/dev-lake-utils/summary-publisher/summary-publisher.gradle.kts`, `/Users/karl.sabo/git/dev-lake-utils/user-metrics-publisher/user-metrics-publisher.gradle.kts`, `/Users/karl.sabo/git/dev-lake-utils/eng-hub/eng-hub.gradle.kts`, and `/Users/karl.sabo/git/dev-lake-utils/shared-resources/shared-resources.gradle.kts`.
- SQLDelight is applied in `/Users/karl.sabo/git/dev-lake-utils/utilities/utilities.gradle.kts`, including migration verification wired into `check`.
- The Gradle wrapper is already `9.4.1` in `/Users/karl.sabo/git/dev-lake-utils/gradle/wrapper/gradle-wrapper.properties` and `/Users/karl.sabo/git/dev-lake-utils/dev-lake-utils.gradle.kts`.

## Preliminary dependency research snapshot

Source: Maven Central metadata queried on 2026-06-17. Treat this as the starting matrix, not a substitute for checking release notes during implementation.

| Catalog key / family           |                    Current |                          Candidate for Kotlin 2.3.21 | Notes                                                                                                                                                                        |
|--------------------------------|---------------------------:|-----------------------------------------------------:|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `kotlin`                       |                   `2.2.21` |                                             `2.3.21` | Target version exists in Maven metadata. Latest overall is `2.4.0`, but the requested target is `2.3.21`.                                                                    |
| `ksp`                          |             `2.2.21-2.0.5` |                                 research/try `2.3.9` | KSP metadata now lists `2.3.0` through `2.3.9` rather than the old Kotlin-version-prefixed format. Validate plugin resolution and all KSP tasks.                             |
| `ktor`                         |                    `3.1.3` |                                              `3.5.0` | Ktor `3.5.0` metadata requires Kotlin stdlib `2.3.21`, making it the strongest Ktor candidate for this target.                                                               |
| `compose`                      |                   `1.10.0` |                                             `1.11.1` | Latest stable Compose Multiplatform from metadata. `1.12.0-alpha02` exists but should not be the default unless required.                                                    |
| `skiko`                        |                 `0.9.37.3` | remove explicit catalog pin/dependencies if possible | User direction: Compose should manage Skiko. Remove explicit app-module Skiko dependencies unless validation proves they are still required.                                 |
| `markdown`                     |                   `0.29.0` |                                             `0.41.0` | Compose-sensitive UI library. `0.42.0-b02` exists, but `0.41.0` is the latest non-beta version found.                                                                        |
| `kotlinInject`                 |                    `0.9.0` |                                              `0.9.0` | No newer version found. Compiler artifact metadata references Kotlin stdlib `2.2.20` and KSP API `2.3.0`; verify against Kotlin `2.3.21` rather than assuming compatibility. |
| `kotlinInjectAnvil`            |                    `0.1.7` |                                              `0.1.7` | No newer version found. Compiler artifact metadata references Kotlin stdlib `2.2.20` and KSP API `2.3.4`; this is a high-risk compatibility point.                           |
| `kotlinxIo`                    |                    `0.6.0` |                                              `0.9.0` | Metadata requires Kotlin stdlib `2.3.0`.                                                                                                                                     |
| `kotlinxCoroutines`            |                   `1.10.1` |                                             `1.11.0` | Latest stable from metadata.                                                                                                                                                 |
| `kotlinxSerializationJson`     |                    `1.8.1` |                                             `1.11.0` | Metadata requires Kotlin stdlib `2.3.20`.                                                                                                                                    |
| `kotlinxDatetime`              |                    `0.6.1` |                                              `0.8.0` | Latest stable from metadata. Validate API changes in date/time usages.                                                                                                       |
| `sqldelight`                   |                    `2.1.0` |                                              `2.3.2` | SQLDelight Gradle plugin `2.3.2` metadata requires Kotlin Gradle plugin `2.3.10`, so it is in the right Kotlin line.                                                         |
| `lifecycleViewModel`           |           `2.10.0-alpha06` |                                             `2.10.0` | Move from alpha to stable while touching Compose-adjacent versions.                                                                                                          |
| `xmlutil`                      |                   `0.91.3` |                                    no default change | `1.0.0-rc3` exists, but current `0.91.3` is latest stable-looking version from metadata.                                                                                     |
| `detekt`, `spotless`, `ktlint` | `1.23.8`, `8.6.0`, `1.8.0` |                                only bump if required | Not Kotlin runtime dependencies. Detekt has no newer stable in metadata; Spotless `8.7.0` exists but is not part of the Kotlin compatibility core.                           |

## Decisions

- Target Kotlin version is fixed at `2.3.21`; do not move to Kotlin `2.4.0` in this work.
- Use latest stable Kotlin-sensitive dependency versions only; do not use alpha, beta, or RC artifacts.
- Upgrade only Kotlin-sensitive dependencies; do not opportunistically update unrelated libraries.
- Let Compose manage Skiko transitively; remove explicit Skiko runtime dependencies unless validation proves they are still required.
- Done means `./gradlew clean build --no-build-cache --refresh-dependencies` passes.

## Acceptance tests

1. **Clean build on Kotlin 2.3.21**
   - Given a clean checkout of `/Users/karl.sabo/git/dev-lake-utils` using the repo Gradle wrapper,
   - when a developer runs `./gradlew clean build --no-build-cache --refresh-dependencies`,
   - then dependency resolution, buildSrc compilation, KMP compilation, KSP processing, Compose compilation, SQLDelight migration verification, and tests complete successfully with Kotlin `2.3.21` selected from the version catalog.

This should be one PR, not one PR per dependency. These versions are coupled through compiler plugins and Kotlin metadata; splitting them would create intermediate states that are likely unbuildable and not useful.

## Stories

### 1. Upgrade Kotlin toolchain and Kotlin-sensitive dependencies to a verified 2.3.21 matrix

**Acceptance criteria:** Given a clean checkout using the Gradle wrapper, when `./gradlew clean build --no-build-cache --refresh-dependencies` is run, then the build passes with `kotlin = "2.3.21"` and compatible stable Ktor, Compose, KSP, kotlin-inject, kotlinx, and SQLDelight versions recorded in the version catalog.

**Expected edits:**
- `/Users/karl.sabo/git/dev-lake-utils/gradle/libs.versions.toml`
- `/Users/karl.sabo/git/dev-lake-utils/buildSrc/build.gradle.kts` if plugin coordinates or KSP plugin format require adjustment
- `/Users/karl.sabo/git/dev-lake-utils/buildSrc/src/main/kotlin/devlake.kotlin-inject-conventions.gradle.kts` if KSP `2.3.x` changes configuration behavior or task names
- `/Users/karl.sabo/git/dev-lake-utils/buildSrc/src/main/kotlin/devlake.kotlin-multiplatform-compose-conventions.gradle.kts` if Compose `1.11.x` requires convention changes
- `/Users/karl.sabo/git/dev-lake-utils/utilities/utilities.gradle.kts` if SQLDelight `2.3.x` requires configuration changes
- `/Users/karl.sabo/git/dev-lake-utils/summary-publisher/summary-publisher.gradle.kts`, `/Users/karl.sabo/git/dev-lake-utils/user-metrics-publisher/user-metrics-publisher.gradle.kts`, and `/Users/karl.sabo/git/dev-lake-utils/eng-hub/eng-hub.gradle.kts` to remove explicit Skiko dependencies if Compose provides the runtime transitively
- `/Users/karl.sabo/git/dev-lake-utils/gradle/wrapper/gradle-wrapper.properties` and `/Users/karl.sabo/git/dev-lake-utils/dev-lake-utils.gradle.kts` only if validation shows Gradle `9.4.1` is below a required minimum

**Scope:**
- In: research release notes for every Kotlin-sensitive row in the matrix above, update the version catalog to latest stable compatible versions, adapt build configuration for plugin API changes, and fix source/build breakages caused by the upgrade.
- In: run `./gradlew clean build --no-build-cache --refresh-dependencies`; also run targeted tasks such as `:utilities:verifySqlDelightMigration`, KSP tasks, and Compose app compilation tasks if the root build failure does not exercise them clearly enough.
- Out: alpha/beta/RC dependencies, upgrading unrelated libraries solely because newer versions exist, UI redesign, feature work, broad refactors, and moving to Kotlin `2.4.0`.

**Notes:**
- Start with the version catalog change and keep the first commit as close as possible to dependency-only. Only change source after the compiler reports concrete incompatibilities.
- High-risk area: kotlin-inject and kotlin-inject-anvil have no newer releases in Maven metadata. If either compiler is incompatible with Kotlin `2.3.21`, do not paper over it; decide explicitly whether to pin Kotlin lower, remove/defer Anvil usage, or wait for upstream.
- High-risk area: KSP changed version scheme between the current `2.2.21-2.0.5` and the available `2.3.x` line. Validate actual plugin behavior in Gradle, not just metadata.
- SQLDelight should no longer stay on the catalog's Kotlin 2.2-compatible line if the repo moves to Kotlin `2.3.21`.
- Use latest stable dependency releases only; do not use alpha, beta, or RC artifacts for this upgrade.
