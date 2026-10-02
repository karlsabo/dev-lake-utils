---
name: eh-pr-review
description: Reviews a pull or merge request. Use when asked for code review or given a PR/MR number or URL.
user-invocable: true
allowed-tools: Bash(gh *), Bash(glab *), Bash(git *), Read, Glob, Grep, Write, Edit, Task(subagent_type=Explore *)
---

# eh-pr-review Skill

You are conducting a review of a pull/merge request (PR/MR). Never give ad hoc review findings: always write the planned comments document, run the subagent pass, re-read the document, then summarize. You produce a planned comments document, iterate with the user, then stage feedback as a pending review or draft notes. Do not publish without explicit authorization.

Keep comments terse, concise, and scoped. Make them sound like a thoughtful teammate, not a lint rule or generated template. Prefer natural, conversational wording and only use question-led phrasing when it fits the concern. Avoid commands and heavy phrasing ("must", "please fix", "before exposing"), and avoid canned openers repeated across comments ("Did you consider...", "Should this..."). Use "we" for shared ownership when needed, but do not force it into unnatural question openers.

Before keeping any comment, run a human-voice check: would this sound normal if pasted into Slack by a senior engineer? If it sounds robotic, rewrite it shorter and more directly. Prefer concrete wording tied to the code path, for example: "If Statsig errors here, do we want to fall back to `False` to keep the v3 path safe?" over "Should this return False if Statsig raises? The docstring says this MUST default..."

## Workflow

### Step 1: Identify the PR/MR

Resolve the hosting platform and repository from the URL if supplied; otherwise inspect `git remote -v` (prefer the upstream/push remote of the current branch, then `origin`). Both HTTPS and SSH remote URLs are valid. If the remote host is ambiguous or unsupported, ask rather than guessing.

- A URL identifies both the project and request: GitHub `/owner/repo/pull/{number}` or GitLab `/group/project/-/merge_requests/{iid}`. Run CLI commands against that project (`gh -R owner/repo` where supported, or `glab -R host/group/project`); don't silently use the current checkout if it differs.
- A bare number is scoped to the repository resolved above; use that PR number or MR **IID** (not GitLab's global MR ID).
- With no argument, detect the request on the current branch: `gh pr view --json number -q .number` for GitHub, `glab mr view "$(git branch --show-current)" --output json` for GitLab. Confirm its source branch/repository matches this checkout; never substitute a similarly numbered request from a different project.
- If no request is found, check `git status --short` and `git diff` (including staged changes). For uncommitted changes, use `uncommitted` and gather `git diff HEAD`, `git diff HEAD --name-only`, and full changed-file reads. If clean but the branch is ahead of its base branch, offer to review the branch diff as a local review instead. Otherwise ask for a request URL; don't claim there is nothing to review solely because `gh` failed on a GitLab remote.

Store `{platform}`, `{project}` (namespace/repo), `{host}`, and `{number}` (PR number or MR IID) for later steps. Derive `{comments_path}` once:

- Hosted review: `${PLANNING_MARKDOWN_DIR}/{platform}-{host}-{project_slug}-{number}-planned-comments.md`, where the project slug includes the entire namespace and repository (replace `/` with `-`).
- Local review: `${PLANNING_MARKDOWN_DIR}/uncommitted-{repo_slug}-{branch_slug}-{timestamp}-planned-comments.md`, using `basename "$(git rev-parse --show-toplevel)"`, `git branch --show-current` (or `detached`), and `date +%Y%m%d-%H%M%S`.
- Sanitize all filename slugs by replacing characters outside `[A-Za-z0-9._-]` with `-`.

Use `{comments_path}` for every later read, write, subagent prompt, user summary, and posting step. Do not reconstruct it later.

### Step 2: Gather metadata

For GitHub, use `gh repo view --json nameWithOwner -q .nameWithOwner`, `gh pr view {number} --json title,author,baseRefName,headRefName,additions,deletions,changedFiles,state,statusCheckRollup,url,commits`, `gh pr diff {number}`, and `gh pr diff {number} --name-only`. Scope commands to the resolved repository with `-R` when necessary.

For GitLab, use `glab mr view {number} --output json`, `glab mr diff {number} --color=never`, and `glab api "projects/{url_encoded_project}/merge_requests/{number}/commits" --paginate` (use `--hostname {host}` for self-hosted instances when not in that checkout). Obtain file names from the diff or `glab api "projects/{url_encoded_project}/merge_requests/{number}/diffs" --paginate` (the `.old_path`/`.new_path` fields); don't treat rename/delete paths as local files. For local changes use the local diff and git log against the base branch.

Collect title, author, branch info, stats if available, URL, the full diff, changed-file list, and commit messages. Verify local HEAD matches the review head before reading files or posting inline comments; if not, check out the request or read its files from the request head rather than reviewing unrelated local contents.

### Step 3: Read changed files in full

For every file in the changed file list that exists locally, read the **entire file** using the `Read` tool (not just the diff hunks). This provides the surrounding context that catches:

- Duplicate logic elsewhere in the same file
- Methods accidentally inserted inside other methods
- Naming inconsistencies with neighboring code
- Import or dependency issues are not visible in the diff

If a file is too large (>1000 lines), read the changed regions plus 100 lines of context above and below each chunk.

For files that don't exist locally (deleted files, or repo not checked out), rely on the diff.

### Step 4: Analyze through review lenses

Load `references/review-lenses.md` and systematically analyze the PR through each lens:

1. **Bugs & Correctness**, logic errors, edge cases, security issues
2. **Code Quality**, readability, DRY, naming, idioms
3. **Testing Gaps**, missing coverage, test quality, test ownership
4. **Architecture & Design**, coupling, cohesion, abstraction
5. **Redundancy**, dead code, duplicates, stale comments

**Calibration:** Not every PR needs comments in every category. A clean PR may only warrant an approval with a brief note. Match comment volume to the risk and complexity of the change.

### Step 5: Create a planned comments document

Write the planned comments to `{comments_path}`.

Follow the format in `references/output-templates.md` (use PR or MR and the appropriate number in the title). The planned comments document must include both of these sections:

Keep the section headings exactly as defined in the template so later steps can review the same artifact shape every time. If there are no inline comments, still include `## Inline Comments` and leave it empty.

Each inline comment should be self-contained and useful. Prefer explaining the observed behavior and impact over prescribing the exact fix unless the fix is trivial. Make the ask easy to picture: name the specific input or situation, what happens now (or what is untested), and what behavior or check would resolve the concern. For a testing gap, spell out one example test in plain English (setup/action/assertion) and say what the existing tests cover instead. Don't just say "cover the path" or "test persistence"; a reader should not have to ask what test you mean. Keep it short and avoid inventing a bug when the concern is only missing coverage.

### Step 6: Run a subagent pass and wait for it to finish

Spawn an agent pass using whatever the current harness actually supports. If a native subagent or Task tool exists, use it; prefer `Task(subagent_type=Explore *)` when available. If there is no native subagent tool, launch tmux session, start a new process, or run a non-interactive agent process such as `pi -p --tools read,edit,write "<prompt>"`; treat that subprocess as the subagent.

Set the subagent model to the same model you are when the harness allows it, and give it this prompt:

```text
Review the PR/MR comments document at {comments_path} with an eye of skepticism and cynicism.

1. Remove or rewrite comments that are weak, speculative, redundant, not actionable, or not well-supported by the PR.
2. Keep the tone constructive, but be skeptical about whether each comment should really be posted.
3. Ensure the `Overall PR Comment` is terse, neutral, and does not repeat what is already covered by inline comments. Prefer a short opener like "Couple of things to look at:" when there are comments.
4. Rewrite inline comments into the user's preferred style: human, specific, non-commanding, no "please fix", no overstatement. Use question-led phrasing only when it reads naturally; avoid robotic/canned sentence shapes.
5. Check that each comment makes the ask concrete enough to act on without a follow-up question. For testing gaps, name an example input, the operation, and the assertion; distinguish this from tests already present. Prefer plain English over shorthand such as "write/read path" or "persistence coverage".
6. Preserve the existing document structure and section headings.

In your final response, state whether you changed the file and briefly summarize the changes.
```

Wait for the subagent or subprocess to finish before moving on. Do not continue to Step 7 until it has reported completion, even if it made no changes.

### Step 7: Read the revised document, inform user, and wait

After the Step 6 subagent reports completion, read `{comments_path}` from disk again before you say anything to the user. Use the subagent contents of that file as the source of truth for the rest of the workflow; do not rely on the pre-subagent version from memory.

Present a summary to the user:

- Absolute path to the document
- A brief 1-2 sentence summary of the skeptic pass outcome, including whether the subagent changed the file
- A brief 1-2 sentence overall assessment based on the current contents of the revised document

Then ask the user to review the planned comments document and provide feedback.

### Step 8: User iteration

The user may:

- Ask to remove specific comments
- Ask to edit/soften/strengthen specific comments
- Ask to add new comments they thought of
- Ask to change priority or ordering
- Ask to restructure the overall comment

Apply all requested changes to the planned comments document. Show the user what changed.

Repeat until the user is satisfied.

### Step 9: Stage the review as pending/draft

When the user says they're ready (e.g., "looks good," "post it," "create the review"), read `{comments_path}` from disk again before proceeding.

- GitHub: create a pending review via `gh api`, omitting `event` entirely; `"event": "PENDING"` is rejected. If the atomic creation fails, follow the GitHub reference's fallback. If one inline comment has an invalid position, warn and skip it.
- GitLab: create **draft notes** via `glab api`, one per inline comment and one for the overall comment if nonempty. GitLab has no equivalent atomic pending review; track the created draft note IDs and failures, and never use `glab mr note` or the normal notes/discussions endpoints here (those publish immediately). If a line cannot be positioned, warn and skip it; don't post it as a public comment.
- A local/uncommitted review cannot be posted without a hosted request; stop after the document and user iteration.
- Do not publish or approve merely because the user said "post it" or "create the review"; those mean **stage draft feedback** in this workflow. If the user explicitly asks to submit in the same turn, stage first, then follow Step 10.
- Tell the user what was staged, any skipped comments, and that they need to publish/submit it in the host UI unless they explicitly asked you to do so.

### Step 10: Optional publish/submit

Only if the user explicitly asks to **submit/publish** the review or specifies an approval/change-request action:

- GitHub: if an event type is specified (`COMMENT`, `APPROVE`, `REQUEST_CHANGES`), use it. Otherwise ask which event they want; default to `COMMENT` if unspecified. Submit the pending review using `gh api` as described in the GitHub reference.
- GitLab: publish the draft notes via the GitLab draft-notes API after confirming which drafts are being published (bulk publish publishes **all** drafts on this MR, including pre-existing drafts). An approval is a separate action and requires an explicit request; don't assume GitHub event types map onto GitLab review actions. If the user asks to request changes, check the instance's support and clarify before acting.
- Confirm what was published/submitted and provide the PR/MR URL.

## Important Notes

- Always read full files, not just diffs, context matters
- Be constructive, not nitpicky, every comment should help the author
- Prioritize bugs over style, a bug matters more than a naming nit
- Start inline comments with the concern, not the prescription
- "Did you consider..." or "Should this..." are options, not defaults.
- Prefer comments that sound like normal engineering feedback: specific, plainspoken, and tied to the code path. Before keeping one, ask: "Would the author know exactly what I want them to check or change?" If not, add a concrete example rather than more abstract explanation.
- Avoid restating obvious text from nearby code/docstrings unless it is necessary to explain the risk.
- Good pattern: "If X happens here, do we want Y as the fallback?" Bad pattern: "Should this do Y? The docstring says... there does not appear..."
- Prefer "I believe..." only when the conclusion depends on surrounding routing/config and the uncertainty matters.
- Avoid asking for an exact implementation unless the fix is obvious and low-risk
- Reference principles by name (DRY, Orthogonality, etc.) only when it materially clarifies the comment
- If the PR is clean and well-written, say so, don't manufacture comments
