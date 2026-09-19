import assert from "node:assert/strict";
import test from "node:test";
import type {HandoffLedger, WorkflowHandoff} from "./handoff.ts";
import type {ReviewAttempt, ReviewClaim, ReviewEvidence} from "./review-evidence.ts";
import {runImplementationWorkflow, type StateAgent, WorkflowFailure, type WorkflowState} from "./workflow.ts";

const COMPLETED = '{"outcome":"completed","workPerformed":true,"summary":"done"}';
const NO_WORK = '{"outcome":"completed","workPerformed":false,"summary":"no work needed"}';
const PASSING = '{"passed":true,"summary":"tests passed"}';
const CLEAN = '{"findings":[],"summary":"clean"}';
const GUIDANCE_PATH = "/repo/llm/notes.md";

class FakeEvidence implements ReviewEvidence {
	attempts = 0;
	fingerprintValue = "same";
	findingsByAttempt: string[][] = [];
	validated: ReviewClaim[] = [];
	cleanupCalls = 0;
	cleanupError: Error | undefined;
	scope = ["src/changed.ts", "docs/command.md"];

	async begin(attemptNumber: number): Promise<ReviewAttempt> {
		this.attempts += 1;
		return {attemptId: `run-${attemptNumber}`, artifactPath: `/tmp/review-${attemptNumber}.md`, fingerprint: "same", scope: this.scope};
	}
	async validate(attempt: ReviewAttempt, claim: ReviewClaim): Promise<string> {
		if (claim.attemptId !== attempt.attemptId || claim.artifactPath !== attempt.artifactPath) {
			throw new Error(`Review evidence does not belong to attempt ${attempt.attemptId}`);
		}
		this.validated.push(claim);
		return `artifact for ${attempt.attemptId}`;
	}
	findings(document: string): string[] {
		const attemptNumber = Number(document.match(/run-(\d+)/)?.[1]);
		return this.findingsByAttempt[attemptNumber - 1] ?? [];
	}
	async fingerprint(): Promise<string> {
		return this.fingerprintValue;
	}
	async cleanup(): Promise<void> {
		this.cleanupCalls += 1;
		if (this.cleanupError) throw this.cleanupError;
	}
}

function exhaustiveStandardResponse(state: WorkflowState): string {
	switch (state) {
		case "review-black-box-tests":
		case "review-white-box-tests":
			return CLEAN;
		case "verify-tests":
			return PASSING;
		case "review-changes":
		case "skeptic-review-changes":
			throw new Error(`Review claim for ${state} needs its attempt-specific response`);
		case "create-contract":
		case "write-black-box-tests":
		case "fix-black-box-test-findings":
		case "implement":
		case "fix-failing-tests":
		case "write-white-box-tests":
		case "fix-white-box-test-findings":
		case "fix-review-findings":
			return COMPLETED;
		default: {
			const unreachable: never = state;
			throw new Error(`Unhandled state ${unreachable}`);
		}
	}
}

function reviewClaim(prompt: string): string {
	const attemptId = prompt.match(/"attemptId":"([^"]+)"/)?.[1];
	const artifactPath = prompt.match(/"artifactPath":"([^"]+)"/)?.[1];
	assert.ok(attemptId && artifactPath);
	return JSON.stringify({attemptId, artifactPath, summary: "reviewed"});
}

function agent(overrides?: (state: WorkflowState, prompt: string) => string | undefined): StateAgent {
	return async (state, prompt) => {
		const overridden = overrides?.(state, prompt);
		if (overridden !== undefined) return overridden;
		if (state === "review-changes" || state === "skeptic-review-changes") return reviewClaim(prompt);
		return exhaustiveStandardResponse(state);
	};
}

function options(evidence: ReviewEvidence, overrides: Record<string, unknown> = {}) {
	return {guidancePath: GUIDANCE_PATH, initialChanges: [], reviewEvidence: evidence, ...overrides};
}

class FakeHandoffLedger implements HandoffLedger {
	recorded: Array<{state: WorkflowState; handoff: WorkflowHandoff}> = [];

	async record(state: WorkflowState, handoff: WorkflowHandoff): Promise<void> {
		this.recorded.push({state, handoff});
	}

	async contextFor(state: WorkflowState): Promise<string | undefined> {
		return this.recorded.length === 0 ? undefined : `cached evidence for ${state}`;
	}
}

test("records structured handoffs and supplies retained evidence to later states", async () => {
	const evidence = new FakeEvidence();
	const ledger = new FakeHandoffLedger();
	let blackBoxPrompt = "";
	const handoff: WorkflowHandoff = {
		facts: [{claim: "contract is declared by Foo", evidence: [{path: "src/foo.ts", line: 3}]}],
		relevantPaths: ["src/foo.ts"],
		commandsRun: [],
	};
	const response = JSON.stringify({outcome: "completed", workPerformed: true, summary: "contract added", handoff});
	const result = await runImplementationWorkflow("implement behavior", agent((state, prompt) => {
		if (state === "create-contract") return response;
		if (state === "write-black-box-tests") blackBoxPrompt = prompt;
		return undefined;
	}), options(evidence, {handoffLedger: ledger}));

	assert.deepEqual(ledger.recorded[0], {state: "create-contract", handoff});
	assert.match(blackBoxPrompt, /cached evidence for write-black-box-tests/);
	assert.deepEqual(result.states[0]?.handoff, handoff);
});

test("runs each required clean implementation state through a separate agent invocation", async () => {
	const evidence = new FakeEvidence();
	const calls: Array<{state: WorkflowState; prompt: string}> = [];
	const result = await runImplementationWorkflow("implement one observable behavior", agent((state, prompt) => {
		calls.push({state, prompt});
		return undefined;
	}), options(evidence));

	assert.deepEqual(calls.map(({state}) => state), [
		"create-contract",
		"write-black-box-tests",
		"review-black-box-tests",
		"implement",
		"verify-tests",
		"write-white-box-tests",
		"review-white-box-tests",
		"review-changes",
		"skeptic-review-changes",
		"verify-tests",
	]);
	assert.equal(result.states.length, calls.length);
	assert.equal(result.initialTestFixes, 0);
	assert.equal(result.finalTestFixes, 0);
	assert.equal(result.reviewFixes, 0);
	assert.ok(calls.every(({prompt}) => prompt.includes(GUIDANCE_PATH)));
	assert.ok(calls.every(({prompt}) => prompt.includes("implement one observable behavior")));
});

test("skips black-box test review when the test-writing state reports no work", async () => {
	const evidence = new FakeEvidence();
	const states: WorkflowState[] = [];
	await runImplementationWorkflow("implement behavior", agent((state) => {
		states.push(state);
		return state === "write-black-box-tests" ? NO_WORK : undefined;
	}), options(evidence));

	assert.equal(states.includes("review-black-box-tests"), false);
	assert.equal(states.includes("fix-black-box-test-findings"), false);
	assert.ok(states.indexOf("implement") > states.indexOf("write-black-box-tests"));
});

test("skips white-box test review when the test-writing state reports no work", async () => {
	const evidence = new FakeEvidence();
	const states: WorkflowState[] = [];
	await runImplementationWorkflow("implement behavior", agent((state) => {
		states.push(state);
		return state === "write-white-box-tests" ? NO_WORK : undefined;
	}), options(evidence));

	assert.equal(states.includes("review-white-box-tests"), false);
	assert.equal(states.includes("fix-white-box-test-findings"), false);
	assert.ok(states.indexOf("review-changes") > states.indexOf("write-white-box-tests"));
});

test("repairs findings from black-box and white-box test reviews", async () => {
	const evidence = new FakeEvidence();
	const states: WorkflowState[] = [];
	await runImplementationWorkflow("implement behavior", agent((state) => {
		states.push(state);
		if (state === "review-black-box-tests") return '{"findings":["strengthen assertion"],"summary":"finding"}';
		if (state === "review-white-box-tests") return '{"findings":["cover branch"],"summary":"finding"}';
		return undefined;
	}), options(evidence));

	assert.equal(states.filter((state) => state === "fix-black-box-test-findings").length, 1);
	assert.equal(states.filter((state) => state === "fix-white-box-test-findings").length, 1);
});

test("treats planned comments as a review-remediation batch", async () => {
	const evidence = new FakeEvidence();
	const prompts = new Map<WorkflowState, string>();
	await runImplementationWorkflow("fix /tmp/planned-comments.md", agent((state, prompt) => {
		prompts.set(state, prompt);
		return undefined;
	}), options(evidence));

	assert.match(prompts.get("create-contract") ?? "", /review-remediation batch/);
	assert.match(prompts.get("create-contract") ?? "", /Do not block it merely because it has multiple comments/);
	assert.match(prompts.get("write-black-box-tests") ?? "", /cover each supported comment independently/);
});

test("repairs an initial validation failure and verifies again", async () => {
	const evidence = new FakeEvidence();
	let verification = 0;
	const states: WorkflowState[] = [];
	const result = await runImplementationWorkflow("implement behavior", agent((state) => {
		states.push(state);
		if (state === "verify-tests" && ++verification === 1) {
			return '{"passed":false,"summary":"focused test failed"}';
		}
		return undefined;
	}), options(evidence));

	assert.equal(result.initialTestFixes, 1);
	assert.deepEqual(states.slice(4, 7), ["verify-tests", "fix-failing-tests", "verify-tests"]);
});

test("directly invokes draft and skeptic review before final validation", async () => {
	const evidence = new FakeEvidence();
	const calls: Array<{state: WorkflowState; prompt: string}> = [];
	const result = await runImplementationWorkflow("implement behavior", agent((state, prompt) => {
		calls.push({state, prompt});
		return undefined;
	}), options(evidence));

	const finalStates = calls.map(({state}) => state).slice(-3);
	assert.deepEqual(finalStates, ["review-changes", "skeptic-review-changes", "verify-tests"]);
	assert.equal(evidence.validated.length, 2);
	assert.equal(evidence.cleanupCalls, 1);
	assert.equal(result.outcome, "completed");
	assert.deepEqual(
		result.states.filter(({artifactContent}) => artifactContent).map(({artifactContent}) => artifactContent),
		["artifact for run-1", "artifact for run-1"],
	);
});

test("reviews initially dirty paths and relevant unchanged callers, docs, and config", async () => {
	const evidence = new FakeEvidence();
	let prompt = "";
	await runImplementationWorkflow("implement behavior", agent((state, currentPrompt) => {
		if (state === "review-changes") prompt = currentPrompt;
		return undefined;
	}), options(evidence, {initialChanges: [{path: "src/changed.ts", snapshotPath: "/tmp/original"}]}));

	assert.match(prompt, /Initial dirtiness is provenance only: do not exclude/);
	assert.match(prompt, /src\/changed\.ts/);
	assert.match(prompt, /unchanged callers, consumers, tests, configuration, and documentation/);
	assert.match(prompt, /documented commands that may have become stale/);
});

test("requires draft and skeptic reviews to structure every finding and mark clean reviews canonically", async () => {
	const evidence = new FakeEvidence();
	const prompts = new Map<WorkflowState, string>();
	await runImplementationWorkflow("implement behavior", agent((state, prompt) => {
		prompts.set(state, prompt);
		return undefined;
	}), options(evidence));

	for (const state of ["review-changes", "skeptic-review-changes"] as const) {
		const prompt = prompts.get(state) ?? "";
		assert.match(prompt, /every (?:surviving )?actionable finding in a complete structured planned-comment entry/);
		assert.match(prompt, /never leave a finding only in the overall comment or in prose outside those entries/);
		assert.match(prompt, /set Overall PR Comment to exactly "No actionable findings\."/);
	}
});

test("a final validation repair returns through both review passes", async () => {
	const evidence = new FakeEvidence();
	let verifications = 0;
	const states: WorkflowState[] = [];
	const result = await runImplementationWorkflow("implement behavior", agent((state) => {
		states.push(state);
		if (state === "verify-tests") {
			verifications += 1;
			return verifications === 2 ? '{"passed":false,"summary":"final check failed"}' : PASSING;
		}
		return undefined;
	}), options(evidence));

	assert.equal(result.finalTestFixes, 1);
	assert.deepEqual(states.slice(-5), ["verify-tests", "fix-failing-tests", "review-changes", "skeptic-review-changes", "verify-tests"]);
	assert.equal(evidence.attempts, 2);
});

test("surviving artifact findings are repaired and re-reviewed", async () => {
	const evidence = new FakeEvidence();
	evidence.findingsByAttempt = [["Documented command still uses the removed syntax"], []];
	const states: WorkflowState[] = [];
	const result = await runImplementationWorkflow("implement behavior", agent((state) => {
		states.push(state);
		return undefined;
	}), options(evidence));

	assert.equal(result.reviewFixes, 1);
	assert.equal(evidence.attempts, 2);
	assert.ok(states.indexOf("fix-review-findings") < states.lastIndexOf("review-changes"));
});

test("cannot accept clean when review evidence belongs to another attempt", async () => {
	const evidence = new FakeEvidence();
	await assert.rejects(
		runImplementationWorkflow("implement behavior", agent((state) => {
			if (state === "review-changes") {
				return JSON.stringify({attemptId: "stale-attempt", artifactPath: "/tmp/stale.md", summary: "clean"});
			}
			return undefined;
		}), options(evidence)),
		(error: unknown) => error instanceof WorkflowFailure && /does not belong to attempt run-1/.test(error.message),
	);
});

test("preserves the workflow audit and reports review-evidence cleanup failure", async () => {
	const evidence = new FakeEvidence();
	evidence.cleanupError = new Error("review artifact cleanup failed");
	await assert.rejects(
		runImplementationWorkflow("implement behavior", agent((state) => {
			if (state === "create-contract") throw new Error("primary workflow failure");
			return undefined;
		}), options(evidence)),
		(error: unknown) => {
			assert.ok(error instanceof WorkflowFailure);
			assert.match(error.message, /primary workflow failure/);
			assert.match(error.message, /Teardown failure\(s\): review artifact cleanup failed/);
			assert.deepEqual(error.audit.states, []);
			return true;
		},
	);
});

test("zero repair caps still run checks and fail explicitly", async () => {
	const evidence = new FakeEvidence();
	evidence.findingsByAttempt = [["unresolved caller"]];
	await assert.rejects(
		runImplementationWorkflow("implement behavior", agent(), options(evidence, {maxReviewFixes: 0})),
		(error: unknown) => error instanceof WorkflowFailure && /Review budget exhausted after 0 repair\(s\): unresolved caller/.test(error.message),
	);
});

test("final validation mutation cannot be accepted", async () => {
	const evidence = new FakeEvidence();
	let verification = 0;
	await assert.rejects(
		runImplementationWorkflow("implement behavior", agent((state) => {
			if (state === "verify-tests" && ++verification === 2) evidence.fingerprintValue = "changed";
			return undefined;
		}), options(evidence)),
		/Repository changed during final validation/,
	);
});

test("final validation budget is total and does not reset after review repairs", async () => {
	const evidence = new FakeEvidence();
	evidence.findingsByAttempt = [[], ["new review finding"], []];
	let verification = 0;
	await assert.rejects(
		runImplementationWorkflow("implement behavior", agent((state) => {
			if (state === "verify-tests") {
				verification += 1;
				return verification === 1 ? PASSING : '{"passed":false,"summary":"still failing"}';
			}
			return undefined;
		}), options(evidence, {maxFinalTestFixes: 1})),
		/Final validation budget exhausted after 1 repair\(s\): still failing/,
	);
});

test("asks for a JSON-only final response in every state prompt", async () => {
	const evidence = new FakeEvidence();
	const prompts: string[] = [];
	await runImplementationWorkflow("implement command", agent((_state, prompt) => {
		prompts.push(prompt);
		return undefined;
	}), options(evidence));

	for (const prompt of prompts) {
		assert.equal(prompt.match(/return only the requested JSON object/gi)?.length, 1);
		assert.match(prompt, /with no prose before or after it/);
	}
});

async function runCreateContractResponse(response: string) {
	const evidence = new FakeEvidence();
	let calls = 0;
	const result = await runImplementationWorkflow("implement command", agent((state) => {
		if (state === "create-contract") {
			calls += 1;
			return response;
		}
		return undefined;
	}), options(evidence));
	return {calls, result};
}

test("parses a JSON object embedded in prose without correction", async () => {
	const {calls} = await runCreateContractResponse(
		`Reviewed.\n{"outcome":"completed","workPerformed":false,"summary":"escaped \\"quote {with braces}\\""}\nDone {now}.`,
	);
	assert.equal(calls, 1);
});

test("parses a valid completion after an unmatched opening brace", async () => {
	const {calls} = await runCreateContractResponse(`An unmatched object { starts here.\nResult: ${COMPLETED}`);
	assert.equal(calls, 1);
});

test("records a later valid completion after unrelated JSON", async () => {
	const {calls, result} = await runCreateContractResponse(`Config {"summary":"loaded"}. Result: ${COMPLETED}`);
	assert.equal(calls, 1);
	assert.equal(result.states.find(({state}) => state === "create-contract")?.summary, "done");
});

test("does not treat schema-shaped nested JSON as the state response", async () => {
	const {calls} = await runCreateContractResponse(
		`{"example":{"outcome":"blocked","workPerformed":false,"summary":"sample"}}\nActual: ${COMPLETED}`,
	);
	assert.equal(calls, 1);
});

test("correction retry preserves context and asks the agent to finish missing work", async () => {
	const evidence = new FakeEvidence();
	const prompts: string[] = [];
	await runImplementationWorkflow("implement command", agent((state, prompt) => {
		if (state !== "create-contract") return undefined;
		prompts.push(prompt);
		return prompts.length === 1 ? "I stopped before inspecting the repository." : COMPLETED;
	}), options(evidence));

	assert.equal(prompts.length, 2);
	assert.match(prompts[1] ?? "", /Check the repository state before responding/);
	assert.match(prompts[1] ?? "", /do not redo it/);
	assert.match(prompts[1] ?? "", /If it is incomplete or missing, finish it/);
	assert.match(prompts[1] ?? "", /I stopped before inspecting the repository/);
	assert.match(prompts[1] ?? "", /Original instructions/);
});

test("retries schema-invalid completion responses", async () => {
	const evidence = new FakeEvidence();
	let calls = 0;
	await runImplementationWorkflow("implement command", agent((state) => {
		if (state !== "create-contract") return undefined;
		calls += 1;
		return calls === 1 ? '{"outcome":"completed","summary":"missing workPerformed"}' : COMPLETED;
	}), options(evidence));
	assert.equal(calls, 2);
});

test("retries malformed review claims and verification responses", async () => {
	const evidence = new FakeEvidence();
	const calls = new Map<WorkflowState, number>();
	await runImplementationWorkflow("implement command", agent((state) => {
		const count = (calls.get(state) ?? 0) + 1;
		calls.set(state, count);
		if (state === "review-changes" && count === 1) return "The changes look clean.";
		if (state === "verify-tests" && count === 1) return "All tests passed.";
		return undefined;
	}), options(evidence));

	assert.equal(calls.get("review-changes"), 2);
	assert.equal(calls.get("verify-tests"), 3);
});

test("fails with the state name when the corrected response is still malformed", async () => {
	const evidence = new FakeEvidence();
	let calls = 0;
	await assert.rejects(
		runImplementationWorkflow("implement command", agent((state) => {
			if (state === "create-contract") {
				calls += 1;
				return "not JSON";
			}
			return undefined;
		}), options(evidence)),
		/create-contract returned an invalid response after correction/,
	);
	assert.equal(calls, 2);
});

test("stops when a state reports the task is blocked", async () => {
	const evidence = new FakeEvidence();
	await assert.rejects(
		runImplementationWorkflow("implement two unrelated behaviors", agent((state) =>
			state === "create-contract"
				? '{"outcome":"blocked","workPerformed":false,"summary":"task has two acceptance tests"}'
				: undefined,
		), options(evidence)),
		/create-contract blocked: task has two acceptance tests/,
	);
});
