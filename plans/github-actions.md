# GitHub Actions build and release plan

## Goal

Add GitHub Actions that validate Eng Hub on Linux, macOS, and Windows, then attach native Eng Hub installers to a GitHub Release when a stable version tag is pushed.

## Context

- The README already tracks this feature as a TODO: build and publish app versions for Linux, macOS, and Windows and attach built binaries when a new Git tag is pushed (`README.md`).
- The repo is a Gradle multi-project build with `eng-hub`, `summary-publisher`, `user-metrics-publisher`, `utilities`, and `shared-resources` included from `settings.gradle.kts`.
- The Eng Hub desktop app is the initial release target: `eng-hub/eng-hub.gradle.kts` configures Compose Desktop with main class `com.github.karlsabo.devlake.enghub.MainKt`, package name `eng-hub`, and native distribution targets `TargetFormat.Dmg`, `TargetFormat.Msi`, and `TargetFormat.Deb`.
- `eng-hub/README.md` documents the existing local build and package commands: `./gradlew :eng-hub:build`, `:eng-hub:packageDistributionForCurrentOS`, `:eng-hub:packageDmg`, `:eng-hub:packageMsi`, and `:eng-hub:packageDeb`.
- `eng-hub/eng-hub.gradle.kts` currently hard-codes `packageVersion = "1.0.0"`, while the root build hard-codes `version = "0.1.0-SNAPSHOT"` in `dev-lake-utils.gradle.kts`. Release builds need `eng-hub` package metadata to use the checked-in Gradle version, and the release workflow needs to verify that the raw stable SemVer tag matches that version before publishing.
- The Gradle wrapper is configured for Gradle `9.4.1` in `dev-lake-utils.gradle.kts`; Kotlin JVM compilation targets JVM 17 in `buildSrc/src/main/kotlin/devlake.kotlin-multiplatform-conventions.gradle.kts`.
- There is no checked-in GitHub Actions workflow yet under `.github/workflows`.
- `utilities/utilities.gradle.kts` applies `maven-publish`, but only publishes to `mavenLocal()`. For native app binaries, GitHub Release assets are the right first publishing mechanism; a Gradle `publish` story should wait until there is a Maven/package registry requirement.
- The current local branch and remote default branch are both `main`.

## Decisions

1. `:eng-hub` is the only executable app in scope. `summary-publisher` and `user-metrics-publisher` installers are out of scope.
2. Release tags use raw stable SemVer only: `MAJOR.MINOR.PATCH`, for example `0.2.0`. No leading `v`, prerelease metadata, or build metadata in the first release flow.
3. The checked-in Gradle version is the release source of truth. A release commit bumps the Gradle version, the raw stable SemVer tag is applied to that commit, and the release workflow verifies that the tag exactly matches the checked-in Gradle version before packaging.
4. Initial installers can be unsigned and unnotarized. macOS Gatekeeper and Windows SmartScreen polish are separate stories.
5. GitHub Release creation should be automatic on tag push, not manually dispatched.
6. Release assets should be attached to GitHub Releases only. Registry/package-manager publishing is deferred.
7. CI should run on pull requests and on pushes to `main`.
8. Normal CI should use one workflow, `.github/workflows/build.yml`, with three explicit jobs: `build-linux`, `build-macos`, and `build-windows`.

## Acceptance tests

1. Given a pull request changes Kotlin or Gradle code, when the Linux CI job runs on `ubuntu-latest`, then it executes `./gradlew clean build` and reports success or failure on the PR.
2. Given a pull request changes Kotlin or Gradle code, when the macOS CI job runs on `macos-latest`, then it executes `./gradlew clean build` and reports success or failure on the PR.
3. Given a pull request changes Kotlin or Gradle code, when the Windows CI job runs on `windows-latest`, then it executes `./gradlew clean build` and reports success or failure on the PR.
4. Given a commit is pushed to `main`, when GitHub Actions evaluates the repository workflows, then the same build workflow starts for that push.
5. Given tag `0.2.0` is pushed to a commit whose checked-in Gradle version is `0.3.0`, when the release workflow runs, then it fails before packaging and uploads no installer assets.
6. Given tag `0.2.0` is pushed to a commit whose checked-in Gradle version is `0.2.0`, when the release workflow runs on Linux, then GitHub Release `0.2.0` contains an Eng Hub `.deb` asset named with version `0.2.0`.
7. Given tag `0.2.0` is pushed to a commit whose checked-in Gradle version is `0.2.0`, when the release workflow runs on macOS, then GitHub Release `0.2.0` contains an Eng Hub `.dmg` asset named with version `0.2.0`.
8. Given tag `0.2.0` is pushed to a commit whose checked-in Gradle version is `0.2.0`, when the release workflow runs on Windows, then GitHub Release `0.2.0` contains an Eng Hub `.msi` asset named with version `0.2.0`.

## Stories

### 1. Build Eng Hub on Linux pull requests

**Status:** Done

**Acceptance criteria:** Given a pull request changes Kotlin or Gradle code, when the Linux CI job runs on `ubuntu-latest`, then it executes `./gradlew clean build` and reports success or failure on the PR.

**Expected edits:**

- Add `.github/workflows/build.yml` with a `build-linux` job.
- Optionally update `README.md` development docs with the CI command if the workflow name/behavior should be documented.

**Scope:**

- In: Linux CI job; checkout; Java setup; Gradle setup/cache; `./gradlew clean build`; `pull_request` trigger.
- Out: `main` push trigger; macOS build; Windows build; release publishing; native installer packaging; signing/notarization.

**Notes:**

- Start with Linux as the tracer bullet because it is the cheapest and fastest runner.
- Use the repository wrapper (`gradlew`, `gradlew.bat`) rather than installing Gradle globally.
- Use Temurin Java 21 unless local verification shows Gradle/Kotlin/Compose require Java 17. The project compiles JVM bytecode for 17 (`buildSrc/src/main/kotlin/devlake.kotlin-multiplatform-conventions.gradle.kts`) but Gradle itself can run on a newer JDK.
- Keep the job name stable because GitHub branch protection/repository rulesets will require this check before merge.
- Validate locally with `./gradlew clean build` before opening the PR.

### 2. Build Eng Hub on macOS pull requests

**Status:** Done

**Acceptance criteria:** Given a pull request changes Kotlin or Gradle code, when the macOS CI job runs on `macos-latest`, then it executes `./gradlew clean build` and reports success or failure on the PR.

**Expected edits:**

- Extend `.github/workflows/build.yml` with a `build-macos` job.

**Scope:**

- In: macOS PR build validation using the same Gradle command as Linux.
- Out: `main` push trigger; DMG packaging; Apple signing; notarization; release upload.

**Notes:**

- `utilities/utilities.gradle.kts` defines a `macosArm64` Kotlin native target, so macOS CI is valuable even before installer packaging.
- Keep this as an explicit job, not a matrix entry, because the chosen design favors clearer per-OS checks over reduced YAML duplication.

### 3. Build Eng Hub on Windows pull requests

**Status:** Done

**Acceptance criteria:** Given a pull request changes Kotlin or Gradle code, when the Windows CI job runs on `windows-latest`, then it executes `./gradlew clean build` and reports success or failure on the PR.

**Expected edits:**

- Extend `.github/workflows/build.yml` with a `build-windows` job.

**Scope:**

- In: Windows PR build validation using the same Gradle command as Linux and macOS.
- Out: `main` push trigger; MSI packaging; code signing; release upload.

**Notes:**

- Prefer invoking `./gradlew clean build` consistently. If Windows shell path behavior causes issues, switch only the Windows step to `./gradlew.bat clean build` and document why in the workflow.
- Keep line endings and executable-bit assumptions visible; `gradlew.bat` exists for Windows in the repo root.

### 4. Run build workflow on `main` pushes

**Acceptance criteria:** Given a commit is pushed to `main`, when GitHub Actions evaluates the repository workflows, then the same build workflow starts for that push.

**Expected edits:**

- Extend `.github/workflows/build.yml` triggers with `push` on `main`.
- Optionally update `README.md` to say CI runs on pull requests and `main` pushes.

**Scope:**

- In: workflow trigger behavior only.
- Out: adding new build commands; branch protection/repository ruleset configuration, which is a GitHub repository setting rather than a code change.

**Notes:**

- This catches bad merge commits, admin bypasses, and branch protection drift after PR CI has already passed.
- Pull request merge gating still needs to be enforced in GitHub branch protection or repository rulesets.

### 5. Reject a release tag that does not match the checked-in Gradle version

**Acceptance criteria:** Given tag `0.2.0` is pushed to a commit whose checked-in Gradle version is `0.3.0`, when the release workflow runs, then it fails before packaging and uploads no installer assets.

**Expected edits:**

- Add `.github/workflows/release.yml` with a raw stable SemVer tag trigger.
- Add or expose a root Gradle task in `dev-lake-utils.gradle.kts` that prints the effective project version in a workflow-friendly way, for example `printVersion`.
- Use the existing root Gradle version in `dev-lake-utils.gradle.kts` as the source of truth.
- Optionally update `README.md` or `eng-hub/README.md` with the release procedure: bump Gradle version, merge, tag the same commit with the same raw stable SemVer string.

**Scope:**

- In: release preflight; raw stable SemVer tag matching; fail-fast version mismatch; no package upload on mismatch.
- Out: building `.deb`, `.dmg`, or `.msi`; signing/notarization; manual release dispatch; prerelease/build metadata tags.

**Notes:**

- Current evidence: `dev-lake-utils.gradle.kts` hard-codes root `version = "0.1.0-SNAPSHOT"`, and `eng-hub/eng-hub.gradle.kts` hard-codes `packageVersion = "1.0.0"`. Those must be brought under one release version concept before publishing installers.
- Raw tag `0.2.0` should compare against Gradle version `0.2.0` exactly. A tag `v0.2.0`, `0.2.0-alpha.1`, or `0.2.0+build.5` should not trigger the first release workflow.
- Keep this as a separate story from packaging so the irreversible part of release publishing only runs after version authority is clear.

### 6. Publish Linux `.deb` on matching version tag

**Acceptance criteria:** Given tag `0.2.0` is pushed to a commit whose checked-in Gradle version is `0.2.0`, when the release workflow runs on Linux, then GitHub Release `0.2.0` contains an Eng Hub `.deb` asset named with version `0.2.0`.

**Expected edits:**

- Extend `.github/workflows/release.yml` with a Linux package/upload job.
- Edit `eng-hub/eng-hub.gradle.kts` so `nativeDistributions.packageVersion` uses the checked-in Gradle project version instead of the current hard-coded `1.0.0`.
- Update `eng-hub/README.md` with the release tag format and Linux package output.

**Scope:**

- In: raw stable SemVer tag trigger such as `0.2.0`; Linux runner; verify tag/version match from Story 5; run `./gradlew clean :eng-hub:packageDeb`; upload the `.deb` to GitHub Release `0.2.0`.
- Out: macOS/Windows assets; signing; repository package publishing; Maven publishing; prerelease/build metadata version translation.

**Notes:**

- This is the release tracer bullet. It proves Gradle version authority, release creation, and asset upload end-to-end with one artifact.
- Use `permissions: contents: write` only in the release workflow.
- Prefer GitHub Release upload over `./gradlew publish` because the existing publish configuration is only `mavenLocal()` in `utilities/utilities.gradle.kts`, and native installers are release assets, not Maven library artifacts.
- Asset naming should make the platform unambiguous, for example `eng-hub-0.2.0-linux-amd64.deb` if Compose emits a less descriptive file name.

### 7. Publish macOS `.dmg` on matching version tag

**Acceptance criteria:** Given tag `0.2.0` is pushed to a commit whose checked-in Gradle version is `0.2.0`, when the release workflow runs on macOS, then GitHub Release `0.2.0` contains an Eng Hub `.dmg` asset named with version `0.2.0`.

**Expected edits:**

- Extend `.github/workflows/release.yml` with a `macos-latest` package/upload job.
- Reuse the version plumbing added in `eng-hub/eng-hub.gradle.kts` by Story 6.
- Update `eng-hub/README.md` with macOS package output and unsigned/notarization caveat.

**Scope:**

- In: macOS runner; verify tag/version match from Story 5; `./gradlew clean :eng-hub:packageDmg`; upload `.dmg` to the same GitHub Release for the tag.
- Out: Apple Developer ID signing; notarization; universal binary tuning; Homebrew cask publishing; prerelease/build metadata version translation.

**Notes:**

- Compose Desktop package tasks are OS-sensitive; build the DMG on macOS rather than trying to cross-package from Linux.
- If GitHub Release creation races with the Linux job, use a create-release job that platform upload jobs depend on, or use an upload mode/action that safely creates or updates the release.

### 8. Publish Windows `.msi` on matching version tag

**Acceptance criteria:** Given tag `0.2.0` is pushed to a commit whose checked-in Gradle version is `0.2.0`, when the release workflow runs on Windows, then GitHub Release `0.2.0` contains an Eng Hub `.msi` asset named with version `0.2.0`.

**Expected edits:**

- Extend `.github/workflows/release.yml` with a `windows-latest` package/upload job.
- Reuse the version plumbing added in `eng-hub/eng-hub.gradle.kts` by Story 6.
- Update `eng-hub/README.md` with Windows package output and unsigned/code-signing caveat.

**Scope:**

- In: Windows runner; verify tag/version match from Story 5; `./gradlew clean :eng-hub:packageMsi`; upload `.msi` to the same GitHub Release for the tag.
- Out: Windows code signing; winget/chocolatey publishing; installer auto-update support; prerelease/build metadata version translation.

**Notes:**

- Keep this separate from the macOS story because Windows packaging failures usually have different causes and different remediation.
- If Gradle invocation differs on Windows, prefer `./gradlew.bat clean :eng-hub:packageMsi` and document the shell-specific choice in the workflow.

## Suggested sequence

1. Story 1: Linux build CI. This gives immediate PR feedback with minimal runner cost.
2. Story 2: macOS build CI. Adds coverage for the native macOS target in `utilities/utilities.gradle.kts`.
3. Story 3: Windows build CI. Completes cross-platform PR validation.
4. Story 4: `main` push trigger. Adds post-merge safety once the jobs exist.
5. Story 5: release tag/version preflight. Establishes Gradle as the release source of truth before publishing assets.
6. Story 6: Linux release asset. Proves the tag-to-release pipeline and version plumbing end-to-end.
7. Story 7: macOS release asset. Reuses the release pipeline for DMG output.
8. Story 8: Windows release asset. Completes native release coverage.

## Trade-offs

- Three explicit CI jobs duplicate some YAML compared with a matrix, but they produce clearer required checks and make OS-specific fixes easier.
- Using checked-in Gradle version as release source of truth makes releases auditable in source control, but it adds release-bump bookkeeping. The workflow must fail fast when the tag and Gradle version drift.
- Starting with unsigned installers ships something usable sooner but leaves macOS Gatekeeper and Windows SmartScreen warnings for later.
- Attaching binaries to GitHub Releases avoids premature package-registry design. If the real need is Maven, Homebrew, winget, or Chocolatey publishing, that should be planned as separate user-visible stories.
- Stable raw SemVer tags are simpler and safer for native installers than prerelease/build metadata. If prerelease distribution matters later, it deserves its own story because MSI and other package formats can impose stricter version rules than SemVer.
