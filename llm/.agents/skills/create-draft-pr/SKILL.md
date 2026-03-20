---
name: create-draft-pr
description: Create a draft GitHub pull request with `gh`, including a description, test plan, PR template content, and a Linear link near the top.
user-invocable: true
allowed-tools: Bash(gh *), Read, Glob, Grep, Write, Edit
---

# Create Draft PR Skill

1. Push any commits that are on the local branch
2. Create a draft PR using the GitHub command line tool `gh` with a description and test plan. If there is a template at `.github/pull_request_template.md`, fill it out when making the PR. Also add a link to the Linear ticket near the top of the PR description.
