# Output Templates

One document is produced per review. Written to `$HOME/karl-backup/notebook/llm-planning/`.

## Overview

Concise summary of what the PR does, its scope, and risk level.}

---

## Planned Comments Document

**Filename:** `pr-{number}-planned-comments.md`

This is the lean deliverable — exactly what will be posted to GitHub. No analysis, just the comments.

### Template

```markdown
# PR #{number} — Planned Comments

---

## Overall PR Comment

> The review body text. This appears at the top of the GitHub review.
> Keep it concise and terse.

---

## Inline Comments

### Comment {short label} ({file_basename})

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

{Repeat for each inline comment, ordered by priority}

```

### Guidelines

- The overall PR comment sets the tone, be constructive and concise
- Each inline comment must be **self-contained**. A reader on GitHub should understand it without seeing the review analysis document
- Use the `>` blockquote for the comment body to visually separate it from the File/Line metadata
- The `{short label}` in the heading should indicate the category: "Bug:", "Nit:", "Question:", "Testing gap:", etc.
- Keep comment bodies focused, if a comment needs more than ~10 lines, consider splitting it or linking to further context

### Tone Guide

- **(Bugs):** Direct but not alarming. "Potiential Bug: this will fail when..." not "CRITICAL BUG!!!"
- **(Quality):** Suggestive. "Consider using..." or "This duplicates..."
- **(Testing/Architecture):** Questioning. "Is there a reason..." or "Testing gap:..." or "Did you consider..."
- **(Minor):** Explicitly low-priority. "Minor:" or "Nit:" prefix
- **General:** Use "we" or impersonal voice ("this could be..." not "you should..."). Assume positive intent. Acknowledge when something is pre-existing rather than introduced by the PR.
