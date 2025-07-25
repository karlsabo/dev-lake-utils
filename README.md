- [TODO](#todo)
- [Overview](#overview)
  - [Utilities](#utilities)
  - [Summary Publisher](#summary-publisher)
  - [User Metrics Publisher](#user-metrics-publisher)
  - [Location for configuration files](#location-for-configuration-files)
    - [Users and teams](#users-and-teams)
    - [DevLake datasource DB config](#devlake-datasource-db-config)
    - [Summary publisher config](#summary-publisher-config)
    - [User metrics publisher config](#user-metrics-publisher-config)
- [Query improvements](#query-improvements)
  - [avatar\_url](#avatar_url)
  - [Slow query by parent\_issue\_id](#slow-query-by-parent_issue_id)

# TODO

* [ ] Create native executables
* [ ] Add logging
* [ ] Add dependency injection
* [ ] Consider consolidating into a single configuration file

# Overview

[Devlake](https://devlake.apache.org/) is an open-source platform designed to help you easily integrate and visualize
data from a variety of sources, such as GitHub, Jira, and PagerDuty. By combining data from your development, project
management, and incident response tools, Devlake provides a unified view of your team's performance, allowing you to
track key metrics and gain insights into your workflows.

Whether you're looking to analyze code commits, issue tracking, or incident resolution times, Devlake empowers you to
see the full picture and make data-driven decisions to optimize your development processes.

This project connects to your DevLake instance to pull data and create summaries of projects and user metrics. The
summaries are sent to Zapier, which then forwards them to Slack. The project consists of the following subprojects:

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

### DevLake datasource DB config

`dev-lake-datasource-db-config.json` contains the database configuration for the DevLake datasource. The JSON format for
the [DataSourceDbConfigNoSecrets](./utilities/src/jvmMain/kotlin/com/github/karlsabo/ds/DataSourceManagerDb.kt) is as
follows:

```json
{
  "jdbcUrl": "jdbc:mysql://localhost:4306/lake",
  "username": "merico",
  "passwordFilePath": "/secrets/dev-lake-db-password.txt",
  "driverClassName": "com.mysql.cj.jdbc.Driver",
  "maximumPoolSize": 30,
  "minimumIdle": 2,
  "idleTimeoutMs": 10000,
  "connectionTimeoutMs": 5000
}
```

### Users and teams

`users-and-teams.json` contains the user and team mappings. The JSON format for
the [DevLakeUserAndTeamsConfig](./utilities/src/jvmMain/kotlin/com/github/karlsabo/devlake/DevLakeUserAndTeamsConfig.kt)
is as follows:

```json
{
  "users": [
    {
      "id": "name@example.local",
      "email": "name@example.local",
      "name": "John Doe",
      "slackId": "memberId01"
    },
    {
      "id": "otherperson@example.local",
      "email": "otherperson@example.local",
      "name": "Jane Doe",
      "slackId": "memberId02"
    }
  ],
  "userAccounts": [
    {
      "userId": "name@example.local",
      "accountId": "github:GithubAccount:1:123"
    },
    {
      "userId": "name@example.local",
      "accountId": "jira:JiraAccount:1:123"
    },
    {
      "userId": "otherperson@example.local",
      "accountId": "github:GithubAccount:1:321"
    },
    {
      "userId": "otherperson@example.local",
      "accountId": "jira:JiraAccount:1:321"
    }
  ],
  "teams": [
    {
      "id": 1,
      "name": "MyTeam",
      "alias": "MyTeam"
    }
  ],
  "teamUsers": [
    {
      "teamId": 1,
      "userId": "name@example.local"
    },
    {
      "teamId": 1,
      "userId": "otherperson@example.local"
    }
  ]
}
```

### Summary publisher config

`summary-publisher-config.json` contains the configuration for the summary publisher. The JSON format for
the [SummaryPublisherConfig](./summary-publisher/src/jvmMain/kotlin/com/github/karlsabo/devlake/tools/SummaryPublisherConfig.kt)
is as follows:

```json
{
  "zapierSummaryUrl": "https://hooks.zapier.com/hooks/catch/123/abc/",
  "summaryName": "Our team",
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
  ]
}
```

### User metrics publisher config

`user-metrics-publisher-config.json` contains the configuration for the user metrics publisher. The JSON format for
the [UserMetricsPublisherConfig](user-metrics-publisher/src/jvmMain/kotlin/com/github/karlsabo/devlake/metrics/UserMetricPublisherConfig.kt)
is as follows:

```json
{
  "userIds": [
    "jane.doe@example.local"
  ],
  "organizationIds": [
    "cilium"
  ],
  "zapierMetricUrl": "https://hooks.zapier.com/hooks/catch/123/abc/",
  "metricInformationPostfix": "\n━━━━━━━━━━━━━━━━━━\n\n💡 *Why do we track these metrics?*  \nWe use these to *track our progress toward milestones* and ensure we are on pace to meet our goals. 📈\n\n🔗 *Helpful Links:*\n• 📜 <https://medium.com/@jamesacowling/stepping-stones-not-milestones-e6be0073563f#:~:text=The%20key%20thing%20about%20a,unknowns%20start%20to%20fall%20away.|Stepping stones, not milestones>\n• 🛠️ <https://ronjeffries.com/articles/019-01ff/story-points/Index.html|Slice stories down>\n• 📊 <https://jacobian.org/2021/may/25/my-estimation-technique/|Estimation technique>\n"
}
```

# Query improvements
## avatar_url
There’s an issue where an avatar url exceeds the 256 character limit. 
```sql
ALTER TABLE _tool_github_accounts
   MODIFY avatar_url varchar(2048) null
;
ALTER TABLE _tool_github_reviewers
   MODIFY avatar_url varchar(2048) null
;
ALTER TABLE accounts
   MODIFY avatar_url varchar(2048) null
;
```

## Slow query by parent_issue_id
```sql
ALTER TABLE issues 
    ADD INDEX idx_issues_parent_issue_id (parent_issue_id)
;
```

# Backup and recover DevLake
Make sure to choose the correct exposed port from your `docker-compose.yml`

`mysqldump --verbose --host=127.0.0.1 --port=4306 -uroot -p --single-transaction --ignore-table=lake._devlake_locking_stub lake > ~/lake.sql`

`mysql --host=127.0.0.1 --port=4306 -uroot -p lake < lake.sql`
