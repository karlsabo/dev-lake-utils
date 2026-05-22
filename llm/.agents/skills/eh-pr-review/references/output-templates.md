# Output Templates

One document is produced per review. Written to `${PLANNING_MARKDOWN_DIR}/`.

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

The review body text. This appears at the top of the GitHub review. Keep it concise, terse, and neutral. When there are inline comments, prefer a simple opener like:

Couple of things to look at:

---

## Inline Comments

### Comment {short label} ({file_basename})

**File:** `{full_file_path}`
**Line:** {line_number} {optional: brief description of what's on that line}

{The exact comment body that will be posted to GitHub. Supports GitHub-flavored markdown.

The body should be copy/paste-ready for GitHub. Do not prefix lines with `>`.

Include code suggestions only when the exact fix is obvious:

```python
suggested_code()
```

}

---

{Repeat for each inline comment, ordered by priority}

```

### Guidelines

- The overall PR comment should be neutral and short; avoid summarizing or judging the PR
- Each inline comment must be **self-contained**. A reader on GitHub should understand it without seeing the review analysis document
- Comment bodies should be plain GitHub-flavored markdown with no leading `>` prefixes
- The `{short label}` in the heading should indicate the category: "Bug:", "Nit:", "Question:", "Testing gap:", etc.
- Keep comment bodies focused; one short paragraph is usually enough

### Tone Guide

- **(Bugs):** Question-led and concrete. "Did you consider that when X happens, Y follows..." not "This must be fixed."
- **(Quality):** Suggestive and light. "What about renaming..." or "This duplicates..."
- **(Testing/Architecture):** Questioning. "Is there a reason..." or "Did you consider..."
- **(Minor):** Explicitly low-priority. "Minor:" or "Nit:" prefix
- **General:** Avoid "you should", "please fix", and hard requirements. Use "we" for shared ownership when natural. Use "I believe" for conclusions that are likely but depend on repo wiring or runtime behavior.
