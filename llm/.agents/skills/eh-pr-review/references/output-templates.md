# Output Templates

One document is produced per review. Written to `${PLANNING_MARKDOWN_DIR}/`.

## Overview

Concise summary of what the PR does, its scope, and risk level.}

---

## Planned Comments Document

**Filename:** `{platform}-{host}-{project_slug}-{number}-planned-comments.md` for hosted reviews; see `SKILL.md` for local reviews.

This is the lean deliverable — exactly the text to be staged on the hosting platform. No analysis, just the comments.

### Template

```markdown
# {PR #{number} | MR !{number}} — Planned Comments

---

## Overall PR Comment

The overall review text. On GitHub it is the review body; on GitLab it is a separate draft note. Keep it concise, terse, and neutral. When there are inline comments, prefer a simple opener like:

Couple of things to look at:

---

## Inline Comments

### Comment {short label} ({file_basename})

**File:** `{full_file_path}`
**Line:** {line_number} {optional: brief description of what's on that line}

{The exact comment body to be staged on GitHub or GitLab. Supports markdown.

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
- Each inline comment must be **self-contained**. A reader on either host should understand it without seeing the review analysis document
- Comment bodies should be plain markdown with no leading `>` prefixes
- The `{short label}` in the heading should indicate the category: "Bug:", "Nit:", "Question:", "Testing gap:", etc.
- Keep comment bodies focused; one short paragraph is usually enough

### Tone Guide

- **(Bugs):** Question-led and concrete. "Did you consider that when X happens, Y follows..." not "This must be fixed."
- **(Quality):** Suggestive and light. "What about renaming..." or "This duplicates..."
- **(Testing/Architecture):** Questioning. "Is there a reason..." or "Did you consider..."
- **(Minor):** Explicitly low-priority. "Minor:" or "Nit:" prefix
- **General:** Avoid "you should", "please fix", and hard requirements. Use "we" for shared ownership when natural. Use "I believe" for conclusions that are likely but depend on repo wiring or runtime behavior.
