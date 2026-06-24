- [TODO](#todo)
- [Development](#development)
  - [Quality checks](#quality-checks)
- [Eng Hub](./eng-hub/README.md)
- [Overview](#overview)
  - [Utilities](#utilities)
  - [Summary Publisher](#summary-publisher)
  - [User Metrics Publisher](#user-metrics-publisher)
  - [Location for configuration files](#location-for-configuration-files)
    - [Users and teams](#users-config)
    - [Summary publisher config](#summary-publisher-config)
    - [User metrics publisher config](#user-metrics-publisher-config)

# TODO

* [ ] Fix detekt to support Compose: https://detekt.dev/docs/introduction/compose/
    * Fix ktlint to support Compose: https://mrmans0n.github.io/compose-rules/ktlint/
* [ ] Add jetbrains changelog gradle plugin
* [ ] Add GitHub actions to build and publish versions of the app for Linux, macOS, Window
* [ ] Need a settings UI/UX.
  * Add it to the menu popup to launch a settings UI
  * The Settings should have options for everything in the com.github.karlsabo.devlake.enghub.EngHubConfig
  * We should display this settings/config on a fresh launch that doesn't have a config file yet
* [ ] After hitting the approve button on a Notification, once the notifications refresh again all those notifications reappear.
* [ ] Worktrees should start expanded
    * In the worktree view, have a highlight hover effect so you know what row you're on
    * Clicking on the worktree to expand, it sometimes takes too long to expand
    * Should be able to expand anywhere with a double click
    * Should be able to right-click to get all the menu options
    * Should have PR information if there's a PR connected to the branch
        * Should have menu options to open the PR in the web
  * Should have shortcut style buttons, open, and archive, use emojis to keep it small
* [ ] Need buttons on worktrees to rebase or merge in origin
* [ ] When archiving a worktree, don't prompt, move it into some archiving bucket, delayed by 60 seconds or so, with a cancel archive button.
  * Play a nice animation that moves it into a recycle bin in the bottom right of the screen, then you can click that bin and undo (stop the archive) withing 60 seconds. If archiving already began, grey out the button and have a hover text helper that says it's being removed. If there's a remote branch or some other way to recover it we should support that.
* [ ] Add a way to create a worktree from a remote branch
* [ ] Need a quick way to archive worktrees. Branches that aren't mine, branches that have been merged to origin/main or master.
* [ ] Add a menu pane. So three dots in top left corner that launches a 'window' with a search for all the actions you can take.
  * Add a mock entry for now that just pops a new window that says hello world
* [ ] Have the GH API for getting notifications return each page as it loads one, so maybe use a kotlinx coroutine channel. that way the UI is more responsive and doesn't have to wait for all notifications to load before it can start displaying some.
* [ ] Switch to using https://github.com/vinceglb/FileKit or anything better
* [ ] Clean up tests that have /User/karl.sabo in the paths. Clean up test names to be Kotlin idiomatic.
* [ ] When setting up worktrees, do we have to disable all setup buttons? It would be great to set up multiple worktrees at a time.
* [ ] Add information log in the app, like app notifications, so we can see what has happened, when a worktree is created, archived, etc.
* [ ] Add the ability to put worktrees into categories
    * E.g., In progress, In review, Done
* [ ] Add a notification badge on the tab when attention of the user is needed
    * Then put a notification badge on the item(s) that needs attention within that tab
    * If there's a notification to review
    * If a PR build has failed, or there's a new comment, etc.
    * If a worktree agent has finished or needs attention
  * For workspace creation, if a background fetch fails and Eng Hub falls back to local refs
* [ ] Fix all test names, either camelCase or back tick names `hello moto`
* [ ] Optimize GitHub API requests: When refreshing notifications, only pull extra information if needed. Same with PRs.
* [ ] If I mark a notification as done, add it to our marked done database table, they keep coming back from GitHub
* [ ] Filter draft PR notifications
* [ ] Show my review status on PR notifications
* [ ] Convert to panel type UI. Have PRs, Tickets, Notifications, etc. that expand a panel, and then you can click each one and they show options for what to do.
* [ ] Start an auto review on PRs, show the copyable path to the review Markdown
* [ ] Add a queued status for PRs so we know if it's waying in a merge queue, maybe give the position in the queue too.
* [ ] Clicking "Setup" greys out all Setup buttons, it should only do it for the current task
* [ ] Add a UI menu option to sync skills
  * Add a settings option that allows it to run automatically
* [ ] Add a UI menu option to upgrade agent harnesses (claude code, codex, pi, goose, etc)
    * Add a settings option that allows it to run automatically
    * Also add auto install, detect OS and install type, so npm i (skip -g since that requires root on linux).
      * brew, dnf, etc.
* [ ] Create native executables
* [ ] Consider consolidating into a single configuration file
* [ ] Add a UI panel for showing tickets assigned to the user
    * If a related PR is closed, ask if the ticket should be closed
* [ ] Search function to look at all PRs, notifications, etc.
* [ ] Can we write UI tests?
    * Can an LLM execute some manual UI-driven tests?
  * Want the UI to be universal. So the web UI will have to call the backend server to run desktop-style functions.
    * Might need to split eng-hub into eng-hub-ui and backend
* [ ] Look into <https://github.com/obra/superpowers/tree/main> for their skills
* [ ] Look into <https://github.com/garrytan/gstack> for their skills
* [ ] Look into <https://github.com/mattpocock/skills/tree/main> for their skills
* [ ] Look into <https://worktrunk.dev/>
* [ ] Add a database pruning background task that runs at startup and then every hour to prune old DB entries.
* [ ] Setup command error dialog is too cluttered to identify what failed. Need the command that failed highlighted, and maybe standard error highlighted.

## Implementation loop
* Implement
    * Create classes/interfaces/functions with contracts
  * Create Unit tests
  * Review Unit tests
  * Write code to satisfy unit tests
  * Verify tests pass
  * Create white box unit tests
  * Review white box unit tests
  * Verify tests pass
  * Create integration tests
  * Review integration tests
  * Verify integration tests pass
  * Verify all tests pass
* [skill] Code review
* Feed code review into implement skill
* Profile - performance check
  * Plan the profile check
  * Run the profile check
  * Create a report of areas to speed up
  * Run report through plan [skill] to break up the work
  * Implement [skill] all the tasks from the profile report
* [skill] Code review

## Development

### Quality checks

Spotless with ktlint owns formatting. Run `./gradlew spotlessApply` to format Kotlin and Gradle Kotlin DSL files, then run `./gradlew spotlessCheck` to verify formatting before committing.

Detekt owns code-quality warnings that go beyond formatting. Run `./gradlew detekt` to run the static analysis checks directly, or run `./gradlew check` to run the full Gradle verification path, including Spotless and detekt.

## Set up MCP servers

* [MCP Proxy](https://github.com/smart-mcp-proxy/mcpproxy-go)
    * GitHub
    * BuildKite
    * Atlassian
    * Linear
    * Sentry
    * Glean
    * Whimsical
    *

## Auto start reviewing PRs

* Auto kick off PR reviews when a notification comes in that requires it

## Have multiple PR review styles

* Use different PR review focuses, security, testing, mccabe complexity, architecture.
* Use a directory for the reviews, each reviewing creates its own comment file
* Consolidate the comments
    * Probably ask the reviewers to use a JSON schema

## Have an issues list

* Show assigned issues
* Show related issues that should be picked up next
    * Maybe in the same Epic, or Project

## Have a task list

* Pull in tasks from multiple sources, Google Tasks etc.

## Keep cli tools up to date

* Run update scripts for claude code, codex cli, goose, OpenCode, etc

## Notification panel

* Have app notifications and logs for when it completes a task like pulling the latest git notifications, updating tools, etc.

# Overview

This project makes direct API calls to GitHub, Jira, Linear, and PagerDuty to collect data and create summaries of
projects and
user metrics. By combining data from your development, project management, and incident response tools, it provides a
unified view of your team's performance, allowing you to track key metrics and gain insights into your workflows.

Whether you're looking to analyze code commits, issue tracking, or incident resolution times, this project empowers you
to see the full picture and make data-driven decisions to optimize your development processes.

The summaries are sent to Zapier, which then forwards them to Slack. The project consists of the following subprojects:

## Utilities

The [utilities](./utilities/readme.md) project contains common utilities used by other projects, such as user and team
creation.

## Summary Publisher

The [summary publisher](./summary-publisher/readme.md) project is responsible for summarizing and publishing project
information. It uses configurations defined in `summary-publisher-config.json` to manage different projects, their
leads, contributors, and related issue keys. The summaries are published to a specified Zapier URL.

## User Metrics Publisher

The [user metrics publisher](./user-metrics-publisher/readme.md) collects user metrics and sends a Slack report to that
user. It collects metrics such as pull requests and issues.

## Location for configuration files

* On Linux: `~/.local/share/DevLakeUtils`
* On macOS: `~/Library/Application Support/DevLakeUtils`
* On Windows: `%APPDATA%\DevLakeUtils`

### Users config

`users-config.json` contains the user mappings. The JSON format for
the [UsersConfig](./utilities/src/commonMain/kotlin/com/github/karlsabo/dto/UsersConfig.kt)
is as follows:

```json
{
  "users": [
    {
      "id": "name@example.local",
      "email": "name@example.local",
      "name": "John Doe",
      "slackId": "memberId01",
      "gitHubId": "johndoe",
      "jiraId": "john.doe"
    },
    {
      "id": "otherperson@example.local",
      "email": "otherperson@example.local",
      "name": "Jane Doe",
      "slackId": "memberId02",
      "gitHubId": "janedoe",
      "jiraId": "jane.doe"
    }
  ]
}
```

### Summary publisher config

`summary-publisher-config.json` contains the configuration for the summary publisher. The JSON format for
the [SummaryPublisherConfig](./summary-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/tools/SummaryPublisherConfig.kt)
is as follows:

```json
{
  "zapierSummaryUrl": "https://hooks.zapier.com/hooks/catch/123/abc/",
  "summaryName": "Our team",
  "isTerseSummaryUsed": true,
  "projects": [
    {
      "id": 1,
      "parentId": null,
      "title": "🐍 Cool project 1",
      "links": [
        "https://example.local/wiki/cool+project+1"
      ],
      "slackProjectChannel": "#cool-project",
      "projectLeadUserId": "john.doe@example.local",
      "projectContributors": [
        "jane.doe@example.local"
      ],
      "productManager": "joe.bob@example.local",
      "topLevelIssueKeys": [
        "JIRA-1234"
      ]
    },
    {
      "id": 2,
      "parentId": null,
      "title": "🌖 Moon shot",
      "links": [
        "https://example.local/wiki/moon+shot"
      ],
      "slackProjectChannel": "",
      "projectLeadUserId": "james.bond@example.local",
      "projectContributors": [],
      "productManager": "m@example.local",
      "topLevelIssueKeys": [
        "JIRA-234"
      ],
      "isVerboseMilestones": true,
      "isTagMilestoneOwners": true
    }
  ],
  "isMiscellaneousProjectIncluded": true,
  "gitHubOrganizationIds": ["example-org"],
  "pagerDutyServiceIds": ["ABCDEF"],
  "miscUserIds": ["john.doe@example.local"]
}
```

### User metrics publisher config

`user-metric-publisher-config.json` contains the configuration for the user metrics publisher. The JSON format for
the [UserMetricPublisherConfig](user-metrics-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/metrics/UserMetricPublisherConfig.kt)
is as follows:

```json
{
  "userIds": [
    "jane.doe@example.local"
  ],
  "organizationIds": [
    "example-org"
  ],
  "zapierMetricUrl": "https://hooks.zapier.com/hooks/catch/123/abc/",
  "metricInformationPostfix": "\n━━━━━━━━━━━━━━━━━━\n\n💡 *Why do we track these metrics?*  \nWe use these to *track our progress toward milestones* and ensure we are on pace to meet our goals. 📈\n\n🔗 *Helpful Links:*\n• 📜 <https://medium.com/@jamesacowling/stepping-stones-not-milestones-e6be0073563f#:~:text=The%20key%20thing%20about%20a,unknowns%20start%20to%20fall%20away.|Stepping stones, not milestones>\n• 🛠️ <https://ronjeffries.com/articles/019-01ff/story-points/Index.html|Slice stories down>\n• 📊 <https://jacobian.org/2021/may/25/my-estimation-technique/|Estimation technique>\n"
}
```
