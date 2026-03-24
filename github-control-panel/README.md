# GitHub Control Panel

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
- `github-control-panel-config.json`

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

### `github-control-panel-config.json`

Example:

```json
{
  "organizationIds": [
    "example-org"
  ],
  "pollIntervalMs": 600000,
  "repositoriesBaseDir": "/Users/you/git",
  "gitHubAuthor": "your-github-login",
  "worktreeSetupCommands": {
    "/Users/you/git/example-repo": [
      "direnv allow",
      "idea ./"
    ]
  },
  "setupShell": "/bin/zsh"
}
```

Field meanings:

- `organizationIds`: orgs searched for authored PRs
- `pollIntervalMs`: refresh interval for both panes
- `repositoriesBaseDir`: where repos are cloned if missing
- `gitHubAuthor`: GitHub login used for the pull request query
- `worktreeSetupCommands`: optional commands keyed by the base repo path
- `setupShell`: login shell used to run setup commands

### First run behavior

If `github-control-panel-config.json` is missing or invalid, the app creates a default config file and shows an error dialog telling you to update it. The app does not continue into the main UI until configuration loads successfully.

## Launching

Run from the repo root:

```bash
./gradlew :github-control-panel:run
```

Other useful run tasks that exist today:

```bash
./gradlew :github-control-panel:runRelease
./gradlew :github-control-panel:runDistributable
./gradlew :github-control-panel:hotRunJvm
```

## Building

Build the module and run its tests:

```bash
./gradlew :github-control-panel:build
```

Create a distributable for the current OS:

```bash
./gradlew :github-control-panel:packageDistributionForCurrentOS
```

Other packaging tasks available today:

```bash
./gradlew :github-control-panel:createDistributable
./gradlew :github-control-panel:packageUberJarForCurrentOS
./gradlew :github-control-panel:packageDmg
./gradlew :github-control-panel:packageMsi
./gradlew :github-control-panel:packageDeb
```

## Development Notes

- Gradle wrapper version: `8.14`
- Kotlin JVM target: `17`
- UI stack: Kotlin Multiplatform + Compose Desktop
- The module currently includes `skiko-awt-runtime-macos-arm64`, so the current setup is clearly macOS Apple Silicon oriented even though Compose packaging tasks exist for other OS formats
- The main window title is `Git Control Panel`

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
- Platform support clarification and validation beyond the current macOS ARM64-oriented runtime dependency

## Source Pointers

- App entry point: `src/jvmMain/kotlin/com/github/karlsabo/devlake/ghpanel/main.kt`
- App bootstrapping: `src/commonMain/kotlin/com/github/karlsabo/devlake/ghpanel/GitHubControlPanelApp.kt`
- Config model: `src/commonMain/kotlin/com/github/karlsabo/devlake/ghpanel/GitHubControlPanelConfig.kt`
- Main screen: `src/commonMain/kotlin/com/github/karlsabo/devlake/ghpanel/screen/GitHubControlPanelScreen.kt`
- View model: `src/commonMain/kotlin/com/github/karlsabo/devlake/ghpanel/viewmodel/GitHubControlPanelViewModel.kt`
- Worktree setup: `src/commonMain/kotlin/com/github/karlsabo/devlake/ghpanel/WorktreeSetupCommands.kt`
