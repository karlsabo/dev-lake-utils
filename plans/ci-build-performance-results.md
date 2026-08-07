# CI build performance results

**Status:** Complete.

Source: [Build run 31217431025](https://github.com/karlsabo/dev-lake-utils/actions/runs/31217431025), commit `9574ea9`, on `macos-latest`.

The macOS job completed successfully in 12m51s. Its retained task report spans 8m42.7s from the first task start to the last task finish.

## First hotspot

The longest material task was `:utilities:linkReleaseFrameworkMacosArm64` at 5m49.1s. It started 2m53.6s into the task timeline and ended at 8m42.6s, effectively defining the end of the Gradle build.

The other release links ran almost entirely in parallel:

| Task | Duration | Start in task timeline | End in task timeline |
|---|---:|---:|---:|
| `:utilities:linkReleaseFrameworkMacosArm64` | 5m49.1s | 2m53.6s | 8m42.6s |
| `:utilities:linkReleaseSharedMacosArm64` | 5m09.4s | 2m54.1s | 8m03.5s |
| `:utilities:linkReleaseStaticMacosArm64` | 5m06.8s | 2m56.7s | 8m03.5s |

The longest debug links also overlapped and took 1m25.8s–1m33.0s. This makes concurrent native release linking the first investigation target, rather than tests or JVM compilation.

## Next experiment

Benchmark a lower Gradle worker limit on macOS to test whether running three memory- and CPU-heavy native release links concurrently is causing contention. Keep the full `clean build --no-build-cache` command and all framework, shared-library, static-library, and native-test outputs unchanged. Retain the change only if the overall macOS job duration improves.
