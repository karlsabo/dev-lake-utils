---
name: eh-pr-review
description: Reviews a pull or merge request. Use when asked for code review or given a PR/MR number or URL.
user-invocable: true
allowed-tools: Bash(gh *), Bash(glab *), Bash(git *), Read, Glob, Grep, Write, Edit, Task(subagent_type=Explore *)
---

# PR/MR Review

Review the requested change and produce a planned-comments document. By default, iterate with the user before staging feedback as a GitHub pending review or GitLab draft notes. An authorized calling workflow may approve staging and publication for the current run instead of pausing. Never publish, approve, or request changes without explicit authorization.

## Review standard

- Prioritize correctness, regressions, security, data integrity, compatibility, and meaningful test gaps. Style and structural feedback require a concrete maintenance or correctness cost.
- Every finding needs evidence from the requested head: identify the relevant code, a concrete trigger or scenario, and its impact. Verify uncertainty with source tracing, focused tests, or documentation when practical. Drop speculation rather than disguising it as a question.
- Ask a question when the missing information is author intent or context. State a demonstrated defect directly and neutrally; do not soften it into a rhetorical question.
- Do not manufacture findings, repeat existing review threads, or flag unrelated pre-existing problems. No category needs a comment, and a clean review is a valid result.
- Be concise and specific. Explain the concern before prescribing a solution; suggest an exact implementation only when it is obvious and low-risk.

## 1. Resolve the request and snapshot its head

Resolve the platform, project, host, and request from the supplied URL. Otherwise use the checkout's upstream/push remote, then `origin`; a bare number means a GitHub PR number or GitLab MR **IID**. For an unspecified request, detect one on the current branch and confirm its source repository and branch. Use `gh -R owner/repo` or `glab -R host/group/project` when the URL names a different project. Ask when the host, base, or repository is ambiguous.

Collect the description, title, author, base/head branches and SHAs, URL, full diff, changed paths, and commit messages. The description and commits explain intent; CI status and diff statistics are optional context, not review evidence.

Typical commands:

```bash
gh pr view {number} --json body,title,author,baseRefName,baseRefOid,headRefName,headRefOid,url,commits
gh pr diff {number}
gh pr diff {number} --name-only

glab mr view {number} --output json
glab mr diff {number} --color=never
glab api "projects/{url_encoded_project}/merge_requests/{number}/commits" --paginate
```

For GitLab, use `--hostname {host}` when needed and obtain paths from the diff or the MR `diffs` API. Handle old and new paths for renames and deletions. Read existing review comments when necessary to avoid duplicates.

If there is no hosted request, review uncommitted changes with `git status --short`, `git diff HEAD`, and relevant untracked files. If the tree is clean and the branch is ahead of an unambiguous base, offer a branch review using its merge-base diff. Otherwise ask what to review. A failed CLI lookup does not prove there is nothing to review.

Verify that local files represent the requested head before using them as evidence. If not, safely check out the request without disturbing existing work or read source at that head through the host API. Never substitute local contents from another commit.

Derive `{comments_path}` once and reuse it:

- Hosted: `${PLANNING_MARKDOWN_DIR}/{platform}-{host}-{project_slug}-{number}-{short_head_sha}-planned-comments.md`.
- Local: `${PLANNING_MARKDOWN_DIR}/uncommitted-{repo_slug}-{branch_slug}-{timestamp}-planned-comments.md`, using `date +%Y%m%d-%H%M%S` and `detached` for an empty branch name.
- Include the full namespace in project slugs and replace characters outside `[A-Za-z0-9._-]` with `-`.

## 2. Build enough context

Start with the complete diff, then read the changed source and tests at the reviewed head. Read entire small and medium files when useful. For large or generated files, read the changed regions and the definitions needed to understand them; do not let an arbitrary line limit replace dependency tracing.

Follow changed behavior across relevant callers, implementations, tests, configuration, schemas, and public contracts. Use the diff alone for deleted content or when no better source view is available. Do not modify the implementation to resolve a review finding unless the user separately asks for fixes.

Assess risks relevant to the change:

- **Behavior:** logic, edge cases, error handling, state and concurrency, security and privacy.
- **Compatibility:** API/schema/data migrations, rollback and rollout, ownership and dependency boundaries.
- **Tests:** whether claims and risky paths are verified at the appropriate level; tests that pass without asserting the behavior.
- **Maintainability:** misleading abstractions, duplication of the same knowledge, unnecessary coupling, or dead code introduced by this change.

Consolidate findings with the same root cause and order them by impact. Do not apply a comment quota or convert every checklist item into feedback.

## 3. Write the planned comments

The document is the exact feedback candidate, not an analysis log. Keep these headings, including an empty `Inline Comments` section when there are no findings:

```markdown
# {PR #{number} | MR !{number}} — Planned Comments

**Reviewed head:** `{head_sha}`

## Overall PR Comment

{Optional concise text to stage. Avoid repeating inline findings.}

## Inline Comments

### {Short descriptive label}

**File:** `{repository_relative_path}`
**Line:** {line_number}
**Side:** {RIGHT for new code, LEFT for deleted code}

{Exact, self-contained Markdown comment body.}
```

For each finding, make the trigger and consequence easy to understand. For a testing gap, give one useful example as setup/action/assertion and distinguish it from existing coverage; do not invent a bug merely to justify a test. Use code suggestions only when the exact replacement is known. Plain Markdown without blockquote prefixes is copy/paste-ready for either host.

The overall comment may be empty. Do not add a generic opener out of habit. If the change is clean, say so in the user-facing assessment rather than inventing inline comments.

## 4. Verify and calibrate the draft

Perform a second evidence-and-calibration pass. For a nontrivial or high-risk review, use a fresh reviewer/subagent when the harness supports one and waiting for it; otherwise perform the pass yourself. A second model or a particular launcher is not required. Give a subagent the request URL or local diff, exact head SHA, and document path so it can inspect source evidence rather than reviewing wording alone. Wait for it to finish.

Ask the reviewer to:

1. Verify each finding, path, line, and side against the exact head and inspect enough source/tests to challenge it.
2. Drop unsupported, duplicate, out-of-scope, or low-value comments and revise unclear impact.
3. Check that demonstrated bugs are stated directly, genuine questions are identifiable, and testing examples are concrete.
4. Preserve the document structure and make no posts. If the reviewer cannot edit, return proposed changes for the parent to apply.

Re-read the final document after this pass and after every user-requested edit. Share its absolute path, reviewed head, a brief assessment, checks run or not run, and the verification outcome. Unless a calling workflow preauthorized staging, iterate with the user until the feedback is approved for staging. Local reviews stop after this iteration.

## 5. Stage feedback without publishing

Re-read `{comments_path}` and re-fetch the hosted head immediately before staging. If it changed, stop and review the new head; never reuse line mappings from the old diff.

### GitHub

Create a pending review with `POST repos/{project}/pulls/{number}/reviews` through `gh api --method POST --input {json_file}`. Include the reviewed `commit_id`, optional overall `body`, and `comments`; each inline comment needs `path`, `line`, `side`, and `body`. Omit `event` entirely—`PENDING` is not a valid create-review event. Save the returned review ID.

If atomic creation fails, create the pending review without inline comments, then add each through `POST repos/{project}/pulls/{number}/comments` with `commit_id`, `pull_request_review_id`, `path`, `line`, `side`, and `body`. Skip invalid positions, report them, and do not turn them into public fallback comments.

### GitLab

Create draft notes individually with `POST projects/{url_encoded_project}/merge_requests/{number}/draft_notes` through `glab api`. The overall comment is a separate draft note. For inline notes, supply the note and a text position using the reviewed diff's `base_sha`, `start_sha`, and `head_sha`, old/new paths, and the appropriate old or new line. Save every created draft-note ID. Skip invalid positions and report them.

Never use normal note/discussion endpoints or `glab mr note` while staging: they publish immediately. Report what was staged, IDs, and skipped comments.

## 6. Publish only when authorized

Re-read the document and verify the head again. Publish only when the user or authorized calling workflow explicitly requests it.

- **GitHub:** submit the saved pending review through `POST repos/{project}/pulls/{number}/reviews/{review_id}/events` with `COMMENT`, `APPROVE`, or `REQUEST_CHANGES`. Use an explicitly requested event; for an unqualified publish/submit request, use `COMMENT`. Approval and change requests require their own explicit intent.
- **GitLab:** publish only the draft IDs created for this review through each draft note's `publish` endpoint. Bulk publish also publishes pre-existing drafts; do not use it unless publishing all drafts is explicitly intended. Approval is a separate action requiring explicit authorization.

Confirm published/submitted feedback and provide the PR/MR URL plus IDs or URLs when available.
