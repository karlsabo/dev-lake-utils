import assert from "node:assert/strict";
import test from "node:test";
import {runWithTeardown} from "./index.ts";
import {WorkflowFailure, type WorkflowResult} from "./workflow.ts";

const COMPLETED_RESULT: WorkflowResult = {
	outcome: "completed",
	states: [{state: "implement", summary: "implementation complete"}],
	initialTestFixes: 1,
	finalTestFixes: 0,
	reviewFixes: 1,
};

test("teardown failure converts completed work into one failure with its audit", async () => {
	const calls: string[] = [];
	await assert.rejects(
		runWithTeardown(
			async () => COMPLETED_RESULT,
			[
				() => {
					calls.push("baseline");
					throw new Error("baseline cleanup failed");
				},
				() => {
					calls.push("progress");
				},
			],
		),
		(error: unknown) => {
			assert.ok(error instanceof WorkflowFailure);
			assert.match(error.message, /baseline cleanup failed/);
			assert.deepEqual(error.audit, {
				states: COMPLETED_RESULT.states,
				initialTestFixes: 1,
				finalTestFixes: 0,
				reviewFixes: 1,
			});
			return true;
		},
	);
	assert.deepEqual(calls, ["baseline", "progress"]);
});

test("teardown reports cleanup failures without replacing the existing workflow audit", async () => {
	const original = new WorkflowFailure("workflow failed", {
		states: [{state: "verify-tests", summary: "tests failed"}],
		initialTestFixes: 2,
		finalTestFixes: 0,
		reviewFixes: 0,
	});
	await assert.rejects(
		runWithTeardown(
			async () => {
				throw original;
			},
			[
				() => {
					throw new Error("baseline cleanup also failed");
				},
				() => {
					throw new Error("progress teardown also failed");
				},
			],
		),
		(error: unknown) => {
			assert.ok(error instanceof WorkflowFailure);
			assert.match(error.message, /workflow failed/);
			assert.match(error.message, /baseline cleanup also failed/);
			assert.match(error.message, /progress teardown also failed/);
			assert.equal(error.audit, original.audit);
			return true;
		},
	);
});
