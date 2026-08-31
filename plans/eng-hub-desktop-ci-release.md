# Eng Hub desktop CI and release packaging

**Goal:** Build Eng Hub in GitHub Actions and publish stable desktop releases for Linux, macOS, and Windows without runtime writes to the installation directory.

**Context:**

- GitHub Actions workflows live under `.github/workflows`: `.github/workflows/build.yml` validates pull requests and pushes to `main`, while `.github/workflows/release.yml` creates GitHub Releases and packages desktop installers on matching operating-system runners.
- Some implementation predates this plan. `.github/workflows/build.yml` is already committed. `.github/workflows/release.yml` already contains macOS, Windows, Debian, and RPM jobs; uncommitted edits are changing its tag/version behavior. Existing work may be retained, changed, or redone based on the agreed acceptance tests.
- The root Gradle project is `dev-lake-utils`. `README.md` has TODOs to add GitHub Actions for Linux, macOS, and Windows releases and to create native executables.
- The build is Gradle/Kotlin Multiplatform Compose. `settings.gradle.kts` includes `:eng-hub`, and `eng-hub/eng-hub.gradle.kts` declares the Compose Desktop app with `mainClass = "com.github.karlsabo.devlake.enghub.MainKt"`.
- `eng-hub/eng-hub.gradle.kts` configures `TargetFormat.Dmg`, `TargetFormat.Msi`, `TargetFormat.Deb`, and `TargetFormat.Rpm`, with package name `eng-hub`. Native installers must be built on matching operating-system runners rather than cross-built on one runner.
- The desired release assets are an unsigned/unnotarized macOS `.dmg`, a Windows `.msi`, and both Linux `.deb` and `.rpm` packages. A Linux `.tar.gz` and a Windows `.exe` are out of scope.
- Pushing a raw stable SemVer tag such as `1.2.3` initiates a release. The tag value is passed unchanged to Gradle as the package version. Tags with a `v` prefix and `SNAPSHOT` versions are not release inputs.
- Releases are published as GitHub Releases. Maven/GitHub Packages publishing is deferred.
- `dev-lake-utils.gradle.kts` defaults the root version to `1.0.0-SNAPSHOT`. Its current uncommitted change allows the `releaseVersion` Gradle property to override that default. `eng-hub/eng-hub.gradle.kts` derives native package metadata from `rootProject.version`.
- Runtime config/data use `getApplicationDirectory(DEV_METRICS_APP_NAME)` from `utilities/src/commonMain/kotlin/com/github/karlsabo/tools/DirectoryUtils.kt`, which resolves to `~/Library/Application Support/DevLakeUtils` on macOS, `%APPDATA%/DevLakeUtils` on Windows, and `~/.local/share/DevLakeUtils` on Linux.
- Eng Hub stores configuration in that application-data directory at `eng-hub-config.json` through `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHubConfig.kt`.
- `eng-hub/src/jvmMain/resources/log4j2.xml` uses only a console appender. Persistent file logging is not part of this feature. If it is added later, its files must go under the user application-data directory.
- The installation directory may contain packaged application files, but the running application must not create or mutate runtime data there. This release work relies on the existing explicit application-data paths and console-only logging; a packaged-GUI write-monitoring test is out of scope.

## Remaining questions

1. Should a release remain a draft until every requested installer has uploaded successfully? **Recommended:** yes. Creating a public release before the packaging jobs finish can expose an empty or partial release when one platform fails. A failed run should leave a draft that can be retried or deleted.
2. Which macOS architectures are required: Intel x64, Apple silicon arm64, or both? Compose packages the architecture of its runner, so supporting both means two independently built DMGs and therefore two acceptance tests/stories. The current generic `macos-latest` job and architecture-free asset name do not make this contract explicit.

## Candidate acceptance tests

1. **CI validates every pull request**
   - Given a pull request is opened against the default branch, when GitHub Actions runs, then `.github/workflows/build.yml` runs `./gradlew clean build` and reports the result on the pull request.

2. **A stable tag creates a versioned GitHub Release**
   - Given tag `1.2.3` is pushed, when `.github/workflows/release.yml` runs, then it creates GitHub Release `1.2.3` and supplies Gradle package version `1.2.3` instead of the default `1.0.0-SNAPSHOT`.

3. **The release contains a macOS DMG**
   - Given the GitHub Release for version `1.2.3` is being built, when the macOS packaging job completes, then the release contains `eng-hub-1.2.3-macos.dmg`.

4. **The release contains a Windows MSI**
   - Given the GitHub Release for version `1.2.3` is being built, when the Windows packaging job completes, then the release contains `eng-hub-1.2.3-windows-x64.msi`.

5. **The release contains a Debian package**
   - Given the GitHub Release for version `1.2.3` is being built, when the Debian packaging job completes, then the release contains `eng-hub-1.2.3-linux-amd64.deb`.

6. **The release contains an RPM package**
   - Given the GitHub Release for version `1.2.3` is being built, when the RPM packaging job completes, then the release contains `eng-hub-1.2.3-linux-x86_64.rpm`.

The installation-directory invariant is a documented constraint supported by the existing runtime paths, not a separate acceptance test. Persistent file logging is out of scope.

## Candidate stories

### 1. PR CI runs the Gradle build — already implemented

**Acceptance criteria:** Given a pull request is opened against the default branch, when GitHub Actions runs, then `.github/workflows/build.yml` runs `./gradlew clean build` and reports the result on the pull request.

**Expected edits:** None if the existing `.github/workflows/build.yml` behavior is accepted; otherwise `.github/workflows/build.yml`.

**Scope:** PR and `main` build validation only. Native installers and release publication are out.

**Notes:** The workflow is already committed and builds on Linux, macOS, and Windows. The repository-required validation command is defined in `AGENTS.md` as `./gradlew clean build`.

### 2. A stable tag creates a GitHub Release - done

**done:** 2026 08 31

**Acceptance criteria:** Given tag `1.2.3` is pushed, when `.github/workflows/release.yml` runs, then it creates GitHub Release `1.2.3` and supplies Gradle package version `1.2.3` instead of the default `1.0.0-SNAPSHOT`.

**Expected edits:** `.github/workflows/release.yml`, `dev-lake-utils.gradle.kts`; tests or a validation script for version parsing if needed.

**Scope:** Raw stable SemVer tag matching, validation, GitHub Release creation, and Gradle version wiring. Tags prefixed with `v`, `SNAPSHOT` versions, and installer formats are out.

**Notes:** `.github/workflows/release.yml` accepts raw stable SemVer tags such as `1.2.3` and passes the tag unchanged as the Gradle release version. `eng-hub/eng-hub.gradle.kts` strips pre-release/build suffixes and requires three numeric package-version components.

### 3. Publish the macOS DMG - done

**done:** 2026-08-31

**Acceptance criteria:** Given the GitHub Release for version `1.2.3` is being built, when the macOS packaging job completes, then the release contains `eng-hub-1.2.3-macos.dmg`.

**Expected edits:** `.github/workflows/release.yml`; `eng-hub/eng-hub.gradle.kts` only if package configuration changes are required.

**Scope:** One unsigned and unnotarized `.dmg`. Signing, notarization, and additional macOS architectures are out.

**Notes:** `eng-hub/eng-hub.gradle.kts` declares `TargetFormat.Dmg`; `.github/workflows/release.yml` already contains a `packageDmg` job that can be validated or reworked.

### 4. Publish the Windows MSI

**Acceptance criteria:** Given the GitHub Release for version `1.2.3` is being built, when the Windows packaging job completes, then the release contains `eng-hub-1.2.3-windows-x64.msi`.

**Expected edits:** `.github/workflows/release.yml`; `eng-hub/eng-hub.gradle.kts` only if package configuration changes are required.

**Scope:** One `.msi` installer. A separate `.exe` installer is out.

**Notes:** `eng-hub/eng-hub.gradle.kts` declares `TargetFormat.Msi`; `.github/workflows/release.yml` already contains a `packageMsi` job that can be validated or reworked.

### 5. Publish the Debian package

**Acceptance criteria:** Given the GitHub Release for version `1.2.3` is being built, when the Debian packaging job completes, then the release contains `eng-hub-1.2.3-linux-amd64.deb`.

**Expected edits:** `.github/workflows/release.yml`; `eng-hub/eng-hub.gradle.kts` only if package configuration changes are required.

**Scope:** One `.deb` package. A generic `.tar.gz` is out.

**Notes:** `eng-hub/eng-hub.gradle.kts` declares `TargetFormat.Deb`; `.github/workflows/release.yml` already contains a `packageDeb` job that can be validated or reworked.

### 6. Publish the RPM package

**Acceptance criteria:** Given the GitHub Release for version `1.2.3` is being built, when the RPM packaging job completes, then the release contains `eng-hub-1.2.3-linux-x86_64.rpm`.

**Expected edits:** `.github/workflows/release.yml`; `eng-hub/eng-hub.gradle.kts` only if package configuration changes are required.

**Scope:** One `.rpm` package. Debian packaging and generic archives are out except for shared workflow setup already introduced by earlier stories.

**Notes:** `eng-hub/eng-hub.gradle.kts` declares `TargetFormat.Rpm`; `.github/workflows/release.yml` already installs `rpm` and runs `packageRpm`. This remains separate from the Debian story because each package is independently observable and can fail for different toolchain reasons.

