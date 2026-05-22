# PR Monitoring Skill Plan

## Goal

Create an LLM skill that monitors an open GitHub PR, addresses actionable reviewer feedback, handles CI failures safely, updates the branch when it is behind its root/base branch, commits and pushes safe fixes, posts reviewer replies for fixes, resolves fixed review threads, and keeps monitoring until the PR is clean or blocked.

## Context

- Existing agent skills live under `llm/.agents/skills/`; the new monitoring skill should follow the same markdown skill pattern at `llm/.agents/skills/eh-monitor-pr/SKILL.md`.
- CI failure investigation already has a dedicated skill: `llm/.agents/skills/eh-track-cicd-failure/SKILL.md`. It identifies the PR, reads `statusCheckRollup`, inspects Buildkite with `bk build view`, classifies failures, and appends/updates `${PLANNING_MARKDOWN_DIR}/cicd-failed.md`. The monitoring skill should load and follow this skill instead of duplicating Buildkite investigation logic.
- PR metadata and checks can be collected with existing `gh` patterns from `llm/.agents/skills/eh-pr-review/SKILL.md`, including `gh pr view ... --json ... statusCheckRollup` and `gh pr diff --name-only`.
- GitHub review/comment API usage is documented in `llm/.agents/skills/eh-pr-review/references/github-review-api.md`, including `gh api repos/{owner}/{repo}/pulls/{number}/comments` and `/reviews`. Resolving review threads requires GraphQL (`resolveReviewThread`), because REST review comments do not carry enough resolved-thread state.
- Implementation discipline and the one-acceptance-test slicing rule are documented in `llm/.agents/skills/eh-implement/SKILL.md` and `llm/notes.md`.
- Guardrail: “100% sure” cannot be literally proven for all CI failures. Operationalize it as an evidence gate: only auto-fix/commit/push when the failure is classified as PR-caused with decisive evidence tied to the PR diff, or deterministic generated metadata drift that can be regenerated and validated. Otherwise stop and report.
- “Root branch” means the PR base branch from GitHub (`baseRefName`) unless the user supplies another branch.
- The monitoring loop is a skill workflow, not a long-running daemon. It repeats with sleeps while the agent session is active.

## Resolved Decisions

1. **Branch update strategy:** the skill should infer the right strategy. Prefer merge for normal PRs targeting the repository default branch. If the PR is stacked or branched off another branch, prefer rebase onto that branch.
2. **Reviewer replies:** after pushing a fix for a review thread, the skill should post a concise GitHub reply explaining what changed.
3. **Review-thread resolution:** after pushing a fix for a review thread, the skill should mark that review thread resolved.
4. **Watch polling:** poll every 5 minutes and give up after 120 minutes unless the user overrides.
5. **Generated metadata drift:** deterministic generated metadata drift is auto-fixable.
6. **Skill location:** implement only in this repo at `llm/.agents/skills/eh-monitor-pr/SKILL.md`; do not copy it to the global skill directory as part of this plan.

## Acceptance Tests

1. **Detect PR work without mutation**
    - Given the agent is on a feature branch with an open PR,
    - when the user invokes the PR monitoring skill once,
    - then it reports the PR URL, branch/base branch, outstanding review/comment work, failing/pending checks, and whether the branch is behind the base branch without changing files, commits, or remote refs.

2. **Log CI failure before any fix**
    - Given the current PR has a failing Buildkite status check,
    - when the monitoring skill processes the failure,
    - then it runs the CI failure tracking workflow from `llm/.agents/skills/eh-track-cicd-failure/SKILL.md` and updates `${PLANNING_MARKDOWN_DIR}/cicd-failed.md` before attempting any code change.

3. **Refuse uncertain CI fixes**
    - Given the tracked CI failure is classified as flaky, infra, unrelated, or uncertain,
    - when the monitoring skill decides the next action,
    - then it does not edit, commit, or push code and instead reports the classification and evidence.

4. **Fix and push PR-caused CI failures**
    - Given the tracked CI failure is classified as PR-caused with decisive evidence tied to the PR diff,
    - when the monitoring skill applies a fix,
    - then it makes the smallest code change, runs targeted validation, commits with a clear message, pushes the branch, and records what was fixed.

5. **Auto-fix deterministic generated metadata drift**
    - Given CI failed because deterministic generated metadata is stale,
    - when the monitoring skill can identify the generator command and expected generated outputs,
    - then it regenerates the metadata, validates the result, commits, pushes, and records the generated files that changed.

6. **Update default-branch PRs by merge**
    - Given the PR branch targets the repository default branch, is behind the base branch, and has no local uncommitted changes,
    - when the monitoring skill updates the branch,
    - then it fetches origin, merges `origin/<baseRefName>` into the PR branch, runs conflict-safe validation, pushes normally, and reports the new head commit.

7. **Update stacked PRs by rebase**
    - Given the PR branch targets a non-default base branch or is otherwise clearly identified as stacked, is behind that base branch, and has no local uncommitted changes,
    - when the monitoring skill updates the branch,
    - then it fetches origin, rebases onto `origin/<baseRefName>`, runs conflict-safe validation, pushes with `--force-with-lease`, and reports the new head commit.

8. **Stop on unsafe branch update**
    - Given the PR branch is behind its base branch but the update has conflicts, local uncommitted changes, or conflicting evidence about whether the PR is stacked,
    - when the monitoring skill attempts to update,
    - then it stops before committing or pushing and reports the exact blocker.

9. **Address actionable reviewer feedback**
    - Given the PR has unresolved reviewer comments that request a code change,
    - when the monitoring skill processes comments,
    - then it summarizes the comments, applies the smallest fix for one coherent request, validates it, commits, pushes, and records which comment/thread was addressed.

10. **Reply to fixed reviewer feedback**
    - Given the monitoring skill pushed a fix for an actionable review thread,
    - when the push succeeds,
    - then it posts a concise reply to that GitHub review thread describing the fix.

11. **Resolve fixed review thread**
    - Given the monitoring skill posted a reply for a pushed fix to an actionable review thread,
    - when the review thread is still unresolved,
    - then it marks that thread resolved in GitHub.

12. **Ignore non-actionable comments**
    - Given the PR has comments that are approvals, praise, FYI, bot noise, or already-resolved discussion,
    - when the monitoring skill processes comments,
    - then it does not make changes for those comments and reports why they were ignored.

13. **Keep monitoring until clean or blocked**
    - Given the monitoring skill is invoked with watch mode enabled,
    - when it finishes one action and pushes or observes pending checks,
    - then it sleeps for 5 minutes, refetches PR state, repeats the workflow, and exits only when the PR has no actionable comments, no failed checks, no behind-base condition, when it hits a documented blocker, or when 120 minutes have elapsed.

## Stories

### 1. Create a non-mutating PR monitoring status pass ✅ Done

**Acceptance criteria:** Given the agent is on a feature branch with an open PR, when the user invokes the PR monitoring skill once, then it reports the PR URL, branch/base branch, outstanding review/comment work, failing/pending checks, and whether the branch is behind the base branch without changing files, commits, or remote refs.

**Expected edits:**

- Add `llm/.agents/skills/eh-monitor-pr/SKILL.md`.
- Possibly update any skill index/sync docs if this repo has one; none was found in the current shallow scan.

**Scope:**

- In: skill metadata, invocation purpose, read-only PR discovery commands, read-only status report format, explicit “no mutation in status pass” rule.
- Out: fixing code, committing, pushing, branch updates, watch loop.

**Notes:**

- Use `gh pr view --json number,url,title,baseRefName,headRefName,statusCheckRollup,mergeStateStatus,reviewDecision,isDraft,state` as the first tracer bullet.
- Use `gh repo view --json nameWithOwner -q .nameWithOwner` before `gh api` calls, following `llm/.agents/skills/eh-pr-review/references/github-review-api.md`.
- Fetch comments/reviews with:
    - `gh api repos/{owner}/{repo}/pulls/{number}/comments`
    - `gh api repos/{owner}/{repo}/pulls/{number}/reviews`
- Fetch review-thread resolution state with GraphQL:
    - `repository(owner,name).pullRequest(number).reviewThreads(first:100) { nodes { id isResolved comments(first:100) { nodes { id body author { login } path line url outdated createdAt } } } }`
- Determine the default branch with `gh repo view --json defaultBranchRef --jq .defaultBranchRef.name`.
- This story creates the safe observation surface before any automation mutates the branch.

### 2. Delegate CI failure logging to the existing tracking skill ✅ Done

**Acceptance criteria:** Given the current PR has a failing Buildkite status check, when the monitoring skill processes the failure, then it runs the CI failure tracking workflow from `llm/.agents/skills/eh-track-cicd-failure/SKILL.md` and updates `${PLANNING_MARKDOWN_DIR}/cicd-failed.md` before attempting any code change.

**Expected edits:**

- Update `llm/.agents/skills/eh-monitor-pr/SKILL.md`.

**Scope:**

- In: detection of failed Buildkite checks from `statusCheckRollup`, mandatory execution of the `eh-track-cicd-failure` workflow, requirement to read the resulting `${PLANNING_MARKDOWN_DIR}/cicd-failed.md` entry before deciding next action.
- Out: fixing CI failures, classifying failure independently, changing `eh-track-cicd-failure` behavior.

**Notes:**

- `llm/.agents/skills/eh-track-cicd-failure/SKILL.md` already includes the required Buildkite commands, PR diff comparison, and classification labels. Do not duplicate that logic in the monitor skill.
- The monitor should treat the tracking file as the handoff artifact from investigation to action.
- This must run before any fix attempt so the failure reason is recorded even if the later fix is interrupted.

### 3. Add the CI safety gate for uncertain or unrelated failures ✅ Done

**Acceptance criteria:** Given the tracked CI failure is classified as flaky, infra, unrelated, or uncertain, when the monitoring skill decides the next action, then it does not edit, commit, or push code and instead reports the classification and evidence.

**Expected edits:**

- Update `llm/.agents/skills/eh-monitor-pr/SKILL.md`.

**Scope:**

- In: explicit no-edit/no-commit/no-push rule unless the failure is PR-caused with evidence or deterministic generated metadata drift; final response template for blocked/ignored CI failures.
- Out: code fixes and branch updates.

**Notes:**

- This is the guardrail for the user’s “if and only if we are 100% for sure caused by changes we made for this PR” requirement.
- Phrase the workflow as an evidence threshold, not confidence vibes. Evidence should cite the Buildkite job/error text, the PR-changed file(s), and why the diff plausibly caused or did not cause the failure.
- Deterministic generated metadata drift is handled by Story 5, not blocked by this story.

### 4. Fix, validate, commit, and push PR-caused CI failures ✅ Done

**Acceptance criteria:** Given the tracked CI failure is classified as PR-caused with decisive evidence tied to the PR diff, when the monitoring skill applies a fix, then it makes the smallest code change, runs targeted validation, commits with a clear message, pushes the branch, and records what was fixed.

**Expected edits:**

- Update `llm/.agents/skills/eh-monitor-pr/SKILL.md`.
- Reference `llm/.agents/skills/eh-implement/SKILL.md` as the implementation discipline for the fix loop.

**Scope:**

- In: one-failure-at-a-time fix loop, pre-change evidence summary, targeted validation, `git status --short`, commit, push, monitoring summary.
- Out: speculative refactors, fixing unrelated failures, broad cleanup, submitting the PR.

**Notes:**

- Follow `llm/.agents/skills/eh-implement/SKILL.md`: one acceptance test/change at a time, narrow tests first, self-review before committing.
- Required pre-commit checks in the skill workflow:
    - `git diff --name-only` to confirm only intended files changed.
    - Narrow validation that proves the failure path is fixed.
    - `git status --short` before commit.
- Commit message should name the observable fix, e.g. `Fix CI failure in <area>`.
- After pushing, the watch loop must refetch PR state instead of assuming the new checks passed.

### 5. Auto-fix deterministic generated metadata drift ✅ Done

**Acceptance criteria:** Given CI failed because deterministic generated metadata is stale, when the monitoring skill can identify the generator command and expected generated outputs, then it regenerates the metadata, validates the result, commits, pushes, and records the generated files that changed.

**Expected edits:**

- Update `llm/.agents/skills/eh-monitor-pr/SKILL.md`.

**Scope:**

- In: recognizing deterministic generated metadata drift, finding the existing generator command from repo docs/build files/CI logs, running it, validating that only expected generated outputs changed, committing, pushing.
- Out: guessing unknown generator commands, accepting broad unrelated regenerated diffs, fixing non-deterministic generated output.

**Notes:**

- The user explicitly wants deterministic generated metadata drift to be auto-fixable.
- Treat it as auto-fixable only when all of these hold:
    - The CI log or repo config identifies the generator/update command.
    - The changed files are generated outputs expected from that command.
    - The diff is limited to generated metadata or directly related lock/output files.
    - Targeted validation passes.
- If the generator command is unclear or the diff sprawls into source code unexpectedly, stop and report the blocker.

### 6. Update default-branch PRs by merge ✅ Done

**Acceptance criteria:** Given the PR branch targets the repository default branch, is behind the base branch, and has no local uncommitted changes, when the monitoring skill updates the branch, then it fetches origin, merges `origin/<baseRefName>` into the PR branch, runs conflict-safe validation, pushes normally, and reports the new head commit.

**Expected edits:**

- Update `llm/.agents/skills/eh-monitor-pr/SKILL.md`.

**Scope:**

- In: detecting default-branch PRs, clean-worktree preflight, fetch, merge, validation, normal push.
- Out: rebasing default-branch PRs, manual conflict resolution, updating unrelated branches.

**Notes:**

- Infer default-branch PRs by comparing `baseRefName` from `gh pr view` with `defaultBranchRef.name` from `gh repo view`.
- Use explicit commands in the skill, for example:
    - `git status --short`
    - `git fetch origin <baseRefName>`
    - `git merge --no-edit origin/<baseRefName>`
    - targeted validation
    - `git push`
- Detect whether the branch is behind with `git merge-base --is-ancestor origin/<baseRefName> HEAD`; if the command fails, the PR branch does not contain the base branch tip.

### 7. Update stacked PRs by rebase ✅ Done

**Acceptance criteria:** Given the PR branch targets a non-default base branch or is otherwise clearly identified as stacked, is behind that base branch, and has no local uncommitted changes, when the monitoring skill updates the branch, then it fetches origin, rebases onto `origin/<baseRefName>`, runs conflict-safe validation, pushes with `--force-with-lease`, and reports the new head commit.

**Expected edits:**

- Update `llm/.agents/skills/eh-monitor-pr/SKILL.md`.

**Scope:**

- In: stacked-PR detection, clean-worktree preflight, fetch, rebase, validation, `--force-with-lease` push.
- Out: rebasing when stack evidence is ambiguous, manual conflict resolution.

**Notes:**

- Strong evidence of a stacked PR includes:
    - `baseRefName` is not the repository default branch.
    - The PR explicitly targets another feature branch.
    - Local branch/fork-point evidence clearly shows the branch was built on a non-default branch that is also the PR base.
- Use explicit commands in the skill, for example:
    - `git status --short`
    - `git fetch origin <baseRefName>`
    - `git rebase origin/<baseRefName>`
    - targeted validation
    - `git push --force-with-lease`
- If local evidence suggests the branch is stacked but the PR base is the default branch, stop and report the conflicting evidence instead of guessing.

### 8. Stop on unsafe branch update conditions ✅ Done

**Acceptance criteria:** Given the PR branch is behind its base branch but the update has conflicts, local uncommitted changes, or conflicting evidence about whether the PR is stacked, when the monitoring skill attempts to update, then it stops before committing or pushing and reports the exact blocker.

**Expected edits:**

- Update `llm/.agents/skills/eh-monitor-pr/SKILL.md`.

**Scope:**

- In: clean worktree check, conflict detection, ambiguous strategy detection, abort instructions, blocker report.
- Out: resolving conflicts automatically, stashing user changes without permission, force-pushing after an interrupted rebase.

**Notes:**

- Required guardrails:
    - Never stash, reset, rebase, or merge over uncommitted user work without explicit permission.
    - If rebase/merge conflicts occur, stop and report conflicted files. Do not invent conflict resolutions unless user asks.
    - If rebase started and conflicts, leave the repo state explicit in the report and tell the user the next safe commands, such as `git rebase --abort` or manual conflict resolution.

### 9. Address one coherent actionable reviewer request ✅ Done

**Acceptance criteria:** Given the PR has unresolved reviewer comments that request a code change, when the monitoring skill processes comments, then it summarizes the comments, applies the smallest fix for one coherent request, validates it, commits, pushes, and records which comment/thread was addressed.

**Expected edits:**

- Update `llm/.agents/skills/eh-monitor-pr/SKILL.md`.

**Scope:**

- In: fetch review comments/reviews, fetch unresolved review threads, classify actionable vs non-actionable, group one coherent requested change, implement one change, validate, commit, push.
- Out: resolving every comment in one commit, responding before a fix has been pushed, submitting reviews.

**Notes:**

- Review API evidence exists in `llm/.agents/skills/eh-pr-review/references/github-review-api.md`.
- Use GraphQL review threads for unresolved/resolved state. REST comments alone are insufficient.
- This story intentionally handles one coherent request at a time. “Fix all comments” usually hides multiple acceptance tests and creates messy commits.
- If a comment is ambiguous, architectural, or asks for a trade-off decision, the monitor should stop and ask rather than guessing.

### 10. Reply to fixed reviewer feedback ✅ Done

**Acceptance criteria:** Given the monitoring skill pushed a fix for an actionable review thread, when the push succeeds, then it posts a concise reply to that GitHub review thread describing the fix.

**Expected edits:**

- Update `llm/.agents/skills/eh-monitor-pr/SKILL.md`.

**Scope:**

- In: identify the review comment/thread fixed by the last commit, post a short reply after push, include enough context for the reviewer.
- Out: replying before a push succeeds, replying to non-actionable comments, marking resolved.

**Notes:**

- Use the GitHub REST reply endpoint when replying to a pull request review comment:
    - `gh api repos/{owner}/{repo}/pulls/{number}/comments/{comment_id}/replies --method POST -f body='Fixed in <sha>: <summary>'`
- Keep replies concise. Example: `Fixed in abc1234 by preserving the existing retry timeout when the override is absent.`
- If the API call fails, report the failure and do not mark the thread resolved.

### 11. Resolve fixed review threads ✅ Done

**Acceptance criteria:** Given the monitoring skill posted a reply for a pushed fix to an actionable review thread, when the review thread is still unresolved, then it marks that thread resolved in GitHub.

**Expected edits:**

- Update `llm/.agents/skills/eh-monitor-pr/SKILL.md`.

**Scope:**

- In: GraphQL review-thread resolution after a successful fix reply, confirmation that the thread is resolved.
- Out: resolving threads without a pushed fix, resolving ambiguous/disputed threads, resolving threads when the reply failed.

**Notes:**

- Use GraphQL mutation:
    - `resolveReviewThread(input: { threadId: $threadId }) { thread { id isResolved } }`
- Only resolve the specific thread addressed by the pushed fix.
- If GitHub permissions or GraphQL IDs are unavailable, stop and report the blocker instead of pretending the thread is resolved.

### 12. Ignore and report non-actionable comments ✅ Done

**Acceptance criteria:** Given the PR has comments that are approvals, praise, FYI, bot noise, or already-resolved discussion, when the monitoring skill processes comments, then it does not make changes for those comments and reports why they were ignored.

**Expected edits:**

- Update `llm/.agents/skills/eh-monitor-pr/SKILL.md`.

**Scope:**

- In: comment classification rules and report template.
- Out: code changes, GitHub replies, resolving conversations.

**Notes:**

- Separate this from actionable-comment handling because the expected behavior is “do nothing safely.”
- Keep the classification conservative. False positives here cause unwanted code churn.
- Treat bot comments as actionable only if they contain a concrete failure or generated-output instruction that maps to the CI safety gates above.

### 13. Add watch mode that loops until clean or blocked ✅ Done

**Acceptance criteria:** Given the monitoring skill is invoked with watch mode enabled, when it finishes one action and pushes or observes pending checks, then it sleeps for 5 minutes, refetches PR state, repeats the workflow, and exits only when the PR has no actionable comments, no failed checks, no behind-base condition, when it hits a documented blocker, or when 120 minutes have elapsed.

**Expected edits:**

- Update `llm/.agents/skills/eh-monitor-pr/SKILL.md`.

**Scope:**

- In: watch-loop order, 5-minute polling interval, 120-minute max duration, exit conditions, per-iteration summary.
- Out: background daemon/process manager, notifications, scheduled runs outside the active agent session.

**Notes:**

- Required loop order:
    1. Fetch PR state.
    2. If behind base, merge default-branch PRs or rebase stacked PRs; stop on unsafe update conditions.
    3. If checks failed, run CI tracking, then fix only if PR-caused with evidence or deterministic generated metadata drift.
    4. If actionable reviewer comments exist, address one coherent request, push, reply, and resolve the fixed thread.
    5. If checks are pending, sleep 5 minutes and refetch.
    6. Exit clean when checks pass, branch is current, and no actionable comments remain.
    7. Exit blocked when a safety gate blocks progress or 120 minutes elapse.
- After every commit/push, the next loop must refetch state instead of assuming GitHub/Buildkite status.
- Each loop iteration should print a compact summary: PR head SHA, branch status, check status, comments processed, action taken, and next sleep/exit reason.

## Suggested Sequence

1. Story 1: read-only status pass.
2. Story 2: CI logging delegation.
3. Story 3: CI safety gate.
4. Story 6 and Story 8: default-branch merge update and unsafe update stop.
5. Story 7: stacked-PR rebase update.
6. Story 4: PR-caused CI fix loop.
7. Story 5: generated metadata drift auto-fix.
8. Story 12 and Story 9: comment classification, then one actionable comment fix.
9. Story 10 and Story 11: reply to fixed feedback, then resolve the fixed thread.
10. Story 13: watch mode.

This sequence gets a safe tracer bullet first, then adds mutation only behind explicit evidence and clean-worktree guardrails. The branch-update stories come before the fix loop because a stale branch can invalidate both CI results and review-thread positions.

## Remaining Questions

No open product questions remain. The remaining work is implementation detail inside `llm/.agents/skills/eh-monitor-pr/SKILL.md`.
