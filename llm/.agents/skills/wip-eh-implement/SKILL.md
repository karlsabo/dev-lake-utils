---
name: wip-eh-implement
description: Implement a planned story or feature slice. Follow llm/notes.md while making small, testable, low-coupling code changes with disciplined comments.
user-invocable: true
allowed-tools: Bash, Read, Glob, Grep, Write, Edit, Task(subagent_type=Explore *)
---

# Implementation Skill

Implement one story at a time: one acceptance test, one PR, one thin end-to-end slice.

## Required context

Before changing code, read:

- `../../notes.md`

Expect input to be one of:

- A story or planning doc
- A ticket or freeform request that clearly names one story / acceptance test

If the input still contains multiple acceptance tests or unrelated behavior, stop.

## Workflow

You are the orchestrator of several LLM calls. When you create subagents, use the same model you are; do not use a mini model.

### Step 1: Create a subagent to flesh out the new contract

Prompt the subagent with all the context it needs to create classes/interfaces/functions with contracts.

### Step 2: Create a subagent to write unit tests against the new contracts

Prompt the subagent with all the context it needs to create black box unit tests for the new contracts.

Find the seam

- Read the surrounding code before editing.
- Reuse existing abstractions when they fit.
- If you need a new abstraction, introduce the smallest one that makes the next change easier.
- Optimize for replaceability, low coupling, and clear contracts rather than cleverness.
- If the change crosses service or data boundaries, make ownership, guarantees, and failure behavior explicit.
- If you make a material architectural trade-off, capture the `why` in the most appropriate artifact for the repo: ADR, ticket, planning doc, or PR notes.

### Step 3: Create a subagent to review the unit tests

Prompt the subagent with all the context it needs to review the black box unit tests for the new contracts.

### Step 4: Create a subagent to fix the unit test review findings

Prompt the subagent with all the context it needs to review the black box unit test reviews and apply fixes.

### Step 5: Create a subagent to write code to satisfy unit tests

Prompt the subagent with all the context it needs to write code to satisfy the black box unit tests
- Keep functions small and single-purpose.
- Make illegal states hard to represent. Validate assumptions and fail early when a contract is violated.
- Remove duplication instead of copying logic into another place.
- Fix nearby broken windows that directly obscure the change but do not sprawl into unrelated cleanup.
- Prefer plain, obvious code over indirection.
- If you are prototyping to learn, keep it disposable. Do not smuggle prototype shortcuts into the final code.
- For distributed or data-heavy changes, be explicit about consistency, ownership, retries, idempotency, and failure modes.
- Do not add comments that narrate what the next line or block does.
- Inline comments are for `why`: constraints, invariants, trade-offs, or non-obvious behavior.
- `What` comments belong only in doc comments on functions, classes, modules, or similar units, and those doc comments should be terse.
- If the code is self-explanatory, prefer no comment.
- Delete stale or noisy comments in code you touch.

### Step 6: Create a subagent to verify the unit tests pass

Prompt the subagent with all the context it needs to verify the unit tests are passing.

### Step 7: Create a subagent to fix any failing unit tests

Prompt the subagent with all the context it needs to fix the unit tests that are failing.

### Step 8: Create a subagent to write white box unit tests against the new contracts

Prompt the subagent with all the context it needs to create white box unit tests for the new code.

### Step 9: Create a subagent to review the white box unit tests

Prompt the subagent with all the context it needs to review the white box unit tests for the new contracts.

### Step 10: Create a subagent to fix the white box unit test review findings

Prompt the subagent with all the context it needs to review the white box unit test review findings and apply fixes.

### Step 11: Spawn a subagent to review all changes

Prompt the subagent to review uncommitted changes with the skill eh-pr-review

### Step 12: If there are review comments spawn a sub agent to fix the comments

Prompt the subagent with all the context needed to fix the comments

### Step 13: Go back to step 11 if there we fixes for comments

This is where you loop, while there are comments, spawn an agent to fix them.
