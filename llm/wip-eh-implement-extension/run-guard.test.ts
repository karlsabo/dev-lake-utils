import assert from "node:assert/strict";
import test from "node:test";
import {WorkflowRunGuard} from "./run-guard.ts";

function deferred(): { promise: Promise<void>; resolve: () => void } {
	let resolve!: () => void;
	const promise = new Promise<void>((done) => {
		resolve = done;
	});
	return { promise, resolve };
}

test("waits for the main agent to become idle before starting", async () => {
	const idle = deferred();
	const guard = new WorkflowRunGuard();
	let started = false;

	const attempt = guard.run(
		() => idle.promise,
		async () => {
			started = true;
		},
	);

	await Promise.resolve();
	assert.equal(started, false);
	idle.resolve();
	assert.deepEqual(await attempt, { started: true, value: undefined });
	assert.equal(started, true);
});

test("rejects another invocation while a workflow is active", async () => {
	const workflowDone = deferred();
	const guard = new WorkflowRunGuard();
	let workflowStarts = 0;

	const first = guard.run(
		async () => {},
		async () => {
			workflowStarts += 1;
			await workflowDone.promise;
		},
	);
	await Promise.resolve();

	const second = await guard.run(
		async () => {},
		async () => {
			workflowStarts += 1;
		},
	);

	assert.deepEqual(second, { started: false });
	assert.equal(workflowStarts, 1);
	workflowDone.resolve();
	await first;
});

test("allows another invocation after a workflow fails", async () => {
	const guard = new WorkflowRunGuard();

	await assert.rejects(
		guard.run(
			async () => {},
			async () => {
				throw new Error("failed");
			},
		),
		/failed/,
	);

	const retry = await guard.run(async () => {}, async () => "retried");
	assert.deepEqual(retry, { started: true, value: "retried" });
});
