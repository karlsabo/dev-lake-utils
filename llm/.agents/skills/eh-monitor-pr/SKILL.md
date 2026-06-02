---
name: eh-monitor-pr
description: Monitor a GitHub PR through Trunk.io; keep it moving toward merge, retry flaky/infra CI safely, report blockers, and fix only evidenced CI/review work.
user-invocable: true
allowed-tools: all
---

Mission: babysit the source PR until it merges, is ready for submission, or hits a real blocker. Always gather evidence first. Retry/re-submit flaky or infra failures when safe. Fix only evidenced PR-caused CI failures or actionable review work.

## Modes

- `status` / read-only: gather + report only. No mutations.
- default / single pass: gather + report + at most one safe action.
- `monitor` / `watch`: persistent mode; keep the PR healthy, but do **not** submit to Trunk. Exit when ready to submit or blocked.
- merge intent (`merge`, `enqueue`, `get this merged`, `push this through`, `monitor until merged`, `watch until merged`): persistent mode; submit/re-submit through Trunk when safe; exit only when merged or blocked.
- Persistent mode loops every 5 minutes for up to 120 minutes unless overridden. Do not stop on flaky/infra CI while safe retry paths remain.

Only submit to Trunk in merge-intent mode.

## Non-negotiable rules

- Gather and report before mutation; perform at most one mutating action per pass.
- Never stash/reset or work over uncommitted changes (`git status --short` must be clean before mutation).
- After any push or Trunk queue action, refetch PR + Trunk state; do not assume checks or queue state advanced.
- Trunk.io is the merge authority. Do not use `gh pr merge`, GitHub auto-merge, or merge synthetic `trunk-merge/...` PRs.
- Do not push while Trunk is active (`Pending`, `Testing`, `Merging`, or equivalent) unless Trunk has dequeued/failed or the user explicitly asks you to cancel first.
- Treat flaky/infra CI as retryable, not final, until retry budget is exhausted or evidence proves a deterministic failure.
- CI edits require `eh-track-cicd-failure`: it must record either `PR-caused` with decisive source-PR-diff evidence or deterministic generated metadata drift. Flaky, infra, unrelated, or uncertain => no code edits.

## Trunk model

- Source PR: the developer PR being monitored/fixed.
- Synthetic Trunk PR: title/head matches `trunk-merge/pr-<source>/<uuid>`. Use it only as read-only CI evidence for the source PR.
- If the input PR is synthetic, switch to the source PR and report the synthetic PR as evidence.
- Trunk state comes from `trunk merge status <source_pr> --no-progress --color=false`, the `Trunk Merge Queue` check, and `trunk-io[bot]` issue comments.
- `Item for merge queue instance not found` for an existing source PR means `not-submitted`, not CI failure.
- If Trunk CLI fails for auth/VPN/config, do not edit repo config. Continue with GitHub-visible evidence and report `trunk=unknown` plus the exact CLI error.
- If read-only status is blocked only because `.trunk/trunk.yaml` is merge-only and lacks `cli.version`, you may run status from a disposable shallow checkout with the same remote and ephemeral Trunk config. Do not use that workaround for submit/cancel without explicit approval.

## Gather + report

Run these commands as applicable:

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

For each synthetic PR found, also run:

```bash
gh pr view {synthetic_number} --json number,url,title,headRefName,statusCheckRollup,state,isDraft,mergeStateStatus
```

Interpretation:

- Behind base = `behind_by > 0`; if compare fails, report `unknown` with `mergeStateStatus`.
- Source PR checks prove branch-protection readiness.
- `Trunk Merge Queue` check proves queue state; its `detailsUrl` is a Trunk app URL, not a Buildkite log.
- Synthetic PR Buildkite checks prove Trunk merge-queue test results.
- Ignore null check-rollup entries.

Report in this shape:

```text
PR: <url> — <title> (<state>, draft=<bool>, review=<decision>)
Branches: <head> -> <base>; default=<default>; behind=<yes/no/unknown> (<evidence>)
Checks: failing=<source + synthetic names>; pending=<source + synthetic names>; passing=<count>
Trunk: status=<not-submitted/Not Ready/Pending/Testing/Failed/Merged/unknown>; app=<url or none>; synthetic=<url or none>; evidence=<latest status line/check/comment/error>
Review work: unresolved_threads=<n>; actionable=<urls or none>; ignored=<urls/reasons or none>
Retry posture: classification=<PR-caused/flaky/infra/unrelated/uncertain/none>; retry_count=<n>; next_action=<retry/re-submit/fix/report/wait/stop>
```

## Action loop and priority

In each pass: gather/report, ensure clean tree before mutation, then take the first safe action below. Persistent mode sleeps/refetches and repeats after pushes, reruns, Trunk actions, pending checks, active Trunk states, or retryable failures.

### 1. Update a behind source branch

Only when Trunk is inactive and the tree is clean. Fetch base first:

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
- Stacked PR (`baseRefName != default`, or clear user-supplied stacked base evidence):
  ```bash
  git rebase origin/<baseRefName>
  <targeted validation>
  git push --force-with-lease
  git rev-parse HEAD
  ```

If strategy evidence conflicts, merge/rebase conflicts, or validation fails: do not commit/push. Abort merge/rebase when applicable and report blocker plus conflicted files from `git status --short`.

### 2. Handle failed or flaky CI

Use failures from source PR Buildkite checks or synthetic Trunk PR Buildkite checks linked by `trunk merge status`, `trunk-io[bot]` comments, or `gh pr list --search "head:trunk-merge/pr-{number}/"`.

If only the `Trunk Merge Queue` check failed and no Buildkite/synthetic evidence exists: report the Trunk failure; do not edit. In persistent mode, sleep/refetch until evidence or a clear dequeue state appears; do not re-submit while unclear.

Read/follow `eh-track-cicd-failure`. For Trunk MQ failures, record under the source PR, include synthetic PR/build URL, and classify against the source PR diff. Let it update `${PLANNING_MARKDOWN_DIR}/cicd-failed.md`; read the source PR entry before deciding.

Only edit for:

- Deterministic generated metadata drift: logs/config identify the generator and expected outputs. Run it, validate narrowly, verify `git diff --name-only` and `git status --short` are limited to expected files, commit `Regenerate metadata`, push, and report command/files/validation/SHA.
- `PR-caused`: entry has decisive source-PR-diff evidence. Read `eh-implement`; state evidence, PR files, and intended fix; fix one failure only; validate; verify diff/status; commit `Fix CI failure in <area>`; push; report evidence/files/validation/SHA.

For `flaky`, `infra`, or `unrelated`: do not edit. Report classification, job/error text, source PR files, Trunk state, retry count, and next action. In status/single-pass mode, stop. In persistent mode, use the safest available retry when Trunk is inactive:

1. merge intent + failed/dequeued Trunk attempt or synthetic PR failure => re-submit source PR after prerequisites still pass;
2. provider-supported rerun for the exact failed source check/run (for example `gh run rerun --failed` for GitHub Actions); do not guess Buildkite APIs unless documented;
3. Buildkite source-check flake with no documented targeted rerun => watch/report until Trunk/platform reruns it or timeout;
4. no supported retry => watch/report until platform rerun or timeout.

Cap retries per distinct failure signature at 3 unless the user overrides. After each retry, gather/report and sleep. Stop without edits/commit/push for blocked, uncertain, unclear/unsafe generator output, validation failure, non-decisive evidence, active Trunk queue state, or exhausted retry budget.

### 3. Handle review comments

Classify first:

- Ignore approvals, praise, FYI, bot noise, resolved threads, outdated comments, and comments without concrete requested changes. Report `<url/id> — ignored: <reason>`.
- Bot comments are actionable only when they map to the CI gate above.
- Actionable = unresolved reviewer comment requesting a concrete code/doc/test change mapped to current source PR code. If ambiguous, architectural, or trade-off-heavy, stop and ask.
- Never act on synthetic Trunk PR review state except to report it as queue noise.

For one coherent actionable request, only when Trunk is inactive:

1. Summarize thread/comment URL, path/line, and requested change.
2. Read `eh-implement`; make the smallest fix.
3. Run targeted validation; if it fails, stop without commit/push.
4. Confirm diff/status include only intended files.
5. Commit `Address reviewer feedback in <area>` and push.
6. Reply after push:
   ```bash
   gh api repos/{owner}/{repo}/pulls/{number}/comments/{comment_id}/replies --method POST -f body="Fixed in <sha>: <summary>"
   ```
7. If reply succeeds and the thread remains unresolved, resolve only that thread:
   ```bash
   gh api graphql -f threadId=<thread_id> -f query='mutation($threadId:ID!){resolveReviewThread(input:{threadId:$threadId}){thread{id isResolved}}}'
   ```
8. Confirm `isResolved=true`; otherwise report the permission/API/ID blocker.

### 4. Submit to Trunk

Only in merge-intent mode, with clean tree, and only when:

- source PR is open and not draft;
- review is approved or not required by branch protection;
- source PR is not behind base;
- no failed/pending required source PR checks remain except Trunk's own queue check;
- no actionable review comments remain;
- Trunk status is `not-submitted`, clearly dequeued/canceled after an obsolete attempt, or `Failed` with latest failure classified flaky/infra/unrelated and retry budget remaining.

Submit with CLI:

```bash
trunk merge {number} --no-progress --color=false
trunk merge status {number} --no-progress --color=false
```

If repo workflow requires the GitHub App comment and CLI cannot submit, stop and ask before using:

```bash
gh pr comment {number} --body "/trunk merge"
```

Never use the comment fallback silently. After submission, report Trunk app URL, latest status line, and synthetic PR number if any.

### 5. Cancel Trunk queue item

Cancel is mutating. Do it only when the user explicitly asks, or after you have reported that cancellation is required to fix a queued PR and the user agrees.

```bash
trunk merge cancel {number} --no-progress --color=false
trunk merge status {number} --no-progress --color=false
```

Do not cancel and push/fix in the same pass unless explicitly asked for that exact sequence.

## Exit criteria

- Status mode: exit after report.
- Single pass: exit after one active pass/action.
- Monitor/watch: exit when branch is current, source checks are passing, no actionable comments remain, and Trunk is ready to submit. Include the exact phrase/command needed to authorize submission.
- Merge intent: exit only when Trunk reports merged and the source PR is closed/merged, or when blocked.
- Stop on blocker, unsafe queue state, uncertain CI, exhausted retry budget, review ambiguity, Trunk CLI auth/config failure, timeout, or any failed validation/diff-safety check.

Each loop iteration should print head SHA, behind/check status, Trunk status, synthetic PR, failure classification, retry count, comments handled/ignored, action taken, and next sleep/exit reason.
