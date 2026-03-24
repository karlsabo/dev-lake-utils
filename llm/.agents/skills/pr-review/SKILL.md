---
name: pr-review
description: Reviews a GitHub PR. Use when asked to review a PR, code review, or given a PR number/URL.
user-invocable: true
allowed-tools: Bash(gh *), Read, Glob, Grep, Write, Edit, Task(subagent_type=Explore *)
---

# PR Review Skill

You are conducting a thorough, structured code review of a GitHub pull request. You produce a planned comments document, iterate with the user, then create a pending GitHub review via `gh api`.

Keep comments, terse, and concise. Replace ‘you’ with ‘we’ What about renaming this variable to something more descriptive, like seconds_remaining? Respect the scope of the review Did you consider...

## Workflow

### Step 1: Identify the PR

Parse the argument to determine the PR number:

- If a number is given (e.g., `12345`), use it directly
- If a URL is given (e.g., `https://github.com/org/repo/pull/12345`), extract the number
- If no argument is given, auto-detect via `gh pr view --json number -q .number` on the current branch

Store the PR number as `{number}` for all later steps.

### Step 2: Gather metadata

Run these commands **in parallel** to collect PR context:

```bash
# Owner/repo
gh repo view --json nameWithOwner -q .nameWithOwner

# PR metadata
gh pr view {number} --json title,author,baseRefName,headRefName,additions,deletions,changedFiles,state,statusCheckRollup,url

# Full diff
gh pr diff {number}

# File list
gh pr diff {number} --name-only

# Commit history
gh pr view {number} --json commits --jq '.commits[] | "\(.oid[:7]) \(.messageHeadline)"'
```

From these, derive:

- `{owner}/{repo}`, for API calls
- PR title, author, branch info, stats
- The full diff and list of changed files
- Commit messages for understanding intent

### Step 3: Read changed files in full

For every file in the changed file list that exists locally, read the **entire file** using the `Read` tool (not just
the diff hunks). This provides the surrounding context that catches:

- Duplicate logic elsewhere in the same file
- Methods accidentally inserted inside other methods
- Naming inconsistencies with neighboring code
- Import or dependency issues are not visible in the diff

If a file is too large (>1000 lines), read the changed regions plus 100 lines of context above and below each hunk.

For files that don't exist locally (deleted files, or repo not checked out), rely on the diff.

### Step 4: Analyze through review lenses

Load `references/review-lenses.md` and systematically analyze the PR through each lens:

1. **Bugs & Correctness**, logic errors, edge cases, security issues
2. **Code Quality**, readability, DRY, naming, idioms
3. **Testing Gaps**, missing coverage, test quality, test ownership
4. **Architecture & Design**, coupling, cohesion, abstraction
5. **Redundancy**, dead code, duplicates, stale comments

**Calibration:** Not every PR needs comments in every category. A clean PR may only warrant an approval with a brief
note. Match comment volume to the risk and complexity of the change.

### Step 5: Create a planned comments document

Write the planned GitHub comments to:

```
$HOME/karl-backup/notebook/llm-planning/pr-{number}-planned-comments.md
```

Follow the format in `references/output-templates.md`. The planned comments document must include both of these sections:

- `## Overall PR Comment`, the terse review body that will appear at the top of the GitHub review
- `## Inline Comments`, the ordered list of inline comments with File, Line, and comment body

Keep the section headings exactly as defined in the template so later steps can review the same artifact shape every time. If there are no inline comments, still include `## Inline Comments` and leave it empty rather than changing the document contract.

Each inline comment should be self-contained and actionable. Use the priority from the analysis to order them.

### Step 6: Run a skeptic subagent pass and wait for it to finish

Compute the planned comments document path once and store it as `{planned_comments_path}`:

```text
$HOME/karl-backup/notebook/llm-planning/pr-{number}-planned-comments.md
```

Use that exact absolute path for the rest of this step. Do not continue if the file is missing; go back and fix Step 5 first.

Spawn exactly one subagent and give it this prompt, substituting `{planned_comments_path}` with the absolute path you computed:

```text
Review the Pull Request comments document at {planned_comments_path} with an eye of skepticism and cynicism.

1. Remove or rewrite comments that are weak, speculative, redundant, not actionable, or not well-supported by the PR.
2. Keep the tone constructive, but be skeptical about whether each comment should really be posted.
3. Ensure the `Overall PR Comment` is terse, concise, and does not repeat what is already covered by inline comments.
4. Preserve the existing document structure and section headings.

In your final response, state whether you changed the file and briefly summarize the changes.
```

Wait for the subagent to finish before moving on. Do not continue to Step 7 until the subagent has reported completion, even if it made no changes.

### Step 7: Inform user and wait

Present a summary to the user:

- Absolute path to the document
- A brief 1-2 sentence overall assessment

Ask the user to review the planned comments document and provide feedback.

### Step 8: User iteration

The user may:

- Ask to remove specific comments
- Ask to edit/soften/strengthen specific comments
- Ask to add new comments they thought of
- Ask to change priority or ordering
- Ask to restructure the overall comment

Apply all requested changes to the planned comments document. Show the user what changed.

Repeat until the user is satisfied.

### Step 9: Create a pending GitHub review

When the user says they're ready (e.g., "looks good," "post it," "create the review"), create the review using `gh api`.

Refer to `references/github-review-api.md` for the exact API calls.

**Critical rules:**

- **ALWAYS** use `"event": "PENDING"`, this creates a draft review the user can inspect on GitHub before submitting
- **NEVER** submit the review (no `APPROVE`, `REQUEST_CHANGES`, or `COMMENT` event) unless explicitly told to
- Tell the user the review is pending, and they need to submit it from the GitHub UI

**Error handling:**

- If a comment gets a 422 (invalid position), warn the user and skip that comment. Suggest they add it manually.
- If the entire review creation fails, fall back to creating an empty pending review first, then adding comments
  individually. See `references/github-review-api.md` for the fallback approach.

### Step 10: Optional submit

**Only** if the user explicitly says "submit" or "submit the review":

- Ask what event type they want: `COMMENT` (safest), `APPROVE`, or `REQUEST_CHANGES`
- Default to `COMMENT` if they don't specify
- Use `gh api` to submit the pending review with the chosen event
- Confirm submission and provide the PR URL

## Important Notes

- Always read full files, not just diffs, context matters
- Be constructive, not nitpicky, every comment should help the author
- Prioritize bugs over style, a bug matters more than a naming nit
- Include code suggestions where helpful, show, don't just tell
- Reference principles by name (DRY, Orthogonality, etc.) but don't be preachy
- If the PR is clean and well-written, say so, don't manufacture comments
