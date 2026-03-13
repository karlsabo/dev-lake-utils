# The Pragmatic Programmer

* You own your career — Take responsibility; don't blame tools, teams, or circumstances.
* DRY (Don't Repeat Yourself) — Every piece of knowledge should have a single, authoritative representation.
* Don't live with broken windows — Fix bad code, poor design, and wrong decisions as soon as you see them.
* Be a generalist — Know enough across many areas to adapt quickly.
* Make it easy to change — Good design is code that's easy to modify. If in doubt, make it replaceable.
* Use tracer bullets — Build thin, end-to-end slices of functionality early to validate your approach.
* Prototype to learn — Prototypes are disposable experiments, not foundations.
* Estimate everything — Develop intuition for the size and complexity of tasks.
* Write code that writes code — Use code generation to avoid repetition at scale.
* Design with contracts — Use preconditions, postconditions, and invariants to make expectations explicit.
* Crash early — A dead program causes less damage than a broken, limping one.
* Decouple ruthlessly — Avoid coupling between components so changes don't ripple everywhere.
* Use the power of plain text — Store knowledge in plain text; it outlives all other formats.
* Know your tools deeply — Master your editor, shell, and debugger.
* Iterate with users — Requirements are found, not given. Work closely and continuously with users.
* Test relentlessly — Test your software, or your users will.
* Don't assume — prove it — Assumptions in code are the root of most bugs.
* Delight your users — The goal isn't to deliver what they asked for, but what they actually need.

# Software Architecture: The Hard Parts

1. **There are no best practices** — only trade-offs. Every architectural decision involves choosing between competing compromises.
2. **Decompose monoliths carefully** — use component coupling analysis (afferent/efferent coupling, abstractness, instability) to find the right seams to split.
3. **Service granularity is hard** — services can be too coarse or too fine. Use granularity disintegrators (scalability, fault tolerance, security) and integrators (data transactions, workflow) to decide.
4. **Data ownership is the hardest part** — in distributed systems, deciding who owns what data, and how to share it, is often more difficult than the service boundaries themselves.
5. **Distributed transactions require careful choices** — sagas (choreography vs. orchestration) each have distinct trade-offs around coupling, error handling, and visibility.
6. **Communication style matters** — synchronous vs. asynchronous, choreography vs. orchestration each affect coupling, scalability, and fault tolerance differently.
7. **Contracts between services need managing** — strict vs. loose contracts, consumer-driven contracts, and versioning all require deliberate strategies.
8. **Architecture fitness functions** — use automated tests to guard architectural characteristics (e.g., coupling thresholds, response times) over time.
9. **Document decisions with ADRs** — Architectural Decision Records capture the *why* behind choices, not just the what.
10. **Architecture quanta** — independently deployable components with high functional cohesion are the unit of analysis for distributed systems.

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

- Replication: copies of data on multiple nodes enable fault tolerance, but create consistency challenges
- Partitioning/sharding: splitting data across nodes for scalability requires careful key design
- Replication lag is unavoidable; know your consistency model (eventual, read-your-writes, linearizable)
- Distributed transactions and consensus are hard — Paxos/Raft exist because agreement is non-trivial
- Networks, clocks, and nodes fail unpredictably — design assuming partial failure

**Part III: Derived Data**

- Batch processing (MapReduce-style) optimizes for throughput over latency
- Stream processing optimizes for low latency over large-scale batch efficiency
- Event logs (Kafka-style) are a powerful unifying abstraction between batch and streaming
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
