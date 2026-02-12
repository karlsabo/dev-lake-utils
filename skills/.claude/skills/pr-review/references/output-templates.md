# Output Templates

Two documents are produced per review. Both are written to `$HOME/karl-backup/notebook/llm-planning/`.

---

## Review Analysis Document

**Filename:** `pr-{number}-{short-descriptor}-review.md`

The short descriptor is derived from the PR title or ticket (e.g., `grant-type`, `social-login-parser`,
`kong-plugin-refactor`). Use lowercase, hyphenated. If no clear descriptor, just use `pr-{number}-review.md`.

### Template

```markdown
# PR #{number} Review: {PR title}

**PR:** {url}
**Author:** {author}
**Branch:** `{head}` -> `{base}`
**Status:** {state} | CI: {check status}
**Files changed:** {count} (+{additions}, -{deletions})

---

## Overview

{1-3 sentence summary of what the PR does, its scope, and risk level.}

---

## {Finding Title} (P{priority})

**File:** `{file_path}:{line_range}`

{Detailed explanation of the finding. Include relevant code snippets using fenced code blocks. Explain WHY it's an issue, not just WHAT.}

{If applicable, show the current code:}
```python
# Current
def example():
    ...
```

{And the suggested fix:}

```python
# Suggested
def example():
    ...
```

**Recommendation:** {One-sentence actionable recommendation.}

---

{Repeat for each finding, ordered by priority (P0 first)}

---

## Summary

| Priority         | Issue               | Location         |
|------------------|---------------------|------------------|
| **P0 — Bug**     | {brief description} | `{file}:{lines}` |
| **P1 — Quality** | {brief description} | `{file}:{lines}` |
| **P2 — Testing** | {brief description} | `{file}:{lines}` |
| **P3 — Minor**   | {brief description} | `{file}:{lines}` |

```

### Guidelines

- Each finding gets its own `##` section with a descriptive title and priority tag
- Include enough code context that the finding is understandable without opening the file
- Show both "current" and "suggested" code when proposing changes
- The Summary table at the end provides a quick scannable overview
- If a finding references a principle (DRY, Orthogonality, etc.), name it but keep the explanation practical
- If the PR is clean, the document may have 0 findings — just the Overview and a note that the code looks good

---

## Planned Comments Document

**Filename:** `pr-{number}-planned-comments.md`

This is the lean deliverable — exactly what will be posted to GitHub. No analysis, just the comments.

### Template

```markdown
# PR #{number} — Planned Comments

---

## Overall PR Comment

> {The review body text. This appears at the top of the GitHub review.
> Keep it to 2-4 sentences. Acknowledge what the PR does well,
> then briefly mention what comments address.}

---

## Inline Comments

### Comment {n} — {short label} ({file_basename})

**File:** `{full_file_path}`
**Line:** {line_number} {optional: brief description of what's on that line}

> {The exact comment body that will be posted to GitHub.
> Supports GitHub-flavored markdown.
>
> Use `>` blockquote formatting for the entire comment body
> so it's visually distinct from the metadata.
>
> Include code suggestions in fenced blocks:
> ```python
> suggested_code()
> ```

> }

---

{Repeat for each inline comment, ordered by priority (P0 first)}

```

### Guidelines

- The overall PR comment sets the tone — be constructive and concise
- Each inline comment must be **self-contained** — a reader on GitHub should understand it without seeing the review analysis document
- Use the `>` blockquote for the comment body to visually separate it from the File/Line metadata
- Order comments by priority (P0 first), then by file order within the same priority
- The `{short label}` in the heading should indicate the category: "Bug:", "Nit:", "Question:", "Testing gap:", etc.
- Keep comment bodies focused — if a comment needs more than ~10 lines, consider splitting it or linking to further context

### Tone Guide

- **P0 (Bugs):** Direct but not alarming. "Bug: this will fail when..." not "CRITICAL BUG!!!"
- **P1 (Quality):** Suggestive. "Consider using..." or "This duplicates..."
- **P2 (Testing/Architecture):** Questioning. "Is there a reason..." or "Testing gap:..."
- **P3 (Minor):** Explicitly low-priority. "Minor:" or "Nit:" prefix
- **General:** Use "we" or impersonal voice ("this could be..." not "you should..."). Assume good intent. Acknowledge when something is pre-existing rather than introduced by the PR.
