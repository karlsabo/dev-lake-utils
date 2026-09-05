import assert from "node:assert/strict";
import test from "node:test";
import {runImplementationWorkflow, type StateAgent, type WorkflowState} from "./workflow.ts";

const COMPLETED = '{"outcome":"completed","workPerformed":true,"summary":"done"}';
const NO_WORK = '{"outcome":"completed","workPerformed":false,"summary":"no work needed"}';
const CLEAN_REVIEW = '{"findings":[],"summary":"clean"}';
const PASSING = '{"passed":true,"summary":"tests passed"}';
const GUIDANCE_PATH = "/repo/llm/notes.md";

function standardResponse(state: WorkflowState): string {
	if (state === "review-black-box-tests" || state === "review-white-box-tests" || state === "review-changes") {
		return CLEAN_REVIEW;
	}
	if (state === "verify-tests") return PASSING;
	return COMPLETED;
}

function options(overrides: Partial<Parameters<typeof runImplementationWorkflow>[2]> = {}) {
	return { guidancePath: GUIDANCE_PATH, initialChanges: [], ...overrides };
}

test("runs each required clean implementation state through a separate agent invocation", async () => {
	const calls: Array<{ state: WorkflowState; prompt: string }> = [];
	const agent: StateAgent = async (state, prompt) => {
		calls.push({ state, prompt });
		return standardResponse(state);
	};

	const result = await runImplementationWorkflow("implement one observable behavior", agent, options());

	assert.deepEqual(
		calls.map((call) => call.state),
		[
			"create-contract",
			"write-black-box-tests",
			"review-black-box-tests",
			"implement",
			"verify-tests",
			"write-white-box-tests",
			"review-white-box-tests",
			"review-changes",
			"verify-tests",
		],
	);
	assert.equal(result.states.length, calls.length);
	assert.equal(result.testFixes, 0);
	assert.equal(result.reviewFixes, 0);
	assert.ok(calls.every((call) => call.prompt.includes(GUIDANCE_PATH)));
	assert.ok(calls.every((call) => call.prompt.includes("implement one observable behavior")));
	assert.match(
		calls.find((call) => call.state === "review-changes")?.prompt ?? "",
		/git status --short --untracked-files=all/,
	);
});

test("skips black-box test review when the test-writing state reports no work", async () => {
	const states: WorkflowState[] = [];

	await runImplementationWorkflow(
		"implement behavior already covered by black-box tests",
		async (state) => {
			states.push(state);
			if (state === "write-black-box-tests") return NO_WORK;
			return standardResponse(state);
		},
		options(),
	);

	assert.equal(states.includes("review-black-box-tests"), false);
	assert.equal(states.includes("fix-black-box-test-findings"), false);
	assert.ok(states.indexOf("implement") > states.indexOf("write-black-box-tests"));
});

test("skips white-box test review when the test-writing state reports no work", async () => {
	const states: WorkflowState[] = [];

	await runImplementationWorkflow(
		"implement behavior with sufficient internal coverage",
		async (state) => {
			states.push(state);
			if (state === "write-white-box-tests") return NO_WORK;
			return standardResponse(state);
		},
		options(),
	);

	assert.equal(states.includes("review-white-box-tests"), false);
	assert.equal(states.includes("fix-white-box-test-findings"), false);
	assert.ok(states.indexOf("review-changes") > states.indexOf("write-white-box-tests"));
});

test("runs test-finding fixes only when test review returns findings", async () => {
	const states: WorkflowState[] = [];

	await runImplementationWorkflow(
		"implement behavior",
		async (state) => {
			states.push(state);
			if (state === "review-black-box-tests") {
				return '{"findings":["strengthen black-box assertion"],"summary":"one finding"}';
			}
			if (state === "review-white-box-tests") {
				return '{"findings":["cover internal branch"],"summary":"one finding"}';
			}
			return standardResponse(state);
		},
		options(),
	);

	assert.equal(states.filter((state) => state === "fix-black-box-test-findings").length, 1);
	assert.equal(states.filter((state) => state === "fix-white-box-test-findings").length, 1);
});

test("treats planned comments as a review-remediation batch", async () => {
	const prompts = new Map<WorkflowState, string>();

	await runImplementationWorkflow(
		"fixes if reasonable /repo/planning/uncommitted-main-planned-comments.md",
		async (state, prompt) => {
			prompts.set(state, prompt);
			return standardResponse(state);
		},
		options(),
	);

	assert.match(prompts.get("create-contract") ?? "", /review-remediation batch/);
	assert.match(prompts.get("create-contract") ?? "", /Do not block it merely because it has multiple comments/);
	assert.match(prompts.get("create-contract") ?? "", /existing contracts already express the required behavior/);
	assert.match(prompts.get("write-black-box-tests") ?? "", /cover each supported comment independently/);
	assert.match(prompts.get("write-black-box-tests") ?? "", /expected to fail until the implementation state/);
});

test("reviews only the workflow delta in paths that were dirty before it started", async () => {
	let reviewPrompt = "";
	const preExistingPath = "notes/user draft.md";
	const snapshotPath = "/tmp/wip-eh-implement-123/0";

	await runImplementationWorkflow(
		"implement command",
		async (state, prompt) => {
			if (state === "review-changes") reviewPrompt = prompt;
			return standardResponse(state);
		},
		options({ initialChanges: [{ path: preExistingPath, snapshotPath }] }),
	);

	assert.match(reviewPrompt, /review only this workflow's delta/);
	assert.match(reviewPrompt, new RegExp(JSON.stringify(preExistingPath).replace(/[.*+?^${}()|[\]\\]/g, "\\$&")));
	assert.match(reviewPrompt, new RegExp(JSON.stringify(snapshotPath).replace(/[.*+?^${}()|[\]\\]/g, "\\$&")));
	assert.match(reviewPrompt, /do not report findings about pre-existing changes/);
});

test("allows only the required planned-comments artifact during final review", async () => {
	let reviewPrompt = "";

	await runImplementationWorkflow(
		"implement command",
		async (state, prompt) => {
			if (state === "review-changes") reviewPrompt = prompt;
			return standardResponse(state);
		},
		options({ prReviewSkillPath: "/skills/eh-pr-review/SKILL.md" }),
	);

	assert.match(reviewPrompt, /Do not edit repository files/);
	assert.match(reviewPrompt, /create or update only the planned-comments artifact/);
	assert.match(reviewPrompt, /skeptic pass may revise that artifact/);
});

test("fixes failing tests and verifies them again", async () => {
	let verificationCount = 0;
	const states: WorkflowState[] = [];

	const result = await runImplementationWorkflow(
		"implement command",
		async (state) => {
			states.push(state);
			if (state === "verify-tests") {
				verificationCount += 1;
				return verificationCount === 1
					? '{"passed":false,"summary":"focused test failed"}'
					: PASSING;
			}
			return standardResponse(state);
		},
		options(),
	);

	assert.equal(result.testFixes, 1);
	assert.deepEqual(
		states.filter((state) => state === "verify-tests" || state === "fix-failing-tests"),
		["verify-tests", "fix-failing-tests", "verify-tests", "verify-tests"],
	);
});

test("gives final verification its own repair budget", async () => {
	let verificationCount = 0;
	const states: WorkflowState[] = [];

	const result = await runImplementationWorkflow(
		"implement command",
		async (state) => {
			states.push(state);
			if (state === "verify-tests") {
				verificationCount += 1;
				return verificationCount % 2 === 1
					? '{"passed":false,"summary":"validation failed"}'
					: PASSING;
			}
			return standardResponse(state);
		},
		options({ maxTestFixes: 1 }),
	);

	assert.equal(result.testFixes, 2);
	assert.deepEqual(
		states.filter((state) => state === "verify-tests" || state === "fix-failing-tests"),
		[
			"verify-tests",
			"fix-failing-tests",
			"verify-tests",
			"verify-tests",
			"fix-failing-tests",
			"verify-tests",
		],
	);
});

test("loops through change review until no findings remain", async () => {
	let reviewCount = 0;
	const reviewPrompts: string[] = [];

	const result = await runImplementationWorkflow(
		"implement command",
		async (state, prompt) => {
			if (state === "review-changes") {
				reviewCount += 1;
				return reviewCount === 1
					? '{"findings":["Handle the error path"],"summary":"one finding"}'
					: CLEAN_REVIEW;
			}
			if (state === "fix-review-findings") reviewPrompts.push(prompt);
			return standardResponse(state);
		},
		options({ prReviewSkillPath: "/skills/eh-pr-review/SKILL.md" }),
	);

	assert.equal(result.reviewFixes, 1);
	assert.equal(reviewCount, 2);
	assert.equal(reviewPrompts.length, 1);
	assert.match(reviewPrompts[0], /Handle the error path/);
});

test("stops when the contract agent reports a multi-slice task", async () => {
	await assert.rejects(
		runImplementationWorkflow(
			"implement two unrelated behaviors",
			async () => '{"outcome":"blocked","workPerformed":false,"summary":"task has two acceptance tests"}',
			options(),
		),
		/create-contract blocked: task has two acceptance tests/,
	);
});
