# Eng Hub desktop CI and release packaging

**Goal:** Add GitHub Actions support to build Eng Hub and publish tagged desktop release bundles for Linux, macOS, and Windows without writing runtime logs into the app install directory.

**Context:**

- The repository has no existing GitHub Actions workflows under `.github/workflows`, so CI/release automation starts from scratch.
- The root Gradle project is `dev-lake-utils`; `README.md` currently has a TODO to "Add GitHub actions to build and publish versions of the app for Linux, macOS, Window" and "Create native executables".
- The build is Gradle/Kotlin Multiplatform Compose. `settings.gradle.kts` includes `:eng-hub`, and `eng-hub/eng-hub.gradle.kts` declares the Compose Desktop app with `mainClass = "com.github.karlsabo.devlake.enghub.MainKt"`.
- `eng-hub/eng-hub.gradle.kts` already configures Compose native distributions with `TargetFormat.Dmg`, `TargetFormat.Msi`, `TargetFormat.Deb`, and `TargetFormat.Rpm`, `packageName = "eng-hub"`, and a `packageVersion` derived from `rootProject.version` with pre-release/build suffixes removed.
- `./gradlew :eng-hub:tasks --all` shows Compose packaging tasks including `packageDmg`, `packageMsi`, `packageDeb`, `packageDistributionForCurrentOS`, `createDistributable`, and release variants. Native packages need to be built on matching OS runners rather than cross-built from one runner.
- The root build currently sets `version = "0.1.0-SNAPSHOT"` in `dev-lake-utils.gradle.kts`, and Eng Hub derives its native package version from that value. The release workflow still needs to set the root project version from the release tag so package metadata uses the tagged version.
- There is no `.github/workflows` directory yet.
- Runtime config/data already use `getApplicationDirectory(DEV_METRICS_APP_NAME)` from `utilities/src/commonMain/kotlin/com/github/karlsabo/tools/DirectoryUtils.kt`, which resolves to `~/Library/Application Support/DevLakeUtils` on macOS, `%APPDATA%/DevLakeUtils` on Windows, and `~/.local/share/DevLakeUtils` on Linux.
- Eng Hub config is stored in that app directory at `eng-hub-config.json` via `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHubConfig.kt`.
- Eng Hub currently has `eng-hub/src/jvmMain/resources/log4j2.xml` with only a console appender. If file logs are added for release builds, they must be routed under the application directory, not the installation/current working directory.

## Open questions

1. Release trigger: should bundles be attached only for `v*` tags, or also for manual `workflow_dispatch` builds?
2. Linux artifact: do you actually need `.tar.gz`, or is `.deb` enough? Compose already supports `.deb`; `.tar.gz` would likely be an archived `createDistributable` output.
3. Windows artifact: do you want `.msi` (already configured) or a literal `.exe` installer? Compose/JPackage MSI is the shortest path; `.exe` means adding another installer tool or changing packaging expectations.
4. macOS signing/notarization: unsigned `.dmg` is easy; signed/notarized `.dmg` needs Apple Developer credentials/secrets and is a separate story.
5. Version source: should tags look like `v1.2.3` and override the root Gradle `version` during release builds, or should the root version be edited before tagging?
6. Release publishing mechanism: use GitHub Releases with uploaded assets, not `./gradlew publish`, unless you specifically want Maven/GitHub Packages artifacts too. `publish` is probably the wrong abstraction for end-user desktop installers.
7. Logs: do you want persistent file logging added now, or only a guard/scan confirming current releases do not write logs into the install dir? Current config is console-only.

## Candidate acceptance tests

1. **CI build validates every pull request**
   - Given a pull request is opened against the default branch, when GitHub Actions runs, then the workflow checks out the repository, sets up a JDK, runs `./gradlew clean build`, and reports the build result on the PR.

2. **A tag creates a macOS desktop release bundle**
   - Given tag `v1.2.3` is pushed, when the release workflow runs on macOS, then GitHub Releases contains an Eng Hub macOS `.dmg` asset for version `1.2.3`.

3. **A tag creates a Windows desktop release bundle**
   - Given tag `v1.2.3` is pushed, when the release workflow runs on Windows, then GitHub Releases contains an Eng Hub Windows installer asset for version `1.2.3`.

4. **A tag creates a Linux desktop release bundle**
   - Given tag `v1.2.3` is pushed, when the release workflow runs on Linux, then GitHub Releases contains an Eng Hub Linux asset for version `1.2.3`.

5. **Release bundles use the Git tag as the application version**
   - Given tag `v1.2.3` is pushed, when native packages are built, then the release workflow supplies root project version `1.2.3` and the generated Eng Hub package metadata uses version `1.2.3` instead of `0.1.0` derived from the default root version.

6. **Runtime file logs stay under the user application data directory**
   - Given the installed Eng Hub app starts and writes a log message, when the log file is created, then it is under `getApplicationDirectory(DEV_METRICS_APP_NAME)` in a `logs` subdirectory and no log file is created in the install/current working directory.

## Candidate stories

### 1. PR CI runs the Gradle build

**Acceptance criteria:** Given a pull request is opened against the default branch, when GitHub Actions runs, then the workflow checks out the repository, sets up a JDK, runs `./gradlew clean build`, and reports the build result on the PR.

**Expected edits:** `.github/workflows/ci.yml`.

**Scope:** Add the minimum workflow for PR/push validation. Do not build native installers or publish releases in this PR.

**Notes:** Use the Gradle wrapper already present at `gradlew`. Root build configuration is in `dev-lake-utils.gradle.kts`. This is the tracer bullet because it gives immediate CI feedback before release automation is added.

### 2. Tag release publishes a macOS DMG

**Acceptance criteria:** Given tag `v1.2.3` is pushed, when the release workflow runs on macOS, then GitHub Releases contains an Eng Hub macOS `.dmg` asset for version `1.2.3`.

**Expected edits:** `.github/workflows/release.yml`, `eng-hub/eng-hub.gradle.kts`, possibly `dev-lake-utils.gradle.kts` or `gradle.properties` for version wiring.

**Scope:** macOS only. Unsigned/unnotarized unless Apple signing secrets are explicitly provided. Do not add Windows/Linux packaging in this story.

**Notes:** `eng-hub/eng-hub.gradle.kts` already declares `TargetFormat.Dmg` and derives package metadata from `rootProject.version`; use Compose task `:eng-hub:packageDmg` or `:eng-hub:packageReleaseDmg`. The workflow must supply the tag-derived root version.

### 3. Tag release publishes a Windows installer

**Acceptance criteria:** Given tag `v1.2.3` is pushed, when the release workflow runs on Windows, then GitHub Releases contains an Eng Hub Windows installer asset for version `1.2.3`.

**Expected edits:** `.github/workflows/release.yml`, possibly `eng-hub/eng-hub.gradle.kts` if `.exe` is required instead of `.msi`.

**Scope:** Windows release asset only. Do not add macOS signing, Linux packages, or Maven publishing.

**Notes:** `eng-hub/eng-hub.gradle.kts` already declares `TargetFormat.Msi`. If a literal `.exe` is required, this is not the same acceptance test and should be split because it likely needs a different installer toolchain.

### 4. Tag release publishes a Linux bundle

**Acceptance criteria:** Given tag `v1.2.3` is pushed, when the release workflow runs on Linux, then GitHub Releases contains an Eng Hub Linux asset for version `1.2.3`.

**Expected edits:** `.github/workflows/release.yml`, possibly `eng-hub/eng-hub.gradle.kts` if `.tar.gz` is required in addition to `.deb`.

**Scope:** Linux release asset only. Do not add macOS or Windows changes beyond workflow matrix reuse.

**Notes:** `eng-hub/eng-hub.gradle.kts` already declares `TargetFormat.Deb`. A `.tar.gz` should probably archive `:eng-hub:createDistributable` output; confirm whether `.deb`, `.tar.gz`, or both are required.

### 5. Release packages derive version from Git tags

**Acceptance criteria:** Given tag `v1.2.3` is pushed, when native packages are built, then the release workflow supplies root project version `1.2.3` and the generated Eng Hub package metadata uses version `1.2.3` instead of `0.1.0` derived from the default root version.

**Expected edits:** `dev-lake-utils.gradle.kts` or `gradle.properties`, and `.github/workflows/release.yml`; `eng-hub/eng-hub.gradle.kts` only if its existing root-version validation needs adjustment.

**Scope:** Version wiring only. Do not add new package formats.

**Notes:** This could be merged into the first release-platform story if we want fewer PRs, but it is a distinct acceptance test because users can observe package metadata/version independently from asset upload.

### 6. File logs go to the application data directory

**Acceptance criteria:** Given the installed Eng Hub app starts and writes a log message, when the log file is created, then it is under `getApplicationDirectory(DEV_METRICS_APP_NAME)` in a `logs` subdirectory and no log file is created in the install/current working directory.

**Expected edits:** `eng-hub/src/jvmMain/resources/log4j2.xml`, `eng-hub/src/jvmMain/kotlin/com/github/karlsabo/devlake/enghub/main.kt`, possibly `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHubAppMetadata.kt`, and tests under `eng-hub/src/commonTest` or `eng-hub/src/jvmTest` if feasible.

**Scope:** Runtime logging location only. Do not add release packaging in this PR.

**Notes:** Current `log4j2.xml` is console-only, so the immediate risk is future file logging defaulting to current/install dir. If file logging is wanted, set a system property before Log4j initializes, e.g. an Eng Hub log directory derived from `getApplicationDirectory(DEV_METRICS_APP_NAME)`, and configure a rolling file appender to use that property.
