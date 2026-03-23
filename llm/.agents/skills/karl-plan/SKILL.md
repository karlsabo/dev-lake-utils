---
name: karl-plan
description: Break down features or stories into single-acceptance-test tickets and PRs. Conversational planning skill.
user-invocable: true
allowed-tools: Bash(gh *), Read, Glob, Grep, Write, Edit, WebFetch, Agent(subagent_type=Explore *)
---

# Planning Skill

Break features into stories where each story = 1 acceptance test = 1 ticket = 1 PR.

## Input

Argument is either:

- Freeform text describing a feature or story
- A Linear ticket ID (e.g., `ABC-123`) or URL

If a Linear ticket ID/URL is given, fetch the ticket details for context.

## Workflow

### Step 1: Understand the work

If freeform text: ask clarifying questions until you understand the feature well enough to enumerate acceptance tests.

If Linear ticket: read the ticket, summarize what you understand, and ask what's unclear or missing.

Don't rush to decompose. Understand first.

### Step 2: Create the planning doc

Create a markdown file at:

```
$HOME/karl-backup/notebook/llm-planning/{descriptive-name}.md
```

Name it based on the feature (e.g., `oauth-rate-limiting.md`, `bulk-user-invite.md`).

Start the doc with:

- **Goal**: one sentence describing the feature
- **Context**: relevant background, links, constraints

### Step 3: Enumerate acceptance tests

Apply the Jeffries method:

**How to break work down to a single acceptance test**

1. **Start with a feature.** Name it on a card. If it doesn't fit on a small card, use a smaller card.
2. **Enumerate the acceptance tests** for that feature — concrete, specific examples with real names and values (Given/When/Then style). Each test describes one observable behavior from the user's perspective.
3. **If there's more than one acceptance test, you have more than one story.** Split into separate stories, one per test.
4. **Make each acceptance test as simple as possible** while still showing something real about the feature. Strip edge cases, performance concerns, UI polish, and "and/or" conjunctions — each is a separate story.
5. **If a single-test story still takes more than a few days, the acceptance test itself is too big.** Split the test.
6. **Each resulting story must be a demonstrable change in functionality** — something a user can actually see or do — not a technical task.

**Slicing triggers to look for:**

- The word "and" or "or" in a scenario → split it
- Multiple user types or roles → split by role
- Edge cases and error paths → defer to separate stories
- Performance, UI polish, browser compatibility → defer
- "Implement the first X, then the rest" → do just the first X

Present the list to the user. Discuss. Iterate.

### Step 4: Map to stories and PRs

Once acceptance tests are agreed on, map each to a story:

```markdown
## Stories

### 1. {Story title}

**Acceptance test:** Given X, when Y, then Z
**Scope:** what's in, what's out
**Notes:** implementation hints, dependencies, ordering
```

Each story gets one PR. Call out ordering dependencies between stories.

### Step 5: Iterate

This is conversational. The user may:

- Challenge the decomposition
- Add/remove/merge stories
- Refine acceptance tests
- Ask about trade-offs or sequencing

Update the planning doc as you go. The doc is the artifact — keep it current.

## Principles

- Tracer bullets: prefer a thin end-to-end slice first, then layer on
- Trade-offs over best practices: name what you're trading and why
- Each story must be a demonstrable change in functionality, not a technical task
- If a story can't be done in a couple days, the acceptance test needs splitting
- Reversibility: sequence easy-to-undo changes before hard-to-undo ones
