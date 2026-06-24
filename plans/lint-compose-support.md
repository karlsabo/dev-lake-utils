# Lint Compose Support

**Goal:** Make detekt and ktlint/Spotless understand Compose Multiplatform idioms in this repository.

## Context

Docs read:

- detekt Compose guidance for the repo's current detekt version: https://detekt.dev/docs/1.23.8/introduction/compose/
- compose-rules ktlint setup: https://mrmans0n.github.io/compose-rules/ktlint/
- Spotless ktlint custom ruleset integration: https://github.com/diffplug/spotless/tree/main/plugin-gradle#ktlint

Repo evidence:

- Root lint wiring lives in `dev-lake-utils.gradle.kts`: detekt is applied/configured there, `check` depends on `detekt`, and Spotless configures ktlint for Kotlin and Kotlin Gradle files.
- Tool versions live in `gradle/libs.versions.toml`: `detekt = "1.23.8"`, `ktlint = "1.8.0"`, `spotless = "8.6.0"`.
- `config/detekt/` exists, but there is no committed detekt config file yet.
- Compose is a first-class concern: `buildSrc/src/main/kotlin/devlake.kotlin-multiplatform-compose-conventions.gradle.kts` applies `org.jetbrains.compose` and `kotlin("plugin.compose")`.
- Compose convention consumers include `eng-hub/eng-hub.gradle.kts`, `summary-publisher/summary-publisher.gradle.kts`, and `user-metrics-publisher/user-metrics-publisher.gradle.kts`.
- Example Compose files include:
  - `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHub.kt`
  - `summary-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/tools/ui/SummaryPublisherScreen.kt`
  - `user-metrics-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/metrics/ui/UserMetricPublisherScreen.kt`
- Current baseline is clean: `./gradlew detekt spotlessCheck --continue` passes locally.

## Decisions

- Fix real violations introduced by enabling Compose lint rules in the same PR if the set is small.
- If the new violation count is large, stop and split cleanup follow-ups instead of hiding everything behind suppressions.
- Prefer no detekt baseline for this Compose-support work. If a baseline is unavoidable, create explicit cleanup follow-ups for the baseline entries.

## Acceptance tests

1. **detekt allows Compose idioms**
   - Given `SummaryPublisherScreen` is an idiomatic PascalCase `@Composable` function in `summary-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/tools/ui/SummaryPublisherScreen.kt`, when a developer runs `./gradlew detekt`, then detekt uses Compose-aware configuration and does not report Compose false positives for documented Compose idioms.

2. **Spotless ktlint runs Compose rules**
   - Given `UserMetricPublisherScreen` is Compose UI code in `user-metrics-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/metrics/ui/UserMetricPublisherScreen.kt`, when a developer runs `./gradlew spotlessKotlinCheck`, then ktlint runs with `io.nlopez.compose.rules:ktlint` through Spotless and fails only for real rule violations, not ruleset resolution/configuration errors.

## Stories / PRs

### 1. Configure detekt for Compose idioms — Done

**Acceptance criteria:** Given `SummaryPublisherScreen` is an idiomatic PascalCase `@Composable`, when `./gradlew detekt` runs, then detekt uses Compose-aware config and does not flag documented Compose false positives.

**Expected edits:**

- Add `config/detekt/detekt.yml` or equivalent.
- Wire that config in `dev-lake-utils.gradle.kts` under the existing `detekt { ... }` block.

**Scope:**

- In: follow detekt's Compose guidance for detekt `1.23.8`.
- In: keep the current detekt source roots and report configuration.
- In: keep `buildUponDefaultConfig` behavior explicit if used.
- Out: broad detekt threshold tuning unrelated to Compose.

**Implementation notes:**

The detekt docs recommend these Compose-specific adjustments:

- `FunctionNaming`: allow PascalCase `@Composable` functions by either broadening `functionPattern` to `[a-zA-Z][a-zA-Z0-9]*` or adding `Composable` to `ignoreAnnotated`.
- `TopLevelPropertyNaming`: use `constantPattern: '[A-Z][A-Za-z0-9]*'` for PascalCase Compose constants such as `FooPadding`.
- `LongParameterList`: raise `functionThreshold` and/or set `ignoreDefaultParameters: true` for composables with many defaulted parameters.
- `MagicNumber`: set `ignorePropertyDeclaration: true` for Compose object/color property declarations.
- `UnusedPrivateMember`: add `Preview` to `ignoreAnnotated`.
- `TooManyFunctions`: add `Preview` to `ignoreAnnotatedFunctions`.

### 2. Add compose-rules to ktlint through Spotless

**Acceptance criteria:** Given Compose UI code exists in `UserMetricPublisherScreen`, when `./gradlew spotlessKotlinCheck` runs, then Spotless invokes ktlint with compose-rules loaded and reports real Compose lint violations.

**Expected edits:**

- Add a compose-rules ktlint version/library entry in `gradle/libs.versions.toml`.
- Add Spotless `ktlint(...).customRuleSets(...)` configuration in `dev-lake-utils.gradle.kts` for Kotlin source files.
- Fix any small set of real violations newly reported by compose-rules.

**Scope:**

- In: use `io.nlopez.compose.rules:ktlint` with the current ktlint `1.8.0` setup.
- In: start with `0.6.2` unless resolution or rule-engine compatibility fails; the compose-rules matrix says `0.4.28+` supports ktlint `1.8.0`, and `0.6.2` still declares ktlint `1.8.0` dependencies.
- In: configure compose-rules only for `spotless { kotlin { ... } }` unless local validation shows it is useful and safe for `kotlinGradle`.
- Out: mass reformatting or broad lint cleanup unrelated to newly enabled Compose rules.

**Implementation notes:**

- Spotless docs show custom ktlint rulesets via:

  ```kotlin
  ktlint(libs.versions.ktlint.get())
      .customRuleSets(listOf("io.nlopez.compose.rules:ktlint:<version>"))
  ```

- Run `./gradlew spotlessKotlinCheck` and `./gradlew spotlessKotlinGradleCheck` to catch scope mistakes.
- Optionally introduce and revert a temporary local compose-rules violation to prove the ruleset is actually loaded.

## Validation

Run these before opening the PR(s):

```bash
./gradlew detekt
./gradlew spotlessKotlinCheck
./gradlew spotlessKotlinGradleCheck
./gradlew check
```

