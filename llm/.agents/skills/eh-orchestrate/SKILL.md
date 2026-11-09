---
name: eh-orchestrate
description: Implement a request in an existing suitable worktree or a new one, open a draft PR/MR, review and fix comments, then monitor and fix CI until the draft is otherwise mergeable or blocked. Use for end-to-end implementation, review, and CI follow-through.
---

# Orchestrate

The user authorizes publishing review comments without another approval prompt. Keep the PR/MR in draft. Do not approve, merge, enqueue, enable auto-merge, or mark it ready. "Mergeable" below means all non-draft prerequisites are satisfied; hand it to the user for the final decision.

## Start

- Get the request, base branch, and ticket link if needed. Ask about missing details you cannot infer. Preserve existing uncommitted work.
- Check `git worktree list --porcelain`, the current branch, and `git status --short` before choosing a worktree. Select reuse only if the current checkout is already a worktree on a suitable feature branch for this request (not the base/default branch) and any uncommitted changes can be safely preserved. If suitability or ownership of existing changes is unclear, ask rather than overwrite or discard them. Otherwise select creation of a dedicated worktree. Record the reuse/create decision for the implementation step and cleanup.
- Identify the host with `git remote -v`. Check `gh` or `glab` availability, help, and authentication. Stop if the host is unsupported or authentication fails.
- Spawn agents with a native tool only if it can set each agent's model and thinking level and wait for completion. Otherwise, use fresh `pi -p` processes. Check `pi --help` and model availability first. Pass each agent the needed paths and URL; agents do not share context. Load skills with absolute `--skill` paths derived from this skill's directory.
- Implementation and fixes: `--model deepseek-v4.1-flash-1017 --thinking high`. Reviews: `--model openai-codex/gpt-6-sol --thinking medium`.
- Confirm the target repo's required checks, CI host, and any merge queue or branch-protection requirements before calling the draft otherwise mergeable. Never use a successful local build as a substitute for required remote checks.

## Implement

1. Spawn an agent with sibling skills `eh-implement` and `eh-create-draft-pr`. Give it the selected worktree decision and original checkout. For reuse, tell it to use the current worktree and branch without creating another. For creation, tell it to create a new feature branch and dedicated worktree **before editing**. In the selected worktree, implement the request, run required validation, commit, push, and open a **draft** PR/MR. Require the worktree path, branch, validation result, and full PR/MR URL in its report.
2. Verify the pushed branch matches the worktree and the PR/MR is draft. Stop on failure; report the blocker and worktree path.

## Review and fix

Run one agent at a time. Stop after five review/fix rounds if comments persist. Leave the worktree for the user when blocked. A new push invalidates the previous head review; review the latest pushed head before finishing.

1. Spawn a fresh review agent in the selected worktree with sibling skill `eh-pr-review`. Give it the full URL. Authorize it to stage and publish actionable feedback for this run without another user pause. Publish comment-only feedback, never approval or change requests. Require the reviewed head SHA and posted comment IDs or URLs. Do not invent comments for a clean PR/MR.
2. Verify the comments were published on that head. If there are none, exit the loop. Stop if publication failed. Ignore unrelated, resolved, outdated, duplicate, and non-actionable comments. Assess bot findings by evidence rather than author type.
3. If there are comments, spawn a fix agent in the same worktree with sibling skill `eh-implement`. Give it the URL, head SHA, and exact comment IDs and text. Tell it to fix reasonable comments, validate, commit, push, and resolve addressed threads. For a comment it rejects, require a rationale on the thread; do not silently resolve it. Require a per-comment report with commit or rationale and resolution status.
4. Verify the push, validation, and comment status. Stop for substantive disagreement, a remaining actionable defect, or failed validation. If the fix is complete but thread resolution fails for permission or API reasons, record that explicitly as a remaining prerequisite or blocker, not as evidence of an unfixed defect. Review the new head again after a successful fix push.

## Monitor CI and fix

After review, and after every subsequent push, monitor the **latest source head SHA**. Run one agent at a time. Stop after five CI fix rounds, three retries per distinct flaky/infra failure, or two hours of waiting; report the remaining blocker instead of looping indefinitely.

1. Use the host CLI (`gh`/`glab`) to obtain the PR/MR head SHA, required check/job statuses and URLs, review state, unresolved threads, conflicts, and whether the branch is behind its base. On GitHub check both PR checks and any merge-queue checks required by the repo; on GitLab check the latest pipeline for the source head and its failed jobs. Do not mistake a pipeline for an older SHA, an optional failed job, or a skipped/canceled check for a passing required check. Wait for pending checks with bounded polling and refresh state after each status change.
2. For each failure, read the actual job log and classify it as deterministic code/config failure, flaky/infra, unrelated, or unknown. Check whether it reproduces on the base branch when relevant. Never change or disable CI gates, suppress static analysis, skip required checks, or claim success from a build with exclusions. Retry only a safely rerunnable flaky/infra job or pipeline via the host CLI; refresh the head/status afterward. For unknown failures or unsupported retries, stop with the evidence and blocker. If a mandatory baseline failure requires unrelated work beyond the request, identify the scope and report it rather than silently expanding the PR.
3. For an evidenced, in-scope deterministic failure, spawn a fix agent **in the selected worktree** with sibling skill `eh-implement`. Give it the PR/MR URL, head SHA, exact failed job URL and error, relevant repo instructions, and required validation. Tell it to fix the root cause (not weaken the gate), run the full required local validation, commit and push only on success, and keep the PR/MR draft. Require changed files, command results, and commit SHA. Verify the worktree is clean, the remote head matches, and the reported validation passed. If validation fails or no safe fix exists, stop and report the blocker; never paper over it by retrying unchanged deterministic CI.
4. After a fix push, wait for the new head's required CI and repeat classification if it fails. Run the review agent again on that head using the review-and-fix steps above; fix any new actionable comments and recheck CI after each push.
5. Use the completion checklist in Finish before calling the draft otherwise mergeable. If approvals, a merge queue, or changing draft status require user action, report that as a remaining prerequisite; do not perform those actions.

## Finish

- Confirm the latest pushed head was reviewed and its required CI passed; no actionable unresolved review threads, substantive disagreements, or conflicts remain; the branch is current if required; and the PR/MR is still draft. Record thread-resolution permission or API failures as remaining prerequisites, or as blockers when repository policy requires resolution. If any completion condition fails, report a blocker rather than calling the draft mergeable.
- Leave any reused worktree in place, even if clean and fully pushed. If this run created a worktree and it is clean and fully pushed, remove it from the original checkout with `git worktree remove <absolute-path>`. Never force removal or delete the branch. Leave it in place when blocked; report cleanup failures.
- Ask the user to review the draft; include its **full URL**, validation result, latest-head CI status, review/CI rounds, remaining user-only prerequisites, and cleanup status. If blocked, include the URL (if any), failing job/check and evidence, blocker, and worktree path instead.
