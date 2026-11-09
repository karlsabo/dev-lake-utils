# Eng Hub

Desktop Compose app for watching GitHub pull requests and notifications, then taking a few common actions without living in the browser.

## What It Currently Does

The app has four panes:

- `Pull Requests`
- `Notifications`
- `Worktrees`
- `Settings`

### Pull Requests pane

The pull request pane polls GitHub on a timer and shows open pull requests for the configured `gitHubAuthor` across the configured `organizationIds`.

Each item currently shows:

- PR number and title
- repository name
- draft state
- CI status and a summary like `3/5 passed, 1 failed`
- review summary like `waiting on 1 reviewer` or `2/3 approved`

Each pull request currently supports:

- `Open in Browser`: opens the PR URL with the OS default browser
- `Setup`: ensures the repo exists locally, creates a git worktree for the PR branch, and runs any configured setup commands for that repo

### Notifications pane

The notifications pane polls GitHub notifications on the same timer and shows notifications that remain after background processing.

Each notification currently shows:

- title
- repository
- notification reason
- subject type

Each notification can currently support:

- `Open`: opens the notification target in the browser when a browser URL is available
- `Setup`: for PR notifications with a resolvable head branch, creates or reuses the local worktree setup
- `Approve`: submits an approval review for the PR, marks the notification done, and removes it from the list
- `Review`: opens a dialog to submit one of:
    - comment
    - request changes
    - approve
- `Done`: marks the notification as done
- `Unsubscribe`: unsubscribes from the thread and also marks it done

### Background processing behavior

Notifications are not shown completely raw. Before the UI displays them, the app runs them through `GitHubNotificationService`.

Current behavior includes:

- merged or closed pull request notifications are marked done automatically
- some PRs are auto-approved and marked done when the title starts with `Updating appfile` and also contains either `demo` or `to dev`
- notifications already marked done by that processing are filtered out of the visible list

This is important because the app is already opinionated, not just a passive dashboard.

## Local Setup

The app stores two config files under the shared `DevLakeUtils` application directory. On first launch, Settings opens with defaults so these files can be created through the application.

Config directory by OS:

- macOS: `~/Library/Application Support/DevLakeUtils`
- Linux: `~/.local/share/DevLakeUtils`
- Windows: `%APPDATA%/DevLakeUtils`

Configuration files:

- `github-config.json`
- `eng-hub-config.json`

### `github-config.json`

This points to a second file that contains the actual GitHub token.

Example:

```json
{
  "tokenPath": "/absolute/path/to/github-token.json"
}
```

Example secret file:

```json
{
  "githubToken": "ghp_xxx"
}
```

### `eng-hub-config.json`

Example:

```json
{
  "organizationIds": [
    "example-org"
  ],
  "pollIntervalMs": 600000,
  "worktreePollIntervalMs": 120000,
  "repositoriesBaseDir": "/Users/you/git",
  "gitHubAuthor": "your-github-login",
  "planningMarkdownDir": "/Users/you/notebook/llm-planning",
  "llmTemplateValues": {
    "ALERT_TRIAGE_WHERE_TO_LOOK": "- Incident management: inspect the active incident.\n- Observability: compare service health with the alert window."
  },
  "localRepositories": [
    {
      "path": "/Users/you/git/example-repo",
      "setupCommands": [
        "direnv allow",
        "idea ./"
      ]
    }
  ],
  "setupShell": "/bin/zsh"
}
```

Field meanings:

- `organizationIds`: orgs searched for authored PRs
- `pollIntervalMs`: refresh interval for pull requests and notifications
- `worktreePollIntervalMs`: refresh interval for local worktree discovery and status
- `repositoriesBaseDir`: where repos are cloned if missing
- `gitHubAuthor`: GitHub login used for the pull request query
- `planningMarkdownDir`: absolute directory used when syncing LLM markdown templates that contain `${PLANNING_MARKDOWN_DIR}`
- `llmTemplateValues`: Markdown fragments for Eng Hub's known template keys, currently `ALERT_TRIAGE_WHERE_TO_LOOK`. Known tokens are substituted while syncing skill Markdown, nested Markdown references, guidelines, and notes; unrelated tokens such as shell variables remain unchanged. Values are inserted without reformatting. Non-Markdown files are copied unchanged. `PLANNING_MARKDOWN_DIR` is reserved for the dedicated setting above and cannot be overridden through this map. If a known template value is absent or blank, synchronization logs an error and installs corrective guidance in place of that token; configure the named value in Eng Hub Settings and rerun `syncLlmFiles`.
- `localRepositories`: local repository roots managed by Eng Hub; each entry has an absolute `path` and optional `setupCommands`. Duplicate checks resolve separators plus lexical `.` and `..` segments without accessing the filesystem, while the configured spelling is preserved. Path identity is case-insensitive on Windows and macOS and case-sensitive on Linux. macOS uses the conservative case-insensitive identity because detecting a case-sensitive volume would require filesystem access that Settings intentionally avoids.
- `setupShell`: login shell used to run setup commands

Settings provides fixed fields for templates used by bundled skills under **LLM skill templates**. Editing **Alert triage: Where to look** saves multiline Markdown to `llmTemplateValues.ALERT_TRIAGE_WHERE_TO_LOOK` after 750 ms, or when leaving Settings or exiting. Saving does not reinstall skills; rerun `syncLlmFiles` to apply the new guidance.

Synchronization installs the published `eh-alert-triage` skill in each selected tool target. After that replacement is copied successfully, it removes only the retired `wip-eh-alert-triage` directory from the same target; destination-only skills are preserved.

Setup commands run from the created worktree directory. Before the shell runs, Eng Hub expands these literal placeholders:

- `$root-repo-dir`: the configured local repository root path
- `$worktree-dir`: the created or reused worktree path

Example setup command:

```json
"setupCommands": [
  "cp \"$root-repo-dir/.env.example\" \"$worktree-dir/.env\"",
  "direnv allow"
]
```

The same placeholders can pass both paths to setup tools, such as the IntelliJ IDEA `.idea` seeding helper. Run it
before `idea ./` when you want reusable project settings seeded before IntelliJ opens the worktree:

```json
"setupCommands": [
  "kotlin /Users/you/git/dev-lake-utils/scripts/idea-tool.kts \"$root-repo-dir/.idea\" \"$worktree-dir/.idea\"",
  "idea ./"
]
```

Quote placeholders the same way you would quote normal paths in shell commands.

### IntelliJ IDEA `.idea` seeding helper

To manually seed a worktree's `.idea` directory from the root checkout template:

```bash
kotlin /Users/you/git/dev-lake-utils/scripts/idea-tool.kts /Users/you/git/example-repo/.idea /Users/you/git/example-repo-worktree/.idea
```

The helper is target-first: it copies reusable project-template files from the source `.idea` tree only when the
corresponding target path is missing. Existing target files and symlinks are never overwritten, merged, parsed,
formatted, validated, or repaired. Existing target directories are traversed so missing child files can still be seeded.
This makes it safe to run before every `idea ./`, but it also means established worktree IDE state always wins.

For copied regular UTF-8 text files, the helper rewrites exact absolute root-checkout path strings to the worktree path.
It does not rewrite binary files or symlink targets.

`workspace.xml` is seeded only when the target `workspace.xml` is missing. The helper parses the source file, removes
worktree-local or personal components at a high level, such as project IDs, changelists/tasks, VCS/Git/GitHub PR state,
window/tool state, recents, debugger settings, indexing state, and similar session metadata, then writes the sanitized
copy. Useful non-denied components remain, including Go environment entries such as `GOPRIVATE`, workspace run
configurations, Go SDK/library settings, and similar project setup. If the source has a `VgoProject` component, the
helper also ensures `GOFLAGS` includes `-mod=readonly`. If the target `workspace.xml` already exists, it is left exactly
as-is, even if malformed.

The helper never copies `shelf/` or `usage.statistics.xml`. Shelves can contain actual code patches from another
checkout, and usage statistics are not project setup.

Datasource state is treated as seedable project setup: `dataSources.xml`, `dataSources.local.xml`, and files under
`dataSources/` copy when the matching target paths are missing, with the same exact root-path rewriting for regular
UTF-8 text files. Existing target datasource files and cache files are preserved.

There is no `--force` mode. To reseed a specific `.idea` path, close IntelliJ, delete only that target path, and rerun
the helper. For example:

```bash
WORKTREE=/Users/you/git/example-repo-worktree
rm -f "$WORKTREE/.idea/workspace.xml"
rm -f "$WORKTREE/.idea/dataSources.xml"
rm -f "$WORKTREE/.idea/dataSources.local.xml"
rm -rf "$WORKTREE/.idea/dataSources"
```

Only delete paths you are willing to replace from the source checkout. Do not delete `shelf/` if you need shelves from
that worktree; the helper will not reseed shelves because they are never copied.

### First run behavior

If `eng-hub-config.json` is missing, the app opens Settings with default draft values. Launching alone does not create the file; valid edits are auto-persisted without a save action. Other panes remain visible and unavailable panes explain which settings they require.

If `eng-hub-config.json` is malformed, Eng Hub tries `eng-hub-config.json.bak`. A valid backup opens in the normal Settings pane; if neither file is valid, the app opens Settings with default drafts rather than attempting field-level recovery.

## Launching

Run from the repo root:

```bash
./gradlew :eng-hub:run
```

Other useful run tasks that exist today:

```bash
./gradlew :eng-hub:runRelease
./gradlew :eng-hub:runDistributable
./gradlew :eng-hub:hotRunJvm
./gradlew :eng-hub:syncLlmFiles
./gradlew :eng-hub:implementReviewChat
```

## Terminal chat agent

The `terminalChat` task runs a separate JVM terminal program with the framework Skills `read`, `write`, and `bash`
tools and the local `QWEN_3_8_27B` model from LM Studio. It does not start the Compose UI. An explicit `--mcp-config`
argument adds tools from a configured `browser` MCP server to the same session.

Start LM Studio and serve the model id `qwen/qwen3.8-27b` at `http://localhost:1234/v1`, then launch from the
repository root:

```bash
./gradlew :eng-hub:terminalChat
```

The default LM Studio base URL is `http://localhost:1234/v1`. To use a server on another host or port, pass
`--lm-studio-base-url` (the `--lm-studio-base-url=URL` form also works):

```bash
./gradlew :eng-hub:terminalChat --args="--lm-studio-base-url http://192.168.1.50:1234/v1"
```

If the server requires API authentication, pass a file containing only the raw token. Surrounding whitespace and the
trailing newline are trimmed, and the token is not printed. If this option is omitted, the existing `lm-studio` token is
used for servers without authentication.

```bash
./gradlew :eng-hub:terminalChat \
  --args="--lm-studio-base-url http://192.168.1.50:1234/v1 --lm-studio-api-key-file /secure/path/lm-studio-api-key"
```

The `--lm-studio-api-key-file=/secure/path/lm-studio-api-key` form also works. Keep the key file outside the repository
and do not commit it.

Relative file paths resolve from the terminal's launch working directory; the JavaExec task sets that to the
repository root explicitly. Ask about any local text file, ask it to write one, ask it to run a command, then ask a
follow-up in the same session. Quit with `/exit` or end-of-input (Ctrl-D).

Example conversation:

```text
you> Read /tmp/release-notes.txt and tell me the release day.
agent> The file says the release is on Friday.
you> What day did I ask about?
agent> Friday.
you> Write summary.txt containing Release on Friday
agent> Created summary.txt.
you> Count the lines in /tmp/release-notes.txt using bash
agent> 1
you> /exit
```

The program retries transient LM Studio HTTP failures. It accepts arbitrary prompts and file paths; no fixture
file is bundled or required at startup.

### Browser MCP tools

Pass `--mcp-config` with a framework `McpConfig` JSON file to add browser tools alongside Skills `read`, `write`,
and `bash`:

```bash
./gradlew :eng-hub:terminalChat --args="--mcp-config /path/to/mcp.json"
```

Example config shape with synthetic values:

```json
{
  "mcpServers": {
    "browser": {
      "command": "example-browser-mcp",
      "args": ["--headless"],
      "env": {
        "SYNTHETIC_SETTING": "not-a-secret"
      }
    }
  }
}
```

The terminal selects the server named `browser` and starts only that server. Other configured servers are ignored.
The `browser` entry must exist and be enabled. Without `--mcp-config`, the terminal keeps the Skills-only `read`,
`write`, and `bash` behavior and does not read any MCP config, including Pi's config. The MCP manager is closed when
the chat returns through `/exit`, end-of-input, a prompt failure, or a startup failure after tool discovery began.

### Framework dependency

Eng Hub consumes the published `com.nimblygames.framework:LLM`, `:MCP`, and `:Skills` artifacts from the public Nimbly
Games repository, with Maven Central for transitive dependencies. The coordinate is `3.0.3-SNAPSHOT`. Story 1 in the
framework repository added public publishing for `Skills`, but did not publish a fixed `3.0.3` release: public metadata
currently exposes only the mutable snapshot. Until a fixed release is published, a build re-resolving the snapshot can
pick up a different framework revision. Framework process execution, which the `bash` tool needs, currently has only
a JVM implementation, so the terminal chat remains a JVM launch.

### Conversation history and compaction

Koog owns the conversation history. The agent installs Koog's `ChatMemory` feature with its in-memory history provider,
keyed by the fixed `eng-hub-terminal` session id. Each prompt is a new Koog agent run that reloads that history, so
follow-ups see earlier prompts, tool calls, and tool results without a separate Eng Hub history store.

The agent also uses Koog's single-run strategy with history compression. Once the prompt exceeds 60 messages, it
summarizes the retained tail (the last 20 messages) with the same model instead of growing the prompt without bound.
History is process-local and is lost when the terminal exits.

### Trust and scope

The framework Skills `read` and `write` tools can access any UTF-8 file the JVM process can access, including
absolute paths and paths outside the launch directory. The `write` tool creates or replaces a file's entire contents,
creates missing parent directories, and has no approval UI or path confinement; it is a trusted local overwrite. The
`bash` tool runs arbitrary non-interactive commands with the host process's permissions, without a sandbox, path
confinement, or command approval. An explicitly configured `browser` MCP server can start its own local process and
receive the environment values in the config, and its tools can drive that browser. All of these are trusted host
capabilities, not a sandbox. Markdown skill loading, a GUI chat surface, a custom history store, and a native
executable are intentionally out of scope for this terminal chat slice.

### Manual LM Studio smoke test

Automated tests use a fake model, a fake MCP session, and temporary files. To verify the real end-to-end path, run LM
Studio with the model above and, from the repository root:

1. Create any local text file containing a known fact, for example `/tmp/release-notes.txt` with `Release on Friday`.
2. Run `./gradlew :eng-hub:terminalChat`.
3. Ask it to read that file and report the fact.
4. Ask a follow-up about the same fact.
5. Confirm the first answer came from the `read` tool and the follow-up remembers it, then quit with `/exit` or Ctrl-D.

For the write path, use a disposable launch directory and ask `Write summary.txt containing Release on Friday`.
Confirm that `summary.txt` exists with exactly `Release on Friday`. The Gradle task launches in the repository root,
so use a disposable checkout copy or an absolute path inside a disposable directory rather than a real project file.

For the bash path, create a disposable two-line file such as `/tmp/eng-hub-lines.txt` and ask it to count the lines
using bash. Confirm it invokes the `bash` tool and replies `2`. Use only safe, disposable commands and files.

For the browser path, pass a synthetic or personal `--mcp-config` file and ask it to open `https://example.com` and
report the title. Confirm it invokes a browser MCP tool and reports `Example Domain`, then quit with `/exit` or Ctrl-D.
Do not commit personal MCP configs, browser profiles, or credentials.

## Implement-then-review agent

The `implementReviewChat` task runs a separate JVM terminal program that carries one story through two model calls:

1. an implementor stage with the same Skills `read`, `write`, and `bash` tools and `QWEN_3_8_27B` model as `terminalChat`
2. a fresh reviewer stage with read-only inspection tools (Skills `read`; no `write` and no `bash`) and its own
   conversation, opened only after the implementor session closes

Every nonblank story line starts a new run, so a previous story's conversation never reaches the next implementor or
reviewer. The implementor reports `{"files":["Greeting.kt"],"what":"Updated greeting","why":"Story asks to greet Ada"}`;
the reviewer reports `{"verdict":"pass","findings":[]}` or `{"verdict":"needs_changes","findings":["..."]}`.
Ordinary Kotlin code decodes and validates both reports; invalid JSON, blank fields, a `pass` with findings, or
`needs_changes` with no findings fail the run instead of producing a review. The flow does not fix findings, commit, or
open pull requests.

Start LM Studio as for terminal chat, then launch from the repository root:

```bash
./gradlew :eng-hub:implementReviewChat
```

The launcher accepts the same `--mcp-config`, `--lm-studio-base-url`, and `--lm-studio-api-key-file` options as
`terminalChat`. With `--mcp-config`, only the implementor receives browser MCP tools. The reviewer has only Skills
`read`: configured MCP servers may expose write or code-execution tools, so their entire registry is excluded from
the read-only reviewer stage. Quit with `/exit` or Ctrl-D; every other nonblank line is a story.

Example conversation with a disposable `Greeting.kt`:

```text
story> Change Greeting.kt to greet Ada
implementor> files: Greeting.kt
implementor> what: Updated greeting
implementor> why: Story asks to greet Ada
reviewer> pass
reviewer> no findings
story> /exit
```

Manual LM Studio smoke test: use a disposable checkout or directory, create `Greeting.kt` with a greeting that does not
mention Ada, run `./gradlew :eng-hub:implementReviewChat`, and enter `Change Greeting.kt to greet Ada`. Confirm the
implementor writes the file, the reviewer runs in a fresh conversation, reads `Greeting.kt` before reporting a verdict,
and the CLI prints the completed review. The implementor's `files` list is a claim, not proof of work; the reviewer
inspects the working tree with its own `read` tool and cannot change it.

## Building

Build the module and run its tests:

```bash
./gradlew :eng-hub:build
```

Create a distributable for the current OS:

```bash
./gradlew :eng-hub:packageDistributionForCurrentOS
```

Other packaging tasks available today:

```bash
./gradlew :eng-hub:createDistributable
./gradlew :eng-hub:packageUberJarForCurrentOS
./gradlew :eng-hub:packageDmg
./gradlew :eng-hub:packageMsi
./gradlew :eng-hub:packageDeb
./gradlew :eng-hub:packageRpm
```

The package version comes from the root Gradle `version` in `dev-lake-utils.gradle.kts`; native packages use only
the SemVer core version because native package formats reject prerelease and build metadata. Apple `jpackage` also
rejects a zero major version, so macOS package metadata adds one to the SemVer major version while release filenames
retain the source version. This permanent offset preserves version ordering when the project moves from `0.x` to `1.x`.
Packages are written under `eng-hub/build/compose/binaries/main`, in the `deb`, `rpm`, `dmg`, or `msi` directory for
their format.

## Releasing native packages

Set the root Gradle version to a stable `MAJOR.MINOR.PATCH` value and merge that commit. Tag the same commit with the
exact same raw version, without a `v` prefix. For example:

```bash
git tag 0.2.0
git push origin 0.2.0
```

The release workflow verifies the tag against the Gradle version before building. A matching tag creates GitHub Release
`0.2.0` and uploads `eng-hub-0.2.0-linux-amd64.deb`, `eng-hub-0.2.0-linux-x86_64.rpm`,
`eng-hub-0.2.0-macos.dmg`, and `eng-hub-0.2.0-windows-x64.msi`. These packages are unsigned, and the macOS app is not
notarized, so macOS Gatekeeper and Windows SmartScreen may warn users before opening them.

## Development Notes

- Gradle wrapper version: `9.4.1`
- Kotlin JVM target: `17`
- UI stack: Kotlin Multiplatform + Compose Desktop
- The module uses Compose Desktop's current-OS runtime dependency for Skiko rather than pinning a platform-specific Skiko artifact directly
- The main window title is `Eng Hub`

## What Else We Still Need

These are the most obvious gaps from the current implementation:

- A screenshot or short walkthrough so new users know what the UI looks like
- A checked-in sample config file instead of requiring people to infer the JSON shape from code
- Better documentation for how `organizationIds` should be populated and what the expected GitHub token scopes are
- Clearer wording around `Setup`: it creates a worktree and runs commands, but it does not directly open an editor unless your configured commands do that
- Per-item setup progress instead of one global `Setting up...` state
- Search, filtering, and sorting in both panes once the lists get large
- A visible activity log for automatic actions like auto-approval and auto-dismissal
- Better bootstrap UX when config is missing, especially for first-time setup
- Platform support clarification and validation for the Compose Desktop package targets

## Source Pointers

- App entry point: `src/jvmMain/kotlin/com/github/karlsabo/devlake/enghub/main.kt`
- App bootstrapping: `src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHub.kt`
- Config model: `src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHubConfig.kt`
- Main screen: `src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/screen/EngHubScreen.kt`
- View model: `src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModel.kt`
- Worktree setup: `src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/WorktreeSetupCommands.kt`
