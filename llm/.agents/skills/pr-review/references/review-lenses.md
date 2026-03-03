# Review Lenses

Each lens represents a dimension of analysis. Apply them in priority order. Not every lens will produce findings for
every PR — that's expected and correct.

---

## P0 — Bugs & Correctness

The highest priority. A bug in production outweighs any number of style nits.

**What to look for:**

- Logic errors — wrong operator, off-by-one, inverted condition, short-circuit evaluation bugs
- Null/None handling — unguarded `.get()`, missing `Optional` checks, falsy-value confusion (`0`, `""`, `False` vs
  `None`)
- Edge cases — empty collections, single-element collections, boundary values, unicode, timezone-naive datetimes
- Race conditions — TOCTOU bugs, unprotected shared state, non-atomic read-modify-write
- Security — OWASP Top 10 (injection, XSS, SSRF, broken auth, mass assignment), secret leakage, unvalidated redirects
- Data integrity — missing DB constraints, orphaned foreign keys, migration ordering, backwards-incompatible schema
  changes
- Error handling — swallowed exceptions, bare `except:`, error paths that silently continue

**Pragmatic Programmer:** "Dead Programs Tell No Lies" — fail loudly and early rather than propagating corrupt state.

**Signal phrases for comments:** "Bug:", "This will fail when...", "This silently..."

---

## P1 — Code Quality

Code that works but is hard to read, maintain, or extend.

**What to look for:**

- Readability — unclear variable names, deeply nested logic, functions doing too many things, missing context for
  non-obvious decisions
- DRY violations — identical or near-identical code in multiple places. But: don't flag "similar" code that handles
  genuinely different cases
- Naming — misleading names, inconsistent conventions within the file/module, abbreviations that aren't project-standard
- Complexity — cyclomatic complexity, long functions, deep nesting. Suggest extraction only when it genuinely improves
  clarity
- Pattern consistency — does the new code follow the patterns established in the file/module? If it deviates, is there a
  good reason?
- Magic values — unexplained numbers, strings, or timeouts. Suggest named constants only when the meaning isn't obvious
  from context
- Idioms — is the code idiomatic for the language/framework? (e.g., `models.TextChoices` vs `str, Enum` in Django)

**Pragmatic Programmer:**

- **DRY** — "Every piece of knowledge must have a single, unambiguous, authoritative representation within a system"
- **Orthogonality** — changes in one area shouldn't ripple to unrelated areas
- **Tracer Bullets** — prefer working end-to-end slices over speculative abstractions

**Signal phrases for comments:** "Nit:", "Consider:", "This duplicates..."

---

## P2 — Testing Gaps

Missing or weak test coverage that leaves the change inadequately verified.

**What to look for:**

- Missing coverage — new code paths with no tests, especially error paths and edge cases
- Untested edge cases — boundary values, empty inputs, None values, concurrent access
- Test quality — tests that pass for the wrong reason, tests that don't assert meaningful behavior, tests that are too
  tightly coupled to implementation
- Redundant tests — duplicate tests across files, tests that exercise the same path with trivially different inputs
- Wrong test layer — unit tests doing integration work, integration tests testing unit-level logic
- Assertion anti-patterns — `assert mock.called_with()` (no-op), overly broad assertions, missing assertions entirely
- Test isolation — tests that depend on execution order, shared mutable state, or external services without mocking

**SE Hard Parts:** Tests should be owned by the component that owns the behavior. A model test should test model logic.
A view test should test view concerns (routing, permissions, response shape). Don't duplicate model-level assertions in
view-level tests.

**Signal phrases for comments:** "Testing gap:", "This path isn't covered:", "This test duplicates..."

---

## P2 — Architecture & Design

Structural issues that affect long-term maintainability.

**What to look for:**

- Coupling — does the change create tight coupling between components that should be independent? Are internal details
  leaking across boundaries?
- Cohesion — does the new code belong in the file/module where it was placed? Would it be more natural elsewhere?
- Abstraction level — is the abstraction at the right level? Too abstract (YAGNI) or too concrete (hard to extend)?
- API design — are function signatures clear? Do parameter names communicate intent? Are return types consistent?
- Dependency direction — do dependencies flow in the right direction? (e.g., domain shouldn't depend on infrastructure)

**SE Hard Parts:**

- **Afferent coupling** (Ca) — how many modules depend on this one? High Ca = careful with changes
- **Efferent coupling** (Ce) — how many modules does this one depend on? High Ce = fragile to upstream changes
- **Data ownership** — who owns this data? Is ownership clear or shared ambiguously?

**Signal phrases for comments:** "This couples...", "Consider moving this to...", "This creates a dependency on..."

---

## P3 — Redundancy

Low-priority cleanup that can be addressed in follow-up work.

**What to look for:**

- Dead code — unused imports, unreachable branches, commented-out code, unused parameters
- Duplicate code — copy-pasted logic that could be extracted (but only if it's clearly the same concern)
- Duplicate tests — tests in different files that exercise the exact same behavior
- Stale comments — comments that no longer match the code, TODO comments for work already done
- Unnecessary complexity — abstractions for one-time operations, feature flags for features that shipped

**Signal phrases for comments:** "Minor:", "Cleanup:", "This is now unused"

---

## Calibration Guide

**Before writing any comments, ask yourself:**

1. **Is this PR high-risk or low-risk?** A migration changing production data schemas deserves more scrutiny than a docs
   update.
2. **How many comments do I have?** More than 5-6 inline comments on a small PR feels heavy. Consolidate related points.
3. **Am I manufacturing findings?** If the code is clean, say so. An approval with zero inline comments is valid.
4. **Is each comment actionable?** Every comment should either (a) identify something to fix, (b) ask a question, or (c)
   suggest an improvement. Pure observations without a call to action are noise.
5. **Am I being constructive?** Frame comments as suggestions, not commands. Use "Consider..." and "Suggest..." not "You
   should..." or "This is wrong."
6. **Would I appreciate this comment on my PR?** If not, rephrase or drop it.

**Volume calibration by PR type:**

- **Bug fix (small):** 0-2 comments. Verify the fix is correct, check for regression risk.
- **New feature (medium):** 2-5 comments. Focus on bugs, test coverage, and whether it fits the codebase.
- **Refactor (large):** 1-3 comments. Verify no behavior changes, check boundary conditions at new module seams.
- **Infrastructure/config:** 0-1 comments. Verify correctness, check for secret leakage.
