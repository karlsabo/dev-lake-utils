# Repository Agent Guidelines

## Required validation

- After making code changes, run `./gradlew clean build` from the repository root.
- Work is not complete until `./gradlew clean build` passes.
- Do not use `@Suppress` annotations to bypass static-analysis failures. Fix the underlying code instead, including by extracting or splitting code when complexity or size rules fail.
- Do not weaken, disable, or exclude static-analysis rules merely to make the build pass.
