---
name: eh-plan
description: "Break down features or stories into single-acceptance-test tickets and PRs. Conversational planning skill."
user-invocable: true
allowed-tools: "Bash(gh *), Read, Glob, Grep, Write, Edit, WebFetch, Agent(subagent_type=Explore *)"
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
${PLANNING_MARKDOWN_DIR}/{descriptive-name}.md
```

Name it based on the feature (e.g., `oauth-rate-limiting.md`, `bulk-user-invite.md`).

Start the doc with:

- **Goal**: one sentence
- **Context**: background, links, constraints, decisions, and exact repo paths
- **Open Questions**: every unresolved question, written before asking in chat

Recommend an answer instead of giving an unranked menu:

```markdown
## Open Questions

1. **{Decision}:** {Question}
   - Recommendation: {Answer and brief reason}
   - Answer: awaiting confirmation.
```

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

**Acceptance criteria:** Given X, when Y, then Z
**Expected edits:** exact files/modules likely to change
**Scope:** what's in, what's out
**Notes:** implementation hints, full self-contained context
```

Each story gets one PR. Every story must name the acceptance criteria, any notes that can help with the expected files/modules to edit should be added. Repo evidence should be cited in context/notes with exact paths. Make sure the acceptance criteria, e.g., what needs to be done, is clear and concise. Also ensure that the story has enough context for anyone to pick it up. The context that we have above will not appear in every story, so ensure the story is self contained.

### Step 5: Iterate

This is conversational.

- Grill the user with any questions you have. Ensure you reach a mutual understanding
- Challenge the decomposition
- Add/remove/merge stories
- Refine acceptance tests
- Ask about trade-offs or sequencing

Update the planning doc as you go. The doc is the artifact — keep it current.

After answers:

1. Resolve contradictions.
2. Move decisions into **Context** with rationale and evidence.
3. Delete answered questions; add recommended follow-ups if needed.
4. Update acceptance tests and stories. Remove the section when empty.

In chat, summarize changes and point to new questions.

## Principles

- Tracer bullets: prefer a thin end-to-end slice first, then layer on
- Trade-offs over best practices: name what you're trading and why
- Each story must be a demonstrable change in functionality, not a technical task
- If a story can't be done in a couple days, the acceptance test needs splitting
- Reversibility: sequence easy-to-undo changes before hard-to-undo ones
- Every story must name the expected files/modules to edit
- Cite repo evidence in context/notes with exact paths when using local code/config to justify a story

# Software Architecture: The Hard Parts

1. **There are no best practices** — only trade-offs. Every architectural decision involves choosing between competing compromises.
2. **Decompose monoliths carefully** — use component coupling analysis (afferent/efferent coupling, abstractness, instability) to find the right seams to split.
3. **Service granularity is hard** — services can be too coarse or too fine. Use granularity disintegrators (scalability, fault tolerance, security) and integrators (data transactions, workflow) to decide.
4. **Data ownership is the hardest part** — in distributed systems, deciding who owns what data, and how to share it, is often more difficult than the service boundaries themselves.
5. **Distributed transactions require careful choices** — sagas (choreography vs. orchestration) each have distinct trade-offs around coupling, error handling, and visibility.
6. **Communication style matters** — synchronous vs. asynchronous, choreography vs. orchestration each affect coupling, scalability, and fault tolerance differently.
7. **Contracts between services need managing** — strict vs. loose contracts, consumer-driven contracts, and versioning all require deliberate strategies.
8. **Architecture fitness functions** — use automated tests to guard architectural characteristics (e.g., coupling thresholds, response times) over time.
9. **Architecture quanta** — independently deployable components with high functional cohesion are the unit of analysis for distributed systems.

| Pattern Name         | Communication | Consistency | Coordination  |
|----------------------|---------------|-------------|---------------|
| Epic Saga            | Synchronous   | Atomic      | Orchestrated  |
| Phone Tag Saga       | Synchronous   | Atomic      | Choreographed |
| Fairy Tale Saga      | Synchronous   | Eventual    | Orchestrated  |
| Time Travel Saga     | Synchronous   | Eventual    | Choreographed |
| Fantasy Fiction Saga | Asynchronous  | Atomic      | Orchestrated  |
| Horror Story Saga    | Asynchronous  | Atomic      | Choreographed |
| Parallel Saga        | Asynchronous  | Eventual    | Orchestrated  |
| Anthology Saga       | Asynchronous  | Eventual    | Choreographed |

# Designing Data-Intensive Applications (DDIA)

**Part I: Foundations of Data Systems**

- Reliability, scalability, and maintainability are the three core goals
- Every tool choice involves trade-offs — no silver bullets
- Define nonfunctional requirements before selecting technologies

**Part II: Distributed Data**

- Replication: copies of data on multiple nodes enable fault tolerance but create consistency challenges
- Partitioning/sharding: splitting data across nodes for scalability requires careful key design
- Replication lag is unavoidable; know your consistency model (eventual, read-your-writes, linearizable)
- Distributed transactions and consensus are hard — Paxos/Raft exist because agreement is non-trivial
- Networks, clocks, and nodes fail unpredictably — design assuming partial failure

**Part III: Derived Data**

- Batch processing (MapReduce-style) optimizes for throughput over latency
- Stream processing optimizes for low latency over large-scale batch efficiency
- Event logs (Kafka style) are a powerful unifying abstraction between batch and streaming
- Vector indexes enable semantic/AI search workloads
- DataFrames and batch pipelines are central to ML data preparation
- Cloud-native architectures favor object stores (S3-style) over local disk

**Throughline**

- The goal is always to reason clearly about what guarantees your system provides — and to be honest about where it doesn't

# Ron Jeffries how to break down tasks

Here's the core process, drawn directly from Jeffries and Killick:

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

The goal: breaking each story down to bits that require only a single acceptance test will almost invariably give you something that can be done in a couple of days. If it doesn't, your acceptance test needs splitting.

# Milestones

[Choosing Project Milestones That Actually Matter](https://www.youtube.com/watch?v=3OO9ZJEudPY)

* Name milestones as outcomes, not activities.
* Tie each milestone to meaningful progress or a turning point.
* Make names specific and measurable with a clear yes/no completion test.
* Prefer completed-state wording like “approved,” “launched,” “finalized,” or “deployed.”
* Align milestone names to decision points, approvals, or major deliverables.
* Keep them few and high-signal so they mark real progress, not clutter.
* Write them so stakeholders can understand them instantly.
