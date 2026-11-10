---
name: eh-create-draft-pr
description: Create a draft pull request with command line tools, a ticket link, required template content, and at most 200 words of authored description.
user-invocable: true
allowed-tools: Bash(gh glab *), Read, Glob, Grep, Write, Edit
---

# Create Draft PR Skill

1. Verify the current branch is a feature branch, not `main`/`master`.
2. Check `git status --short`.
3. If there are staged or unstaged changes, ask whether they should be committed before creating the PR. Do not silently omit local changes from the PR.
4. Push the branch. If the branch has no upstream yet, push with upstream tracking.
5. Gather or infer the PR title, base branch, and ticket link. If any of these are unclear, ask.
6. Draft the body in a temporary Markdown file using the rules below. Read it back and check its length before submitting.
7. Create the draft PR with `gh pr create --draft --body-file <file>` (or the GitLab equivalent). Fetch the published description and verify it matches the file.

## Description limits — required, not suggestions

- Target **100–150 words; hard maximum 200 words of authored text**, unless the user or repository explicitly requires more detail. This includes summary, tests, risks, deployment, and monitoring combined, not 200 words per section. Do not pad short changes to meet the target.
- Required template wording does not count toward that budget. Preserve repository-required headings/checklists, but do not expand checklist items with explanations or test inventories. Use one short line per required risk/deploy/monitoring section; mark inapplicable items honestly.
- Without a required template, use only the ticket link, `Summary`, and `Test plan`. Add a risk/rollout bullet only for a material concern.
- Put the ticket URL first, using the repository's required `Fixes`/`Part of` syntax. Summary: **at most two sentences** explaining the behavior change and why.
- Test plan: **at most three bullets**: completed validation with its result, a reproducible manual check with expected behavior, and a material unverified gap if needed. Label planned/unrun checks; never claim they passed.
- Do not list every file, function, test name, or edge case. No diff narration, implementation essay, raw logs, hook transcripts, local timeout/setup stories, repeated assurances, or generated release notes. Link detailed evidence instead of pasting it.
- State each fact once. Do not repeat tests under the summary, checklist, risk section, and test plan.

## Final length gate

Before creating or updating the PR:

1. Read the entire body file. Count words with `wc -w <file>`; if the total exceeds 200, separately count the authored text excluding unchanged required template wording.
2. If authored text exceeds 200 words, **rewrite it before submitting**. Only an explicit user/repository requirement for additional detail overrides the cap; complexity or available context alone does not. Optional prose still must be cut. Moving prose into tables, HTML comments, or collapsible sections does not exempt it.
3. Check: ticket first, summary at most two sentences, test plan at most three bullets, required template intact, no duplicated facts, no invented validation.

## Example without a required template

```markdown
Fixes https://linear.app/example/issue/ABC-123

## Summary
Route device-verified users who still require MFA to setup before their original destination. This prevents account-setup API requests from receiving an HTML redirect.

## Test plan
- Completed: 231 focused auth tests passed; changed-line coverage 100%.
- Not run: on staging, log in from a fresh browser with MFA required but unconfigured; verify device confirmation opens MFA setup, then reaches account setup after MFA enrollment.
```

Stop there. More available context is not a reason to add more text.
