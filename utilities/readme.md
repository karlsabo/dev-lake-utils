# Introduction

Hold utilities for accessing DevLake database. It also has a utility to create user, account, and team mappings in the
DevLake database.

## Commands

`./gradle createUsersAndTeams`

Executes [CreateUsersAndTeams.kt](./src/jvmMain/kotlin/com/github/karlsabo/devlake/CreateUsersAndTeams.kt) to create
users, accounts, and team mappings in the DevLake database.

It will load [configurations](../readme.md#configuration-file-location) from `dev-lake-datasource-db-config.json`
and [users-and-teams.json](../readme.md#users-and-teams) to create new records in the database for mapping users to
accounts.
