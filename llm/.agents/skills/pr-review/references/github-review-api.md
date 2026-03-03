# GitHub Review API Reference

All commands use `gh api` to interact with the GitHub REST API. Replace `{owner}/{repo}` and `{number}` with actual
values.

---

## Gathering PR Data

### PR metadata (JSON)

```bash
gh pr view {number} --json title,author,baseRefName,headRefName,additions,deletions,changedFiles,state,statusCheckRollup,url
```

### Full diff

```bash
gh pr diff {number}
```

### Changed file list

```bash
gh pr diff {number} --name-only
```

### Commit history

```bash
gh pr view {number} --json commits --jq '.commits[] | "\(.oid[:7]) \(.messageHeadline)"'
```

### Owner/repo (for API calls)

```bash
gh repo view --json nameWithOwner -q .nameWithOwner
```

### PR comments (existing review comments)

```bash
gh api repos/{owner}/{repo}/pulls/{number}/comments
```

### PR reviews (existing reviews)

```bash
gh api repos/{owner}/{repo}/pulls/{number}/reviews
```

---

## Creating a Pending Review

### Primary method: single atomic call

Create the review with all inline comments in one API call. This is the preferred method because it's atomic — either
all comments are posted or none are.

```bash
gh api repos/{owner}/{repo}/pulls/{number}/reviews \
  --method POST \
  --input - <<'EOF'
{
  "event": "PENDING",
  "body": "The overall review comment that appears at the top.",
  "comments": [
    {
      "path": "src/foo/bar.py",
      "line": 42,
      "body": "Bug: this will fail when `x` is None.\n\nSuggest adding a guard:\n```python\nif x is not None:\n    ...\n```"
    },
    {
      "path": "src/foo/baz.py",
      "line": 15,
      "body": "Nit: consider renaming `val` to `value` for clarity."
    }
  ]
}
EOF
```

**Key fields:**

- `event`: MUST be `"PENDING"` — creates a draft review, does not submit
- `body`: The overall review comment (appears at the top of the review)
- `comments[].path`: File path relative to repo root
- `comments[].line`: The line number in the **new version** of the file (right side of diff). For deleted lines, use
  `side: "LEFT"` (see multi-line comments below)
- `comments[].body`: The comment text (supports GitHub-flavored markdown)

### Multi-line comments

To highlight a range of lines:

```json
{
  "path": "src/foo/bar.py",
  "start_line": 10,
  "line": 15,
  "start_side": "RIGHT",
  "side": "RIGHT",
  "body": "This entire block could be simplified."
}
```

- `start_line` + `line` define the range
- `side`: `"RIGHT"` for additions/unchanged, `"LEFT"` for deletions
- `start_side`: side for the start of the range (usually matches `side`)

### Comments on deleted lines

For lines that only appear on the left side of the diff (deletions):

```json
{
  "path": "src/foo/bar.py",
  "line": 42,
  "side": "LEFT",
  "body": "Was this intentionally removed?"
}
```

---

## Fallback Method: Empty Review + Individual Comments

If the atomic call fails (e.g., one bad comment position causes the whole request to fail), use this two-step approach:

### Step 1: Create empty pending review

```bash
gh api repos/{owner}/{repo}/pulls/{number}/reviews \
  --method POST \
  --input - <<'EOF'
{
  "event": "PENDING",
  "body": "The overall review comment."
}
EOF
```

This returns a review object with an `id` field. Save it as `{review_id}`.

### Step 2: Add comments individually

```bash
gh api repos/{owner}/{repo}/pulls/{number}/comments \
  --method POST \
  --input - <<'EOF'
{
  "body": "Bug: this will fail when x is None.",
  "commit_id": "{latest_commit_sha}",
  "path": "src/foo/bar.py",
  "line": 42,
  "side": "RIGHT",
  "pull_request_review_id": {review_id}
}
EOF
```

**Note:** When using the fallback method, each comment requires `commit_id` (the HEAD commit of the PR) and
`pull_request_review_id`.

Get the latest commit SHA:

```bash
gh pr view {number} --json commits --jq '.commits[-1].oid'
```

This approach lets you skip individual comments that fail while still posting the rest.

---

## Submitting a Pending Review

**Only do this when the user explicitly asks to submit.**

```bash
gh api repos/{owner}/{repo}/pulls/{number}/reviews/{review_id}/events \
  --method POST \
  --input - <<'EOF'
{
  "event": "COMMENT",
  "body": "Optional additional text appended to the review."
}
EOF
```

**Event types:**

- `COMMENT` — safest, leaves feedback without approving or blocking
- `APPROVE` — approves the PR
- `REQUEST_CHANGES` — blocks the PR until changes are made

Default to `COMMENT` unless the user specifies otherwise.

### Finding the pending review ID

If you need to find a pending review you created:

```bash
gh api repos/{owner}/{repo}/pulls/{number}/reviews \
  --jq '.[] | select(.state == "PENDING") | .id'
```

---

## Deleting a Pending Review

If something goes wrong and you need to start over:

```bash
gh api repos/{owner}/{repo}/pulls/{number}/reviews/{review_id} \
  --method DELETE
```

---

## Troubleshooting

| Error                                            | Cause                                         | Fix                                                                                                                                                                                                      |
|--------------------------------------------------|-----------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `422 Unprocessable Entity` on comment            | Line number not in the diff hunk              | Use a line number that appears in the diff. Check the diff to find valid line numbers. If the line isn't in the diff, the comment can't be placed inline — suggest the user add it as a general comment. |
| `422` with "pull_request_review_id is not valid" | Review ID doesn't match a pending review      | Re-fetch pending reviews and use the correct ID                                                                                                                                                          |
| `404 Not Found`                                  | Wrong owner/repo or PR number                 | Verify with `gh repo view` and `gh pr view`                                                                                                                                                              |
| `401 Unauthorized`                               | `gh` not authenticated or lacks scope         | Run `gh auth status` to check                                                                                                                                                                            |
| `422` with "event is not valid"                  | Trying to submit with wrong event string      | Use exactly: `PENDING`, `COMMENT`, `APPROVE`, or `REQUEST_CHANGES`                                                                                                                                       |
| Comment appears as "outdated"                    | PR was force-pushed after comment was created | Use the latest commit SHA; re-fetch if needed                                                                                                                                                            |

### Line number mapping tips

- The `line` field refers to the line number in the **file** (not the diff position)
- For new/modified lines: use the line number as it appears in the new version of the file (right side)
- For deleted lines: use the line number as it appeared in the old version (left side) with `"side": "LEFT"`
- If a line isn't part of the diff context (not added, removed, or within the diff's context lines), you **cannot**
  place an inline comment on it — use a file-level or PR-level comment instead

### File-level comments (when line targeting fails)

If you can't target a specific line, omit `line` and use `subject_type`:

```json
{
  "path": "src/foo/bar.py",
  "body": "General comment about this file.",
  "subject_type": "file"
}
```
