---
name: eh-create-draft-pr
description: Create a draft pull request with command line tools, including a description, test plan, PR template content, and a ticket link near the top.
user-invocable: true
allowed-tools: Bash(gh glab *), Read, Glob, Grep, Write, Edit
---

# Create Draft PR Skill

1. Verify the current branch is a feature branch, not `main`/`master`.
2. Check `git status --short`.
3. If there are staged or unstaged changes, ask whether they should be committed before creating the PR. Do not silently omit local changes from the PR.
4. Push the branch. If the branch has no upstream yet, push with upstream tracking.
5. Gather or infer the PR title, base branch, and ticket link. If any of these are unclear, ask.
6. Create a draft PR using command line tools with a description and test plan. If a pull request template exists, fill it out. Put the ticket link near the top of the PR body.

## PR description best practices

- Lead with the ticket link and a brief summary of the change and why it matters.
- Call out only material scope limits, risks, or reviewer decisions; don't narrate the diff.
- Keep the test plan short and reproducible; distinguish completed validation from steps still to run.
- Follow the PR template, but remove boilerplate, repetition, and generated prose that adds no value.
