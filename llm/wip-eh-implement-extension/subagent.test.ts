import assert from "node:assert/strict";
import {EventEmitter} from "node:events";
import {PassThrough} from "node:stream";
import test from "node:test";
import registerWorkflowExtension from "./index.ts";
import {CancellationError} from "./cancellation.ts";
import {isWorkflowSubagent, piSubagentArgs, runPiSubagent, WORKFLOW_SUBAGENT_ENV} from "./subagent.ts";

const options = {
	cwd: "/repo",
	model: "extension-provider/model-id",
	thinkingLevel: "medium",
};

test("keeps extension discovery enabled for extension-provided models", () => {
	const args = piSubagentArgs("implement behavior", options);

	assert.equal(args.includes("--no-extensions"), false);
	assert.deepEqual(args.slice(args.indexOf("--model"), args.indexOf("--model") + 2), [
		"--model",
		"extension-provider/model-id",
	]);
});

test("uses Pi's default thinking level when none is active", () => {
	const args = piSubagentArgs("implement behavior", { cwd: "/repo", model: "provider/model-id" });

	assert.equal(args.includes("--thinking"), false);
});

class FakeChild extends EventEmitter {
	readonly stdout = new PassThrough();
	readonly stderr = new PassThrough();
	readonly signals: NodeJS.Signals[] = [];

	kill(signal?: NodeJS.Signals | number): boolean {
		if (typeof signal === "string") this.signals.push(signal);
		return true;
	}
}

function fakeSpawn(child: FakeChild): typeof import("node:child_process").spawn {
	return (() => child) as unknown as typeof import("node:child_process").spawn;
}

test("an already-cancelled signal prevents the child from starting", async () => {
	const controller = new AbortController();
	controller.abort();
	let spawnCalls = 0;

	await assert.rejects(
		runPiSubagent("work", {...options, signal: controller.signal}, (() => {
			spawnCalls += 1;
			return new FakeChild();
		}) as unknown as typeof import("node:child_process").spawn),
		CancellationError,
	);
	assert.equal(spawnCalls, 0);
});

test("cancellation during child startup still terminates the child", async () => {
	const child = new FakeChild();
	const controller = new AbortController();
	const run = runPiSubagent("work", {...options, signal: controller.signal}, (() => {
		controller.abort();
		return child;
	}) as unknown as typeof import("node:child_process").spawn);
	const rejected = assert.rejects(run, CancellationError);

	assert.deepEqual(child.signals, ["SIGTERM"]);
	child.emit("close", null);
	await rejected;
});

test("cancellation terminates the child and rejects with an explicit cancellation", async (context) => {
	context.mock.timers.enable({apis: ["setTimeout"]});
	const child = new FakeChild();
	const controller = new AbortController();
	const run = runPiSubagent("work", {...options, signal: controller.signal, killGraceMs: 10}, fakeSpawn(child));
	const rejected = assert.rejects(run, CancellationError);

	controller.abort();
	assert.deepEqual(child.signals, ["SIGTERM"]);
	child.emit("close", null);
	await rejected;
	context.mock.timers.tick(15);
	assert.deepEqual(child.signals, ["SIGTERM"]);
	assert.equal(child.listenerCount("close"), 0);
	assert.equal(child.listenerCount("error"), 0);
	assert.equal(child.stdout.listenerCount("data"), 0);
	assert.equal(child.stderr.listenerCount("data"), 0);
});

test("termination escalates once and timeout cannot race with cancellation", async (context) => {
	context.mock.timers.enable({apis: ["setTimeout"]});
	const child = new FakeChild();
	const controller = new AbortController();
	const run = runPiSubagent(
		"work",
		{...options, signal: controller.signal, timeoutMs: 2, killGraceMs: 8},
		fakeSpawn(child),
	);
	const rejected = assert.rejects(run, CancellationError);

	controller.abort();
	context.mock.timers.tick(15);
	assert.deepEqual(child.signals, ["SIGTERM", "SIGKILL"]);
	child.emit("close", null);
	await rejected;
	assert.deepEqual(child.signals, ["SIGTERM", "SIGKILL"]);
});

test("cancellation replaces a timeout reason during the kill grace period", async (context) => {
	context.mock.timers.enable({apis: ["setTimeout"]});
	const child = new FakeChild();
	const controller = new AbortController();
	const run = runPiSubagent(
		"work",
		{...options, signal: controller.signal, timeoutMs: 2, killGraceMs: 8},
		fakeSpawn(child),
	);
	const rejected = assert.rejects(run, CancellationError);

	context.mock.timers.tick(2);
	assert.deepEqual(child.signals, ["SIGTERM"]);
	controller.abort();
	assert.deepEqual(child.signals, ["SIGTERM"]);
	child.emit("close", null);
	await rejected;
	context.mock.timers.tick(8);
	assert.deepEqual(child.signals, ["SIGTERM"]);
});

test("timeout uses the same SIGTERM and SIGKILL termination policy", async (context) => {
	context.mock.timers.enable({apis: ["setTimeout"]});
	const child = new FakeChild();
	const run = runPiSubagent("work", {...options, timeoutMs: 2, killGraceMs: 8}, fakeSpawn(child));
	const rejected = assert.rejects(run, /Subagent timed out/);

	context.mock.timers.tick(2);
	assert.deepEqual(child.signals, ["SIGTERM"]);
	context.mock.timers.tick(8);
	assert.deepEqual(child.signals, ["SIGTERM", "SIGKILL"]);
	child.emit("close", null);
	await rejected;
});

test("normal close clears timeout and process listeners", async (context) => {
	context.mock.timers.enable({apis: ["setTimeout"]});
	const child = new FakeChild();
	const run = runPiSubagent("work", {...options, timeoutMs: 2, killGraceMs: 8}, fakeSpawn(child));
	child.stdout.write(`${JSON.stringify({
		type: "message_end",
		message: {role: "assistant", content: [{type: "text", text: "done"}]},
	})}\n`);
	child.emit("close", 0);

	assert.equal(await run, "done");
	context.mock.timers.tick(15);
	assert.deepEqual(child.signals, []);
	assert.equal(child.listenerCount("close"), 0);
	assert.equal(child.listenerCount("error"), 0);
});

test("process errors clear termination timers and listeners", async (context) => {
	context.mock.timers.enable({apis: ["setTimeout"]});
	const child = new FakeChild();
	const run = runPiSubagent("work", {...options, timeoutMs: 2, killGraceMs: 8}, fakeSpawn(child));
	const rejected = assert.rejects(run, /spawn failed/);
	child.emit("error", new Error("spawn failed"));

	await rejected;
	context.mock.timers.tick(15);
	assert.deepEqual(child.signals, []);
	assert.equal(child.listenerCount("close"), 0);
	assert.equal(child.listenerCount("error"), 0);
});

test("does not register this workflow extension in worker processes", () => {
	const previousValue = process.env[WORKFLOW_SUBAGENT_ENV];
	let registered = false;
	process.env[WORKFLOW_SUBAGENT_ENV] = "1";
	try {
		registerWorkflowExtension({ registerCommand: () => (registered = true) } as never);
	} finally {
		if (previousValue === undefined) delete process.env[WORKFLOW_SUBAGENT_ENV];
		else process.env[WORKFLOW_SUBAGENT_ENV] = previousValue;
	}

	assert.equal(registered, false);
	assert.equal(isWorkflowSubagent({ [WORKFLOW_SUBAGENT_ENV]: "1" }), true);
	assert.equal(isWorkflowSubagent({}), false);
});
