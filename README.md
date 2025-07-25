- [TODO](#todo)
- [Overview](#overview)
  - [Utilities](#utilities)
  - [Summary Publisher](#summary-publisher)
  - [User Metrics Publisher](#user-metrics-publisher)
  - [Location for configuration files](#location-for-configuration-files)
    - [Users and teams](#users-config)
    - [Summary publisher config](#summary-publisher-config)
    - [User metrics publisher config](#user-metrics-publisher-config)

# TODO

* [ ] Create native executables
* [ ] Add logging
* [ ] Add dependency injection
* [ ] Consider consolidating into a single configuration file

# Overview

This project makes direct API calls to GitHub, Jira, and PagerDuty to collect data and create summaries of projects and
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

The [user metrics publisher](./user-metrics-publisher/readme.md) collects user metrics and send a Slack report to that
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
the [UserMetricPublisherConfig](user-metrics-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/tools/UserMetricPublisherConfig.kt)
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

