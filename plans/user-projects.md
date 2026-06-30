# User completed Linear issues by project and milestone

**Goal:** Add a runnable Linear demo that lists all issues completed by one user in a requested timeframe, grouped as Project -> Milestone -> Issue in Markdown.

**Context:**

- Similar demo shape exists at `user-metrics-publisher/src/commonTest/kotlin/com/github/karlsabo/devlake/metrics/UserEpicsWithIssuesDemo.kt`; it parses `--user=...`, calls a project-management API, and prints Markdown-ish grouped output.
- A simpler Linear demo already fetches completed Linear issues for a user, but only supports `--weeks=` and prints a flat list: `user-metrics-publisher/src/commonTest/kotlin/com/github/karlsabo/devlake/metrics/LinearDemo.kt`.
- The backend API already has the right completed-issue call: `ProjectManagementApi.getIssuesResolved(user, startDate, endDate)` in `utilities/src/commonMain/kotlin/com/github/karlsabo/projectmanagement/ProjectManagementApi.kt`, implemented by `LinearRestApi` in `utilities/src/commonMain/kotlin/com/github/karlsabo/linear/LinearRestApi.kt`.
- Linear filtering already uses assignee and `completedAt` bounds in `utilities/src/commonMain/kotlin/com/github/karlsabo/linear/query/LinearIssueFilterBuilder.kt`.
- Current Linear issue selection and model do **not** expose issue project or project milestone, so grouping cannot be implemented correctly without extending them:
  - `utilities/src/commonMain/kotlin/com/github/karlsabo/linear/LinearSelections.kt`
  - `utilities/src/commonMain/kotlin/com/github/karlsabo/linear/Issue.kt`
  - `utilities/src/commonMain/kotlin/com/github/karlsabo/linear/conversion/IssueConversions.kt`
  - `utilities/src/commonMain/kotlin/com/github/karlsabo/projectmanagement/ProjectIssue.kt`
- The user requested the plan artifact at `plans/user-projects.md`, not under the default notebook planning directory.

**Assumptions to confirm before implementation:**

- Date args are date-only UTC: `--start=YYYY-MM-DD --end=YYYY-MM-DD`; the end date should include the full calendar day.
- Issues with no Linear project or no Linear project milestone should not be dropped; they should go under `# No project` and/or `## No milestone`.
- Output should be deterministic: project name ascending, milestone title ascending, issue key ascending.
- Ticket line format should be exactly `* TICKET-ID Ticket title` without URLs or descriptions for the first slice.

## Acceptance tests

1. **Linear issue metadata is available for grouping**
   - Given a Linear issue response for `ENG-101` with project `Project Atlas` and project milestone `MVP`
   - When the response is converted to `ProjectIssue`
   - Then the converted issue exposes project id/name and milestone id/name.

2. **Completed user issues render as Project -> Milestone -> Issue Markdown**
   - Given completed Linear issues for user `usr_123` between `2026-06-01` and `2026-06-30`:
     - `ENG-101 Ship ingestion` in project `Project Atlas`, milestone `MVP`
     - `ENG-102 Fix ingestion bug` in project `Project Atlas`, milestone `MVP`
     - `OPS-7 Rotate tokens` in project `Operations`, milestone `Hardening`
   - When the grouping renderer runs
   - Then it outputs:
     ```markdown
     # Operations
     ## Hardening
     * OPS-7 Rotate tokens

     # Project Atlas
     ## MVP
     * ENG-101 Ship ingestion
     * ENG-102 Fix ingestion bug
     ```

3. **The runnable demo accepts an explicit date range**
   - Given the Linear config exists and user `usr_123` completed `ENG-101` on `2026-06-30T18:00:00Z`
   - When running `./gradlew :user-metrics-publisher:runUserLinearProjectsDemo --args="--user=usr_123 --start=2026-06-01 --end=2026-06-30"`
   - Then `ENG-101` is included in the Markdown output.

4. **Ungrouped Linear issues are still shown**
   - Given completed issue `ENG-103 Cleanup logs` has no project or no milestone
   - When the grouping renderer runs
   - Then the issue appears under placeholder sections instead of being dropped:
     ```markdown
     # No project
     ## No milestone
     * ENG-103 Cleanup logs
     ```

## Stories

### 1. Expose Linear project and milestone metadata on `ProjectIssue`

**Status:** Done

**Acceptance criteria:** Given a Linear issue response for `ENG-101` with project `Project Atlas` and project milestone `MVP`, when the response is converted to `ProjectIssue`, then the converted issue exposes project id/name and milestone id/name.

**Expected edits:**

- `utilities/src/commonMain/kotlin/com/github/karlsabo/projectmanagement/ProjectIssue.kt` — add optional project and milestone fields, likely `projectId`, `projectName`, `milestoneId`, `milestoneName`.
- `utilities/src/commonMain/kotlin/com/github/karlsabo/linear/Issue.kt` — add nullable Linear `project` and `projectMilestone` properties.
- `utilities/src/commonMain/kotlin/com/github/karlsabo/linear/LinearSelections.kt` — request `project { id name }` and `projectMilestone { id name }` in `LINEAR_ISSUE_FIELDS`.
- `utilities/src/commonMain/kotlin/com/github/karlsabo/linear/conversion/IssueConversions.kt` — map the new Linear fields into `ProjectIssue`.
- `utilities/src/commonTest/kotlin/com/github/karlsabo/linear/...` — add a conversion test; avoid a live Linear API test.

**Scope:** Adds metadata only. Does not add the demo or Markdown output.

**Notes:** This is the enabling slice. The current `ProjectIssue` only has issue hierarchy via `parentKey`; Linear project/milestone grouping is not available in the unified issue model.

### 2. Add a pure Markdown grouping renderer

**Status:** Done

**Acceptance criteria:** Given completed issues in projects `Operations` and `Project Atlas` with milestones `Hardening` and `MVP`, when the grouping renderer runs, then it outputs Markdown grouped as `# project name`, `## milestone title`, and `* ticket-id ticket title` in deterministic order.

**Expected edits:**

- Add a small renderer, likely `user-metrics-publisher/src/commonTest/kotlin/com/github/karlsabo/devlake/metrics/UserLinearProjectsMarkdown.kt` or commonMain if reuse is expected.
- Add tests in `user-metrics-publisher/src/commonTest/kotlin/com/github/karlsabo/devlake/metrics/...` using in-memory `ProjectIssue` objects.

**Scope:** Formatting only. Does not call Linear, parse CLI args, or load config.

**Notes:** Keep this pure so the important output behavior is testable without a Linear token. Sort project name, milestone name, then issue key unless implementation confirms a better user-facing order.

### 3. Add the runnable Linear demo with `--user`, `--start`, and `--end`

**Status:** Done

**Acceptance criteria:** Given a Linear config exists and user `usr_123` completed `ENG-101` on `2026-06-30T18:00:00Z`, when running `./gradlew :user-metrics-publisher:runUserLinearProjectsDemo --args="--user=usr_123 --start=2026-06-01 --end=2026-06-30"`, then `ENG-101` is included in the Markdown output.

**Expected edits:**

- Add `user-metrics-publisher/src/commonTest/kotlin/com/github/karlsabo/devlake/metrics/UserLinearProjectsDemo.kt`.
- Update `user-metrics-publisher/user-metrics-publisher.gradle.kts` with `createJvmExecTask(taskName = "runUserLinearProjectsDemo", mainClassName = "com.github.karlsabo.devlake.metrics.UserLinearProjectsDemoKt")`.
- Reuse `LinearRestApi(loadLinearConfig(linearConfigPath))` from `LinearDemo.kt`.
- Reuse `DateTimeFormatting.parseDateOnlyToInstant` from `utilities/src/commonMain/kotlin/com/github/karlsabo/common/datetime/DateTimeFormatting.kt`, but make the end date inclusive for the full date-only day.

**Scope:** Live/demo wiring only. Does not change renderer behavior beyond calling it.

**Notes:** Treat missing `--user`, `--start`, or `--end` as argument errors with clear messages. Automated tests should cover date parsing if extracted to a pure function; do not require a Linear token in CI.

### 4. Keep issues without project or milestone visible

**Acceptance criteria:** Given completed issue `ENG-103 Cleanup logs` has no project or no milestone, when the grouping renderer runs, then it appears under `# No project` and `## No milestone`.

**Expected edits:**

- Update the renderer from story 2.
- Extend renderer tests in `user-metrics-publisher/src/commonTest/kotlin/com/github/karlsabo/devlake/metrics/...`.

**Scope:** Placeholder grouping only. Does not add extra issue details or URLs.

**Notes:** This prevents silent data loss. If the user prefers dropping ungrouped issues, this story should be removed rather than hidden behind an option.

## Suggested sequencing

1. Story 1 first, because grouping is impossible without project/milestone metadata from Linear.
2. Story 2 next, because it locks the exact Markdown contract with cheap tests.
3. Story 3 after that, wiring real Linear calls into the tested renderer.
4. Story 4 can ship with story 2 if small, but keep its acceptance test separate so the behavior is explicit.
