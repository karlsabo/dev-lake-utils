# Introduction

Contains common utilities used by other projects, including API clients for GitHub, Jira, and PagerDuty, as well as user
and team configuration management.

## API Clients

The utilities project provides direct API clients for:

- **GitHub**: Access GitHub repositories, issues, and pull requests
- **Jira**: Query Jira issues and comments
- **PagerDuty**: Retrieve incident information

## User Configuration

The project uses [users-config.json](../README.md#users-config) to manage user information, including mappings to
GitHub, Jira, and Slack IDs.
