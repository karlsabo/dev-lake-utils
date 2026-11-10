---
name: eh-implement
description: Implement a planned story, feature slice, or description. Follow the guidance embedded in this skill while making small, testable, low-coupling code changes with disciplined comments.
user-invocable: true
allowed-tools: Bash, Read, Glob, Grep, Write, Edit, Task(subagent_type=Explore *)
---

# Implementation Skill

## Required context

Expect input to be one of:

- A story or planning doc
- A ticket or freeform request that clearly names one story / acceptance test
- Review comments

## Workflow

### Step 1: Lock the slice

- Identify the acceptance tests being implemented.
- For non-trivial or ambiguous work, establish the observable behavior and scope before editing; ask when material ambiguity remains.
- Prefer the smallest tracer-bullet path that proves the behavior end to end.

### Step 2: Find the seam

- Read the surrounding code; trace callers, tests, fixtures, config, and exports the change affects.
- Check for data loss, exposure, or broken callers before choosing scope.
- For bug fixes, search all callers and fix the root cause once.
- Reuse existing abstractions and conventions when they fit.
- If you need a new abstraction, introduce the smallest one that makes the next change easier.
- Optimize for replaceability, low coupling, and clear contracts rather than cleverness.
- If the change crosses service or data boundaries, make ownership, guarantees, and failure behavior explicit.
- If you make a material architectural trade-off, capture the `why` in the most appropriate artifact for the repo: ADR, ticket, planning doc, or PR notes.

### Step 3: Implement

- Keep functions cohesive; extract when it improves clarity, testability, or reuse—not merely to reduce line count.
- Make illegal states hard to represent. Validate assumptions and fail early when a contract is violated.
- Deduplicate shared behavior or knowledge, but do not merge merely similar-looking code with independent reasons to change.
- Fix nearby broken windows that directly obscure the change but do not sprawl into unrelated cleanup.
- Prefer deletion, existing code, or the standard library over new code; avoid dependencies for a few lines.
- Add no speculative options, wrappers, conversions, or configuration.
- Preserve validation and error handling when moving code; never cut trust-boundary checks, security, or accessibility.
- Prefer plain, obvious code over indirection.
- If you are prototyping to learn, keep it disposable. Do not smuggle prototype shortcuts into the final code.
- For distributed or data-heavy changes, be explicit about consistency, ownership, retries, idempotency, and failure modes.

### Step 4: Comments and docs

- Default to no new comments. Prefer clear code and names.
- Add a comment only to explain a non-obvious invariant, external constraint, or failure mode that cannot be made clear in code. Having a reason for the change is not enough to justify a code comment.
- For configuration/value-only changes, change the value only. Put before/after sizing, incident history, ticket references, change rationale, rollout steps, and validation notes in the PR or ticket—not inline comments.
- Document public APIs and contracts where useful or required by repository conventions; skip obvious boilerplate.
- Include inputs, returns, failures, side effects, units, or concurrency requirements only when they are non-obvious and relevant.
- Remove stale or noisy comments in code you touch.
- Follow existing repository and language conventions.

### Step 5: Validate

- Run the narrowest useful tests first, then broader tests as needed.
- Do not assume behavior. Prove it with tests, logs, types, contracts, or direct inspection.
- Verify the acceptance test, the main failure path, and any contract you changed.
- Add or update focused automated tests for changed behavior and regression fixes; when that is impractical, perform and report an appropriate alternative verification.
- If architecture-level guarantees matter, add or update automated checks where practical.

### Step 6: Self-review

- Check for accidental coupling, hidden duplication, oversized functions, leaky names, and comment noise.
- Inspect every added comment in the diff. Delete comments that restate values, narrate the edit, or belong in the PR/ticket; keep only the non-obvious code constraints allowed in Step 4.
- Check whether the change still maps cleanly to one acceptance test.
- If the work expands into independent acceptance criteria or separately deployable changes, stop and replan or split it.
- End with one or two lines on what you skipped or did not check and any remaining risk.
