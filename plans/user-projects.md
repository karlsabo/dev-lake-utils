# User completed Linear issues by project and milestone

**Goal:** Add a runnable Linear demo that lists all issues completed by one user in a requested timeframe, grouped as Project -> Milestone -> Issue in Markdown.

**Context:**

- Similar demo shape exists at `user-metrics-publisher/src/commonTest/kotlin/com/github/karlsabo/devlake/metrics/UserEpicsWithIssuesDemo.kt`; it parses `--user=...`, calls a project-management API, and prints Markdown-ish grouped output.
- A simpler Linear demo already fetches completed Linear issues for a user, but only supports `--weeks=` and prints a flat list: `user-metrics-publisher/src/commonTest/kotlin/com/github/karlsabo/devlake/metrics/LinearDemo.kt`.
- The backend API already has the right completed-issue call: `ProjectManagementApi.getIssuesResolved(user, startDate, endDate)` in `utilities/src/commonMain/kotlin/com/github/karlsabo/projectmanagement/ProjectManagementApi.kt`, implemented by `LinearRestApi` in `utilities/src/commonMain/kotlin/com/github/karlsabo/linear/LinearRestApi.kt`.
- Linear filtering already uses assignee and `completedAt` bounds in `utilities/src/commonMain/kotlin/com/github/karlsabo/linear/query/LinearIssueFilterBuilder.kt`.
- Linear issue selection and model now expose issue project and project milestone metadata for grouping:
  - `utilities/src/commonMain/kotlin/com/github/karlsabo/linear/LinearSelections.kt`
  - `utilities/src/commonMain/kotlin/com/github/karlsabo/linear/Issue.kt`
  - `utilities/src/commonMain/kotlin/com/github/karlsabo/linear/conversion/IssueConversions.kt`
  - `utilities/src/commonMain/kotlin/com/github/karlsabo/projectmanagement/ProjectIssue.kt`
- The user requested the plan artifact at `plans/user-projects.md`, not under the default notebook planning directory.
- Issue done dates can be rendered from `ProjectIssue.completedAt` in `utilities/src/commonMain/kotlin/com/github/karlsabo/projectmanagement/ProjectIssue.kt`; the current renderer at `user-metrics-publisher/src/commonTest/kotlin/com/github/karlsabo/devlake/metrics/UserLinearProjectsMarkdown.kt` drops that date and only prints `* KEY Title`.
- Project completion cannot be inferred accurately from the user's completed issues alone. Current Linear project metadata is only id/name in `utilities/src/commonMain/kotlin/com/github/karlsabo/linear/IssueProject.kt`, selected in `utilities/src/commonMain/kotlin/com/github/karlsabo/linear/LinearSelections.kt`, and mapped through `utilities/src/commonMain/kotlin/com/github/karlsabo/linear/conversion/IssueConversions.kt` into `ProjectIssue.projectId/projectName`.

**Assumptions to confirm before implementation:**

- Date args are date-only UTC: `--start=YYYY-MM-DD --end=YYYY-MM-DD`; the end date should include the full calendar day.
- Issues with no Linear project or no Linear project milestone should not be dropped; they should go under `# No project` and/or `## No milestone`.
- Issue done dates should use the UTC date portion of `completedAt` and render as `YYYY-MM-DD`.
- Project final-state dates should come from Linear Project metadata, not from the latest issue completed by this user. Projects with no final-state date should render `* Done: in-progress`.
- Issue done-date subbullets should render `* Done: in-progress` when `completedAt` is missing.
- Project ordering should be deterministic: completed projects by project done date ascending, in-progress/no-date projects last, `# No project` last within that group, then project name ascending. Milestones and issues keep deterministic name/key ordering inside each project.
- Ticket line format should be exactly `* TICKET-ID Ticket title` without URLs or descriptions for the first slice.

## Decisions

- Project final-state dates come from Linear project metadata, not from the latest completed issue in this user/date-range result set.
- For projects, “done” means moved to a final state, including canceled projects.
- Render the label as `Done` everywhere, for both issues and projects.
- Project section status uses Option A shape:
  ```markdown
  # Project Atlas
  * Done: 2026-06-28
  ## MVP
  ```
- Projects with no done date render `* Done: in-progress`.
- Issues with no `completedAt` render `* Done: in-progress`.
- The literal fallback text is lowercase `in-progress` everywhere.
- In-progress/no-date projects sort last after all dated/done projects.
- `# No project` sections sort last.
- `# No project` does not render a project-level `Done` line because it is not a real project.
- Project done/final-state date is project metadata. If the implementation carries that metadata on each `ProjectIssue`, it should treat those duplicated fields as transport details, not as separate user-visible sources of truth.
- Project final-state date can be outside the requested issue date range; the date range filters issues, while the project line always shows the true project final-state date.

## Open questions for user

None currently.

## Acceptance tests

1. **Linear issue metadata is available for grouping**
   - Given a Linear issue response for `ENG-101` with project `Project Atlas` and project milestone `MVP`
   - When the response is converted to `ProjectIssue`
   - Then the converted issue exposes project id/name and milestone id/name.

2. **Completed user issues render as Project -> Milestone -> Issue Markdown**
   - Given completed Linear issues for user `usr_123` between `2026-06-01` and `2026-06-30`:
     - `ENG-101 Ship ingestion` in project `Project Atlas`, milestone `MVP`, completed at `2026-06-15T20:12:00Z`
     - `ENG-102 Fix ingestion bug` in project `Project Atlas`, milestone `MVP`, completed at `2026-06-16T09:30:00Z`
     - `OPS-7 Rotate tokens` in project `Operations`, milestone `Hardening`, completed at `2026-06-12T14:00:00Z`
   - When the grouping renderer runs
   - Then it outputs:
     ```markdown
     # Operations
     ## Hardening
     * OPS-7 Rotate tokens
       * Done: 2026-06-12

     # Project Atlas
     ## MVP
     * ENG-101 Ship ingestion
       * Done: 2026-06-15
     * ENG-102 Fix ingestion bug
       * Done: 2026-06-16
     ```

3. **The runnable demo accepts an explicit date range**
   - Given the Linear config exists and user `usr_123` completed `ENG-101` on `2026-06-30T18:00:00Z`
   - When running `./gradlew :user-metrics-publisher:runUserLinearProjectsDemo --args="--user=usr_123 --start=2026-06-01 --end=2026-06-30"`
   - Then `ENG-101` is included in the Markdown output.

4. **Ungrouped Linear issues are still shown**
   - Given completed issue `ENG-103 Cleanup logs` has no project or no milestone and completed at `2026-06-20T11:45:00Z`
   - When the grouping renderer runs
   - Then the issue appears under placeholder sections instead of being dropped:
     ```markdown
     # No project
     ## No milestone
     * ENG-103 Cleanup logs
       * Done: 2026-06-20
     ```

5. **Issue lines show done dates as subbullets**
   - Given completed Linear issue `ENG-101 Ship ingestion` has `completedAt` of `2026-06-15T20:12:00Z`
   - When the grouping renderer runs
   - Then the issue line includes a nested done-date bullet:
     ```markdown
     * ENG-101 Ship ingestion
       * Done: 2026-06-15
     ```

6. **Project headings show final-state date or in-progress status**
   - Given `Project Atlas` has Linear project final-state date `2026-06-28T14:00:00Z`
   - And `Operations` has no Linear project final-state date
   - When the grouping renderer runs
   - Then `Project Atlas` shows `* Done: 2026-06-28` below its heading
   - And `Operations` shows `* Done: in-progress` below its heading.

7. **Projects sort by done date ascending**
   - Given `Project Beta` is done on `2026-06-01`, `Project Alpha` is done on `2026-06-10`, and `Project Gamma` is in progress
   - When the grouping renderer runs
   - Then the project sections appear in this order: `Project Beta`, `Project Alpha`, `Project Gamma`.

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

**Status:** Done

**Acceptance criteria:** Given completed issue `ENG-103 Cleanup logs` has no project or no milestone, when the grouping renderer runs, then it appears under `# No project` and `## No milestone`.

**Expected edits:**

- Update the renderer from story 2.
- Extend renderer tests in `user-metrics-publisher/src/commonTest/kotlin/com/github/karlsabo/devlake/metrics/...`.

**Scope:** Placeholder grouping only. Does not add extra issue details or URLs.

**Notes:** This prevents silent data loss. If the user prefers dropping ungrouped issues, this story should be removed rather than hidden behind an option.

### 5. Add issue done-date subbullets

**Status:** Done

**Acceptance criteria:** Given completed Linear issue `ENG-101 Ship ingestion` has `completedAt` of `2026-06-15T20:12:00Z`, when the grouping renderer runs, then the issue line includes a nested bullet `* Done: 2026-06-15`.

**Expected edits:**

- `user-metrics-publisher/src/commonTest/kotlin/com/github/karlsabo/devlake/metrics/UserLinearProjectsMarkdown.kt` — include a nested `Done: YYYY-MM-DD` line under every issue, derived from `ProjectIssue.completedAt`.
- `user-metrics-publisher/src/commonTest/kotlin/com/github/karlsabo/devlake/metrics/UserLinearProjectsMarkdownTest.kt` — update existing renderer expectations and add a focused date-format test with a fixed `Instant`.

**Scope:** Issue date rendering only. Does not add project done status or change project sort order.

**Notes:** `ProjectIssue.completedAt` already exists at `utilities/src/commonMain/kotlin/com/github/karlsabo/projectmanagement/ProjectIssue.kt`; keep date formatting pure and token-free in renderer tests. If an issue has no `completedAt`, render `* Done: in-progress`.

### 6. Add project final-state date or in-progress subbullets

**Status:** Done

**Acceptance criteria:** Given `Project Atlas` has Linear project final-state date `2026-06-28T14:00:00Z` and `Operations` has no Linear project final-state date, when the grouping renderer runs, then `Project Atlas` shows `* Done: 2026-06-28` below its heading and `Operations` shows `* Done: in-progress` below its heading.

**Expected edits:**

- `utilities/src/commonMain/kotlin/com/github/karlsabo/projectmanagement/ProjectIssue.kt` — add optional project final-state/status fields, likely `projectCompletedAt: Instant?`, `projectCanceledAt: Instant?`, and `projectStatus: String?`, or one normalized `projectFinalizedAt: Instant?` if the Linear mapping can own the semantics cleanly.
- `utilities/src/commonMain/kotlin/com/github/karlsabo/linear/IssueProject.kt` — add nullable Linear Project final-state/status fields supported by the Linear schema.
- `utilities/src/commonMain/kotlin/com/github/karlsabo/linear/LinearSelections.kt` — request those project final-state/status fields inside the existing `project { ... }` selection.
- `utilities/src/commonMain/kotlin/com/github/karlsabo/linear/conversion/IssueConversions.kt` — map Linear project metadata into `ProjectIssue`.
- `utilities/src/commonTest/kotlin/com/github/karlsabo/linear/conversion/IssueConversionsTest.kt` — cover project completion/status mapping without a live Linear token.
- `user-metrics-publisher/src/commonTest/kotlin/com/github/karlsabo/devlake/metrics/UserLinearProjectsMarkdown.kt` — render one `Done: YYYY-MM-DD` or `Done: in-progress` subbullet per project section.
- `user-metrics-publisher/src/commonTest/kotlin/com/github/karlsabo/devlake/metrics/UserLinearProjectsMarkdownTest.kt` — cover done and in-progress project sections.

**Scope:** Project final-state/status metadata and rendering only. Does not change project sort order.

**Notes:** Do not derive project completion from the user's completed issues; that would produce false project done dates because `UserLinearProjectsDemo.kt` only fetches issues resolved by one user in one date range. Current project metadata is only id/name in `IssueProject.kt`, so this story extends the data model before changing the renderer. For projects, canceled counts as done/final and should render the date the project moved to that final state. The project final-state date may be outside the issue date range and should still render. `# No project` does not render a project-level `Done` line.

### 7. Sort projects by done date ascending

**Status:** Done

**Acceptance criteria:** Given `Project Beta` is done on `2026-06-01`, `Project Alpha` is done on `2026-06-10`, `Project Gamma` is in progress, and one issue has no project, when the grouping renderer runs, then the project sections appear in this order: `Project Beta`, `Project Alpha`, `Project Gamma`, `No project`.

**Expected edits:**

- `user-metrics-publisher/src/commonTest/kotlin/com/github/karlsabo/devlake/metrics/UserLinearProjectsMarkdown.kt` — replace project-name-first ordering with project completion date ascending, in-progress/no-date projects last, then project name as the tiebreaker.
- `user-metrics-publisher/src/commonTest/kotlin/com/github/karlsabo/devlake/metrics/UserLinearProjectsMarkdownTest.kt` — add a focused ordering test and update any expectations that assume project-name ordering.

**Scope:** Project ordering only. Does not add new metadata fields; depends on story 6.

**Notes:** Current renderer sorts by `projectName`, then `milestoneName`, then `key` in `UserLinearProjectsMarkdown.kt`. Keep milestone and issue ordering deterministic inside each project.

## Suggested sequencing

1. Story 1 first, because grouping is impossible without project/milestone metadata from Linear.
2. Story 2 next, because it locks the exact Markdown contract with cheap tests.
3. Story 3 after that, wiring real Linear calls into the tested renderer.
4. Story 4 can ship with story 2 if small, but keep its acceptance test separate so the behavior is explicit.
5. Story 5 can ship next; it only uses existing `ProjectIssue.completedAt` data.
6. Story 6 must happen before story 7, because true project done dates require new project metadata.
7. Story 7 last, after project done dates/statuses exist.
