---
name: eh-monitor-pr
description: Monitor a GitHub PR; update safely; fix only evidenced CI/review work.
user-invocable: true
allowed-tools: all
---

# eh-monitor-pr

Default: one active pass. If asked for `status`/read-only, run **Gather + report** and stop. If asked to `watch`, loop every 5 minutes for up to 120 minutes unless overridden.

## Rules

- Gather and report before any mutation.
- Never stash/reset or work over uncommitted changes.
- At most one mutating action per pass.
- After any push, refetch PR state; do not assume checks passed.
- CI edits require `eh-track-cicd-failure` to record either `PR-caused` with decisive PR-diff evidence or deterministic generated metadata drift. Flaky, infra, unrelated, or uncertain => stop.

## Gather + report

```bash
gh pr view [PR] --json number,url,title,baseRefName,headRefName,statusCheckRollup,mergeStateStatus,reviewDecision,isDraft,state
gh repo view --json nameWithOwner -q .nameWithOwner
gh repo view --json defaultBranchRef --jq .defaultBranchRef.name
gh api repos/{owner}/{repo}/pulls/{number}/comments
gh api repos/{owner}/{repo}/pulls/{number}/reviews
gh api graphql -f owner={owner} -f name={repo} -F number={number} -f query='query($owner:String!,$name:String!,$number:Int!){repository(owner:$owner,name:$name){pullRequest(number:$number){reviewThreads(first:100){nodes{id isResolved comments(first:100){nodes{id body author{login} path line url outdated createdAt}}}}}}}'
gh api repos/{owner}/{repo}/pulls/{number} --jq '{base:.base.sha,head:.head.sha}'
gh api repos/{owner}/{repo}/compare/{base_sha}...{head_sha} --jq '{status,ahead_by,behind_by}'
```

Behind base = `behind_by > 0`; if compare fails, report `unknown` with `mergeStateStatus`.

```text
PR: <url> — <title> (<state>, draft=<bool>, review=<decision>)
Branches: <head> -> <base>; default=<default>; behind=<yes/no/unknown> (<evidence>)
Checks: failing=<names>; pending=<names>; passing=<count>
Review work: unresolved_threads=<n>; actionable=<urls or none>; ignored=<urls/reasons or none>
Mutation: none yet
```

## Action order

### 1. Update behind branch

Only with clean `git status --short`; otherwise stop. Then:

```bash
git fetch origin <baseRefName>
```

- Default-base PR (`baseRefName == default`):
  ```bash
  git merge-base --is-ancestor origin/<baseRefName> HEAD || git merge --no-edit origin/<baseRefName>
  <targeted validation>
  git push
  git rev-parse HEAD
  ```
- Stacked PR (`baseRefName != default`, or user-supplied stacked base with clear matching evidence):
  ```bash
  git rebase origin/<baseRefName>
  <targeted validation>
  git push --force-with-lease
  git rev-parse HEAD
  ```

If strategy evidence conflicts, merge/rebase conflicts, or validation fails: do not commit/push; report blocker, conflicted files from `git status --short`, and `git merge --abort`, `git rebase --abort`, or manual resolution.

### 2. Handle failed Buildkite CI

If `statusCheckRollup` has a failed Buildkite check (`buildkite.com` URL or Buildkite name), read and follow `eh-track-cicd-failure skill`. Let it update `${PLANNING_MARKDOWN_DIR}/cicd-failed.md`; then read that PR entry before deciding.

- Blocked classification: stop without edits/commit/push and report:
  ```text
  CI blocked: <classification>
  Evidence: <job/error text>; PR files: <paths>; reason: <why not PR-caused>
  Mutation: none
  ```
- Deterministic generated metadata drift: run the known generator only if logs/config identify the command and expected outputs. Validate narrowly. Continue only if `git diff --name-only` and `git status --short` are limited to expected generated/related files. Commit `Regenerate metadata`, push, and report command, files, validation, SHA.
- `PR-caused`: read `eh-implement skill`; fix one failure only. Before editing, state evidence, PR files, and intended fix. Make the smallest change, validate, confirm `git diff --name-only` and `git status --short`, commit `Fix CI failure in <area>`, push, and report evidence, files, validation, SHA.

If generator command is unclear, output is unexpected/non-deterministic, validation fails, or evidence is not decisive, stop.

### 3. Handle review comments

Classify first.

- Ignore without mutation: approvals, praise, FYI, bot noise, already-resolved threads, outdated comments, and comments with no concrete requested change. Report `<url/id> — ignored: <reason>`.
- Treat bot comments as actionable only when they map to the CI gate above.
- Actionable = unresolved reviewer comment requesting a concrete code/doc/test change that maps to current code. If ambiguous, architectural, or trade-off-heavy, stop and ask.

For one coherent actionable request only:

1. Summarize thread ID, comment ID/URL, path/line, requested change.
2. Read `eh-implement skill`; make the smallest fix.
3. Run targeted validation; if it fails, stop without commit/push.
4. Confirm `git diff --name-only` and `git status --short` include only intended files.
5. Commit `Address reviewer feedback in <area>` and `git push`.
6. Reply after push:
   ```bash
   gh api repos/{owner}/{repo}/pulls/{number}/comments/{comment_id}/replies --method POST -f body="Fixed in <sha>: <summary>"
   ```
7. If reply succeeds and the thread is still unresolved, resolve only that thread:
   ```bash
   gh api graphql -f threadId=<thread_id> -f query='mutation($threadId:ID!){resolveReviewThread(input:{threadId:$threadId}){thread{id isResolved}}}'
   ```
8. Confirm `isResolved=true`; otherwise report the permission/API/ID blocker.

## Watch mode

Repeat Gather + actions in order. Per iteration print: head SHA, behind status, check status, comments handled/ignored, action, next sleep/exit reason. Sleep/refetch after pushes or pending checks. Exit clean only when branch is current, checks are not failed/pending, and no actionable comments remain. Exit blocked on any safety gate/blocker or timeout.
