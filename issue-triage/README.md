# Issue triage workbook

`issue-triage` queries a configurable Linear scope, assesses each active issue with a read-only `pi` invocation, and writes an editable ODS workbook. The scope is the union of the selected project and label within one team; either selector may be omitted.

The command has no built-in team, project, label, repository, or output paths. `--output` and at least one `--project` or `--label` are required. Repository roots are repeatable and the first root is the `pi` working directory. Absolute paths are recommended; a leading `~` is expanded to the current user's home directory.

## Prerequisites

- JDK 17
- `pi` on `PATH`
- A Linear configuration file containing a path to the API-key file, for example:

```json
{
  "tokenPath": "/absolute/path/to/linear-secret.json"
}
```

The secret file has this shape:

```json
{
  "linearApiKey": "lin_api_replace_me"
}
```

Keep both files outside the repository and restrict access to them.

## ABC example

From the repository root:

```shell
./gradlew :issue-triage:run --args="\
--linear-config ~/.config/dev-lake-utils/linear.json \
--team ABC \
--project 'ABC KTLO' \
--label abc-ktlo \
--repository-root ~/git \
--model openai-codex/gpt-5.6-sol \
--thinking medium \
--output ~/notebook/llm-planning/issue-triage/issue-triage.ods"
```

This queries the union of the `ABC KTLO` project and `abc-ktlo` label for team `ABC`. Existing workbooks are refreshed in place; use a different `--output` to create a separate source of truth.

## Another scope

No source change is needed to generate a workbook for another team or repository set:

```shell
./gradlew :issue-triage:run --args="\
--linear-config ~/.config/dev-lake-utils/linear.json \
--team PLAT \
--project 'Platform Reliability' \
--label reliability-review \
--repository-root ~/src/platform-api \
--repository-root ~/src/platform-infra \
--model example/custom-model \
--thinking high \
--output ~/reports/platform-reliability.ods"
```

## Arguments

| Argument                           | Required         | Description                                                                                 |
|------------------------------------|------------------|---------------------------------------------------------------------------------------------|
| `--linear-config <path>`           | Yes              | Linear configuration JSON.                                                                  |
| `--team <key>`                     | Yes              | Linear team key used in every scope query.                                                  |
| `--project <name>`                 | Project or label | Linear project display name.                                                                |
| `--label <name>`                   | Project or label | Linear label name.                                                                          |
| `--repository-root <path>`         | Yes, repeatable  | Directory available to the assessor. The first is the `pi` working directory.               |
| `--output <path.ods>`              | Yes              | Workbook to create or atomically refresh. Parent directories are created.                   |
| `--model <provider/model>`         | No               | Pi model; defaults to `openai-codex/gpt-5.6-sol`.                                           |
| `--thinking <level>`               | No               | `off`, `minimal`, `low`, `medium`, `high`, `xhigh`, or `max`; defaults to `medium`.         |
| `--assessment-concurrency <count>` | No               | Maximum concurrent pi processes; defaults to `4`.                                           |
| `--retriage <ticket> or all`       | No, repeatable   | Explicitly replace selected existing assessments. `all` cannot be combined with ticket IDs. |

Each assessment runs in an ephemeral pi session with only `read`, `grep`, `find`, and `ls`. A mandatory pi extension confines those tools to the configured repository roots, including resolving symlinks before access. Linear issue text and comments are passed as untrusted data. The workbook records the configured scope, model, thinking level, and prompt version on `Summary`. If an assessment still fails after one retry, successful rows and an `Error` row are saved before the command exits nonzero.

## Run progress

The command writes INFO progress to the console while it fetches and merges the Linear inventory, assesses queued tickets, and saves the workbook. Assessment messages identify only the ticket, attempt, elapsed time, terminal outcome, and completed/total count. Retries are announced before the next pi attempt, and every normal run ends with success and failure totals. Prompts, comments, credentials, and pi output are never included in progress messages.

The command atomically saves the merged inventory before assessment starts and again after each terminal assessment. If a run is interrupted, the last valid workbook retains completed assessments and blank assessment cells identify unfinished rows. Restarting normally skips successful rows and retries unfinished or `Error` rows. Restarting with `--retriage` preserves force semantics and reassesses every explicitly requested target.

## Workbook editing and smoke test

LibreOffice Calc is the supported editor. Other ODS-compatible editors may work, but must preserve ODS links, filters, cell types, styles, and view settings when saving. Avoid converting the workbook to XLSX or CSV because those formats do not preserve the refresh metadata contract.

Automated tests inspect and round-trip the ODS package without requiring LibreOffice. Before releasing spreadsheet-format changes, run this manual smoke test on a machine with LibreOffice installed:

1. Generate a workbook with at least two assessed issues and one `Error` issue.
2. Open the `.ods` file in LibreOffice Calc and select `Ranking`.
3. Confirm the header remains visible while scrolling and every header has an autofilter control.
4. Follow a ticket ID link and confirm it opens the corresponding Linear issue.
5. Confirm difficulty values are numeric, rationale text wraps, and columns are readable without initial resizing.
6. Confirm rows are ordered by ascending difficulty and then numeric ticket suffix, with blank or error rows last.
7. Edit an assessment cell, save as ODS, rerun the command without `--retriage`, and confirm the edit remains attached to the same ticket.
