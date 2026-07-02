# Eng Hub

Desktop Compose app for watching GitHub pull requests and notifications, then taking a few common actions without living in the browser.

## What It Currently Does

The app has two panes:

- `Pull Requests`
- `Notifications`

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

The app expects two config files under the shared `DevLakeUtils` application directory.

Config directory by OS:

- macOS: `~/Library/Application Support/DevLakeUtils`
- Linux: `~/.local/share/DevLakeUtils`
- Windows: `%APPDATA%/DevLakeUtils`

Required files:

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
- `localRepositories`: local repository roots managed by Eng Hub; each entry has an absolute `path` and optional `setupCommands`
- `setupShell`: login shell used to run setup commands

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

If `eng-hub-config.json` is missing or invalid, the app creates a default config file and shows an error dialog telling you to update it. The app does not continue into the main UI until configuration loads successfully.

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
```

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
```

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
