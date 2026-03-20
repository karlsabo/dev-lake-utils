---
name: create-draft-pr
description: Create a draft GitHub pull request with `gh`, including a description, test plan, PR template content, and a Linear link near the top.
user-invocable: true
allowed-tools: Bash(gh *), Read, Glob, Grep, Write, Edit
---

# Create Draft PR Skill

1. Verify the current branch is a feature branch, not `main`/`master`.
2. Check `git status --short`.
3. If there are staged or unstaged changes, ask whether they should be committed before creating the PR. Do not silently omit local changes from the PR.
4. Push the branch. If the branch has no upstream yet, push with upstream tracking.
5. Gather or infer the PR title, base branch, and Linear ticket link. If any of these are unclear, ask.
6. Create a draft PR using `gh` with a description and test plan. If `.github/pull_request_template.md` exists, fill it out. Put the Linear link near the top of the PR body.
