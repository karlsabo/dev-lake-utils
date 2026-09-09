# Incremental issue triage with observable progress

**Goal:** Make `issue-triage` report actionable run progress and durably preserve completed assessments so a restarted run continues with only unfinished work.

## Context

- The affected command is:

  ```shell
  ./gradlew issue-triage:run --info --stacktrace --args="--linear-config '/Users/karl.sabo/Library/Application Support/DevLakeUtils/linear-config.json' --team IAM --project 'IAM KTLO' --label iam-ktlo --repository-root /Users/karl.sabo/Klaviyo/Repos --output /Users/karl.sabo/karl-backup/notebook/llm-planning/issue-triage/issue-triage.ods"
  ```

- Repository: `/Users/karl.sabo/git/dev-lake-utils`.
- The CLI currently emits only a final export message. `IssueTriageCommand.run` fetches Linear issues, merges the prior workbook, concurrently assesses every missing/error/retriage row, waits for all assessments, and writes the workbook once at the end (`issue-triage/src/jvmMain/kotlin/com/github/karlsabo/devlake/triage/IssueTriageCli.kt`). A process crash or interruption before that final write loses every assessment completed during the run.
- Assessment concurrency defaults to four. `assessRows` uses coroutines plus a semaphore, but exposes no lifecycle events or intermediate results to persistence (`issue-triage/src/jvmMain/kotlin/com/github/karlsabo/devlake/triage/IssueTriageCli.kt`, `issue-triage/src/jvmMain/kotlin/com/github/karlsabo/devlake/triage/TriageArguments.kt`).
- Each pi assessment may run for up to ten minutes and is retried once, but attempts, retries, elapsed time, process failures, and timeouts are not reported while work is underway (`issue-triage/src/jvmMain/kotlin/com/github/karlsabo/devlake/triage/assessment/PiIssueAssessor.kt`).
- Existing workbook merge behavior already supplies most resume semantics: assessments are preserved by immutable Linear ID; rows without assessments and rows in `Error` state are selected for assessment on the next run (`issue-triage/src/jvmMain/kotlin/com/github/karlsabo/devlake/triage/IssueTriageCli.kt`, `issue-triage/src/jvmMain/kotlin/com/github/karlsabo/devlake/triage/spreadsheet/OdsTriageWorkbook.kt`).
- The missing piece is durable intermediate state. The workbook writer currently rejects an active row without an assessment, so it cannot yet represent pending work (`issue-triage/src/jvmMain/kotlin/com/github/karlsabo/devlake/triage/spreadsheet/OdsTriageWorkbook.kt`).
- Workbook replacement is already atomic: a complete temporary ODS is validated and atomically moved over the target. Reusing this mechanism means interruption during a checkpoint leaves the preceding valid checkpoint intact (`issue-triage/src/jvmMain/kotlin/com/github/karlsabo/devlake/triage/spreadsheet/OdsTriageWorkbook.kt`).
- Existing tests establish final-write, retry, cancellation, merge, stale-assessment, manual-edit, and atomic-file behavior in:
  - `issue-triage/src/jvmTest/kotlin/com/github/karlsabo/devlake/triage/IssueTriageCommandTest.kt`
  - `issue-triage/src/jvmTest/kotlin/com/github/karlsabo/devlake/triage/PartialAssessmentFailureTest.kt`
  - `issue-triage/src/jvmTest/kotlin/com/github/karlsabo/devlake/triage/assessment/PiIssueAssessorTest.kt`
  - `issue-triage/src/jvmTest/kotlin/com/github/karlsabo/devlake/triage/spreadsheet/OdsTriageWorkbookTest.kt`
- Required repository validation after implementation: `./gradlew clean build` from `/Users/karl.sabo/git/dev-lake-utils`.
- Accepted implementation decisions:
  - Write the ODS after preparing the merged inventory and after every completed assessment. This favors recoverability over minimizing disk I/O.
  - Use the output ODS as the only checkpoint rather than introducing a sidecar state file.
  - Represent pending active rows with blank assessment cells; do not add a persisted `Pending` status.
  - Emit concise INFO-level lifecycle progress to the console by default: scope fetch and merge counts, queue size, per-ticket starts, retries, terminal outcomes with elapsed time and completed/total counts, durable saves, and the final summary. Never log credentials, prompts, comments, or complete pi output; reserve stack traces and sanitized stderr details for failures/debugging.
  - On restart, skip successful assessments and retry missing or `Error` rows.
  - Preserve force semantics for `--retriage`: restarting with that option reassesses every explicitly requested target, including a target completed by an interrupted prior invocation.

## Acceptance Tests

1. **Observable assessment progress**
   - Given three active IAM issues where two need assessment and assessment concurrency is two, when issue triage runs, then console output identifies each major phase and each ticket's start and terminal outcome, reports completed/total progress and elapsed time, and ends with a run summary without exposing issue comments, prompts, credentials, or full model output.

2. **Resume from durable per-assessment checkpoints**
   - Given three IAM issues needing assessment, when two assessments complete and the run is interrupted before the third completes, then the output ODS remains valid with those two completed assessments; when the same command is restarted, only the unfinished issue is sent to pi and the final workbook contains all three assessments.

## Stories

### 1. Show actionable triage progress while the command runs

**Acceptance criteria:** Given `IAM-101`, `IAM-102`, and `IAM-103`, where `IAM-101` and `IAM-102` require assessment, when issue triage runs with concurrency two, then the console reports phase transitions, queue size, each ticket start, retry when applicable, each terminal result with elapsed time and completed/total progress, durable save activity, and a final success/failure summary, without printing secrets or untrusted issue/model payloads.

**Expected edits:**

- `issue-triage/src/jvmMain/kotlin/com/github/karlsabo/devlake/triage/IssueTriageCli.kt`
- `issue-triage/src/jvmMain/kotlin/com/github/karlsabo/devlake/triage/assessment/PiIssueAssessor.kt`
- `issue-triage/src/jvmMain/resources/log4j2.xml` (new, if the existing logging stack is used)
- `issue-triage/src/jvmTest/kotlin/com/github/karlsabo/devlake/triage/IssueTriageCommandTest.kt`
- `issue-triage/src/jvmTest/kotlin/com/github/karlsabo/devlake/triage/assessment/PiIssueAssessorTest.kt`
- `issue-triage/README.md`

**Scope:** In: human-readable phase, ticket, retry, duration, progress, checkpoint, and summary events. Out: structured JSON logging, persistent log files, prompt/stdout logging, metrics, tracing, or changing assessment behavior.

**Notes:** Introduce injectable progress/reporting boundaries rather than scattering direct `println` calls through orchestration and assessor logic. This keeps output assertions deterministic and prevents tests from depending on a logging backend. Include ticket identifier and attempt number, but sanitize failure detail and never include Linear config contents, comments, prompts, or successful pi stdout. This story may define checkpoint event names that Story 2 emits, but it must not implement fake progress ahead of actual durable writes.

### 2. Persist completed assessments and resume unfinished work

**Acceptance criteria:** Given `IAM-101`, `IAM-102`, and `IAM-103` all need assessment, when `IAM-101` and `IAM-102` complete and the run is interrupted while `IAM-103` is unfinished, then the ODS is a valid checkpoint containing the two completed assessments; when the same command restarts, only `IAM-103` is assessed and the final ODS contains all three completed assessments.

**Expected edits:**

- `issue-triage/src/jvmMain/kotlin/com/github/karlsabo/devlake/triage/IssueTriageCli.kt`
- `issue-triage/src/jvmMain/kotlin/com/github/karlsabo/devlake/triage/spreadsheet/OdsTriageWorkbook.kt`
- `issue-triage/src/jvmTest/kotlin/com/github/karlsabo/devlake/triage/IssueTriageCommandTest.kt`
- `issue-triage/src/jvmTest/kotlin/com/github/karlsabo/devlake/triage/PartialAssessmentFailureTest.kt`
- `issue-triage/src/jvmTest/kotlin/com/github/karlsabo/devlake/triage/spreadsheet/OdsTriageWorkbookTest.kt`
- `issue-triage/README.md`

**Scope:** In: valid ODS representation of pending rows, an initial merged-inventory checkpoint, serialized atomic checkpoints as concurrent assessments finish, preservation of successful and failed outcomes, restart skipping of successful rows, and continued retry of pending/error rows. Out: resuming in-flight pi sessions, avoiding a fresh Linear query, cross-process locking, concurrent manual edits while a run is active, and special resume semantics for explicit `--retriage`.

**Notes:** Keep concurrent pi work, but funnel outcomes through one orchestration path that updates an immutable/current inventory snapshot and invokes the workbook writer serially. Never allow concurrent ODS writes. Continue using temporary-file validation plus atomic replacement so interruption during a write preserves the prior checkpoint. Relax active-row serialization only enough to write blank assessment cells for pending rows; existing workbook parsing already maps all-blank assessment cells to `null`. Update the existing cancellation expectation, which currently asserts no output is saved, to assert that completed work remains durable. On a normal run, final output and exit-code behavior should remain unchanged.

## Sequencing and Trade-offs

1. Implement Story 1 first to expose the actual execution path and provide diagnostics while changing checkpoint behavior.
2. Implement Story 2 using those progress events around real durable writes.
3. Per-assessment ODS writes favor recoverability and simplicity over minimizing disk I/O. If measured workbook-write time becomes material, a later story can add a configurable batch/time cadence without changing resume semantics.
4. The ODS remains the sole source of truth. This minimizes dynamic coupling and makes recovery inspectable, at the cost of allowing temporarily blank assessment rows during an active/interrupted run.
