# CI build performance results

**Status:** Waiting for a task-timed CI run.

The Build workflow now runs the existing full command with task timing enabled and uploads:

- `build/reports/task-timing.json`
- `build/reports/task-timing.txt`

After a successful macOS run, download the `task-timing-macos` artifact and record:

- the longest material task;
- its duration and position in the build timeline;
- whether overlap with another long task makes the result ambiguous; and
- the smallest reversible optimization worth testing next.

One run is enough to begin unless the result is inconclusive. No optimization target has been selected yet.
