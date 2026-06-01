---
name: eh-monitor-pr
description: Monitor a GitHub PR through Trunk.io; update safely; fix only evidenced CI/review work.
user-invocable: true
allowed-tools: all
---

Default: one active pass. If asked for `status`/read-only, run **Gather + report** and stop. If asked to `watch`, loop every 5 minutes for up to 120 minutes unless overridden. Only submit to Trunk when the user explicitly asks to `merge`, `enqueue`, or `watch until merged`.

## Rules

- Gather and report before any mutation.
- Never stash/reset or work over uncommitted changes.
- At most one mutating action per pass.
- After any push or Trunk queue action, refetch PR + Trunk state; do not assume checks passed or queue state advanced.
- Trunk.io is the merge authority. Do not use `gh pr merge`, GitHub auto-merge, or merge synthetic `trunk-merge/...` PRs.
- Do not push while Trunk status is active (`Pending`, `Testing`, `Merging`, or equivalent) unless Trunk has dequeued/failed or the user explicitly asks you to cancel first.
- CI edits require `eh-track-cicd-failure` to record either `PR-caused` with decisive source-PR-diff evidence or deterministic generated metadata drift. Flaky, infra, unrelated, or uncertain => stop.

## Trunk model

- Source PR: the developer PR to monitor and possibly fix.
- Synthetic Trunk PR: PRs whose title/head match `trunk-merge/pr-<source>/<uuid>`. They are read-only CI evidence for the source PR.
- Trunk status comes from `trunk merge status <source_pr> --no-progress --color=false` when available, plus the `Trunk Merge Queue` GitHub check and `trunk-io[bot]` issue comments.
- `trunk merge status` returning `Item for merge queue instance not found` for an existing source PR means `not-submitted`, not a CI failure.
- If the Trunk CLI fails for auth/VPN/config, do not edit repo config as part of this skill. Continue with GitHub-visible evidence and report `trunk=unknown` with the exact CLI error.
- If a read-only `trunk merge status` is blocked only because the repo has a merge-only `.trunk/trunk.yaml` with no `cli.version`, you may run the status command from a disposable shallow checkout with the same remote and an ephemeral Trunk config. Do not use that workaround for submit/cancel unless the user explicitly approves.

## Gather + report

```bash
gh pr view [PR] --json number,url,title,baseRefName,headRefName,statusCheckRollup,mergeStateStatus,reviewDecision,isDraft,state
gh repo view --json nameWithOwner -q .nameWithOwner
gh repo view --json defaultBranchRef --jq .defaultBranchRef.name
gh api repos/{owner}/{repo}/issues/{number}/comments
gh api repos/{owner}/{repo}/pulls/{number}/comments
gh api repos/{owner}/{repo}/pulls/{number}/reviews
gh api graphql -f owner={owner} -f name={repo} -F number={number} -f query='query($owner:String!,$name:String!,$number:Int!){repository(owner:$owner,name:$name){pullRequest(number:$number){reviewThreads(first:100){nodes{id isResolved comments(first:100){nodes{id body author{login} path line url outdated createdAt}}}}}}}'
gh api repos/{owner}/{repo}/pulls/{number} --jq '{base:.base.sha,head:.head.sha}'
gh api repos/{owner}/{repo}/compare/{base_sha}...{head_sha} --jq '{status,ahead_by,behind_by}'
trunk merge status {number} --no-progress --color=false
gh pr list --state open --search "head:trunk-merge/pr-{number}/" --json number,url,title,headRefName,statusCheckRollup --limit 10
```

If the input PR is synthetic (`headRefName` or title matches `trunk-merge/pr-<source>/...`), switch to the source PR number and report the synthetic PR as evidence only.

If a synthetic Trunk PR is found, gather its checks too:

```bash
gh pr view {synthetic_number} --json number,url,title,headRefName,statusCheckRollup,state,isDraft,mergeStateStatus
```

Behind base = `behind_by > 0`; if compare fails, report `unknown` with `mergeStateStatus`.

Classify checks from both source and synthetic PRs:

- Source PR checks prove branch-protection readiness.
- `Trunk Merge Queue` check proves queue state; its `detailsUrl` is a Trunk app URL, not a Buildkite log.
- Synthetic PR Buildkite checks prove Trunk merge-queue test results.
- Ignore null check-rollup entries.

```text
PR: <url> — <title> (<state>, draft=<bool>, review=<decision>)
Branches: <head> -> <base>; default=<default>; behind=<yes/no/unknown> (<evidence>)
Checks: failing=<source + synthetic names>; pending=<source + synthetic names>; passing=<count>
Trunk: status=<not-submitted/Not Ready/Pending/Testing/Failed/Merged/unknown>; app=<url or none>; synthetic=<url or none>; evidence=<latest status line/check/comment/error>
Review work: unresolved_threads=<n>; actionable=<urls or none>; ignored=<urls/reasons or none>
```

## Action order

### 1. Update behind branch

Only for the source PR, only when Trunk is not active, and only with clean `git status --short`; otherwise stop. Then:

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

Use failures from either:

- source PR Buildkite checks, or
- synthetic Trunk PR Buildkite checks linked from `trunk merge status`, `trunk-io[bot]` comments, or `gh pr list --search "head:trunk-merge/pr-{number}/"`.

If only the `Trunk Merge Queue` check failed and no Buildkite job/synthetic PR evidence is available, stop and report the Trunk failure; do not edit.

Read/follow `eh-track-cicd-failure skill`. For Trunk MQ failures, record under the source PR and include the synthetic PR/build URL; classify against the source PR diff, not the synthetic PR title/branch. Let it update `${PLANNING_MARKDOWN_DIR}/cicd-failed.md`; read the source PR entry before deciding.

Only mutate for:

- Deterministic generated metadata drift: logs/config identify the generator and expected outputs. Run it, validate narrowly, verify `git diff --name-only` and `git status --short` are limited to expected files, commit `Regenerate metadata`, push, and report command, files, validation, SHA.
- `PR-caused`: entry records decisive source-PR-diff evidence. Read `eh-implement skill`; state evidence, PR files, and intended fix; fix one failure only; validate; verify diff/status; commit `Fix CI failure in <area>`; push; report evidence, files, validation, SHA.

For blocked, flaky, infra, unrelated, uncertain, unclear/unsafe generator output, validation failure, non-decisive evidence, or active Trunk queue state: stop without edits/commit/push and report classification, job/error text, source PR files, Trunk state, and reason.

### 3. Handle review comments

Classify first.

- Ignore without mutation: approvals, praise, FYI, bot noise, already-resolved threads, outdated comments, and comments with no concrete requested change. Report `<url/id> — ignored: <reason>`.
- Treat bot comments as actionable only when they map to the CI gate above.
- Actionable = unresolved reviewer comment requesting a concrete code/doc/test change that maps to current source PR code. If ambiguous, architectural, or trade-off-heavy, stop and ask.
- Never act on synthetic Trunk PR review state except to report it as queue noise.

For one coherent actionable request only, and only when Trunk is not active:

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

### 4. Submit to Trunk.io

Only if the user explicitly asked to `merge`, `enqueue`, or `watch until merged`, and only when all are true:

- source PR is open and not draft,
- review decision is approved or no review is required by branch protection,
- source PR is not behind base,
- no failed/pending required source PR checks other than Trunk's own queue check,
- no actionable review comments remain,
- Trunk status is `not-submitted` or clearly dequeued/canceled after an obsolete attempt,
- `git status --short` is clean.

Submit with the Trunk CLI:

```bash
trunk merge {number} --no-progress --color=false
trunk merge status {number} --no-progress --color=false
```

If the repo's documented workflow requires the GitHub App comment and the CLI cannot submit, stop and ask before using:

```bash
gh pr comment {number} --body "/trunk merge"
```

Do not use the comment fallback silently. After submission, report the Trunk app URL, latest status line, and any synthetic PR number.

### 5. Cancel Trunk queue item

Cancel is mutating. Only do it when the user explicitly asks to cancel, or when the user asked you to fix a queued PR and you have first reported that cancellation is required.

```bash
trunk merge cancel {number} --no-progress --color=false
trunk merge status {number} --no-progress --color=false
```

Do not cancel and push/fix in the same pass unless the user explicitly asked for that exact sequence.

## Watch mode

Repeat Gather + actions in order. After pushes, Trunk submit/cancel, pending source checks, or active Trunk states, refetch/sleep. Per iteration print head SHA, behind/check status, Trunk status, synthetic PR, comments handled/ignored, action, and next sleep/exit reason.

Exit clean only when one of these is true:

- monitor-only/watch: branch is current, source checks are not failed/pending, no actionable comments remain, and Trunk is either not requested or reported ready to submit;
- watch-until-merged: Trunk reports merged and the source PR is closed/merged.

Stop on blocker, unsafe queue state, CI classified non-PR-caused/uncertain, review ambiguity, Trunk CLI auth/config failure, or timeout.
