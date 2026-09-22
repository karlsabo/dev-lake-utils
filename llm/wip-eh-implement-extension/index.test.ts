import assert from "node:assert/strict";
import {execFileSync} from "node:child_process";
import {access, mkdtemp, readFile, rm, stat, utimes, writeFile} from "node:fs/promises";
import {tmpdir} from "node:os";
import {dirname, join} from "node:path";
import {setTimeout as delay} from "node:timers/promises";
import test from "node:test";
import registerWorkflowExtension, {cancellationReason, runWithTeardown} from "./index.ts";
import {WORKFLOW_SUBAGENT_ENV} from "./subagent.ts";
import {WorkflowCancelled, WorkflowFailure, type WorkflowResult} from "./workflow.ts";
import {captureWorktreeBaseline} from "./worktree.ts";

const COMPLETED_RESULT: WorkflowResult = {
	outcome: "completed",
	states: [{state: "implement", summary: "implementation complete"}],
	initialTestFixes: 1,
	finalTestFixes: 0,
	reviewFixes: 1,
};

async function fileMetadata(path: string) {
	const {ino, size, mtimeMs, ctimeMs} = await stat(path);
	return {ino, size, mtimeMs, ctimeMs};
}

async function waitForLogEntries(path: string, expected: number): Promise<string[]> {
	for (let attempt = 0; attempt < 100; attempt += 1) {
		const entries = await readFile(path, "utf8").then((contents) => contents.trim().split("\n"), () => []);
		if (entries.length >= expected) return entries;
		await delay(20);
	}
	throw new Error(`Timed out waiting for ${expected} workflow states`);
}

const SUBAGENT_FIXTURE = `
import {appendFileSync, readFileSync, writeFileSync} from "node:fs";
import {join} from "node:path";

const prompt = process.argv.at(-1) ?? "";
const state = [
  ["flesh out the smallest classes", "create-contract"],
  ["write black-box unit tests", "write-black-box-tests"],
  ["Write the smallest production implementation", "implement"],
  ["Run the narrowest relevant tests", "verify-tests"],
  ["Diagnose the failing validation", "fix-failing-tests"],
].find(([marker]) => prompt.includes(marker))?.[1];
if (!state) throw new Error("Unrecognized workflow state");
const logPath = join(process.cwd(), "states.log");
appendFileSync(logPath, state + "\\n");
const respond = (value) => console.log(JSON.stringify({
  type: "message_end",
  message: {role: "assistant", content: [{type: "text", text: JSON.stringify(value)}]},
}));
if (state === "create-contract") {
  respond({outcome: "completed", workPerformed: false, continueWorkflow: true, summary: "contract ready"});
} else if (state === "write-black-box-tests") {
  respond({outcome: "completed", workPerformed: false, summary: "tests already present"});
} else if (state === "implement") {
  writeFileSync(join(process.cwd(), "partial-work.ts"), "preserved child edit\\n");
  respond({outcome: "completed", workPerformed: true, summary: "implementation complete"});
} else if (state === "fix-failing-tests") {
  respond({outcome: "completed", workPerformed: true, summary: "validation repaired"});
} else {
  const verificationCount = readFileSync(logPath, "utf8").split("\\n").filter((entry) => entry === "verify-tests").length;
  if (verificationCount === 1) respond({passed: false, summary: "one repair required"});
  else setInterval(() => {}, 1_000);
}
`;

test("cancel command records cancellation once and reports no-active requests without a result", async () => {
	const handlers = new Map<string, (args: string, context: any) => Promise<void>>();
	const entries: Array<{type: string; data: Record<string, unknown>}> = [];
	const notifications: Array<{message: string; level: string}> = [];
	let releaseIdle!: () => void;
	const idle = new Promise<void>((resolve) => {
		releaseIdle = resolve;
	});
	const subagentFlag = process.env[WORKFLOW_SUBAGENT_ENV];
	delete process.env[WORKFLOW_SUBAGENT_ENV];
	try {
		registerWorkflowExtension({
			registerCommand(name: string, command: {handler: (args: string, context: any) => Promise<void>}) {
				handlers.set(name, command.handler);
			},
			appendEntry(type: string, data: Record<string, unknown>) {
				entries.push({type, data});
			},
		} as never);
	} finally {
		if (subagentFlag === undefined) delete process.env[WORKFLOW_SUBAGENT_ENV];
		else process.env[WORKFLOW_SUBAGENT_ENV] = subagentFlag;
	}
	const context = {
		cwd: "/repo",
		model: {provider: "provider", id: "model"},
		thinkingLevel: undefined,
		waitForIdle: () => idle,
		ui: {
			notify: (message: string, level: string) => notifications.push({message, level}),
			setStatus: () => {},
			setWidget: () => {},
		},
	};
	const run = handlers.get("wip-eh-implement")?.("one task", context);
	assert.ok(run);

	await handlers.get("wip-eh-implement-cancel")?.("", context);
	await handlers.get("wip-eh-implement-cancel")?.("", context);
	releaseIdle();
	await run;

	assert.equal(entries.length, 1);
	assert.equal(entries[0]?.type, "wip-eh-implement-result");
	assert.deepEqual(
		Object.fromEntries(Object.entries(entries[0]?.data ?? {}).filter(([key]) => key !== "cancelledAt")),
		{
			task: "one task",
			outcome: "cancelled",
			reason: "Cancelled by user",
			states: [],
			initialTestFixes: 0,
			finalTestFixes: 0,
			reviewFixes: 0,
		},
	);
	assert.equal(notifications.some(({message, level}) => /cancelled/.test(message) && level === "warning"), true);
	assert.equal(notifications.some(({level}) => level === "error"), false);

	await handlers.get("wip-eh-implement-cancel")?.("", context);
	assert.equal(entries.length, 1);
	assert.match(notifications.at(-1)?.message ?? "", /No implementation workflow is active/);
});

test("cancel command stops an active child, retains audit, and preserves its edits", {timeout: 10_000}, async (context) => {
	const cwd = await mkdtemp(join(tmpdir(), "cancel-command-test-"));
	context.after(() => rm(cwd, {recursive: true, force: true}));
	execFileSync("git", ["init", "--quiet"], {cwd});
	const fixturePath = join(cwd, "subagent-fixture.mjs");
	await writeFile(fixturePath, SUBAGENT_FIXTURE);
	const handlers = new Map<string, (args: string, context: any) => Promise<void>>();
	const entries: Array<{type: string; data: Record<string, unknown>}> = [];
	const notifications: Array<{message: string; level: string}> = [];
	const subagentFlag = process.env[WORKFLOW_SUBAGENT_ENV];
	delete process.env[WORKFLOW_SUBAGENT_ENV];
	try {
		registerWorkflowExtension({
			registerCommand(name: string, command: {handler: (args: string, context: any) => Promise<void>}) {
				handlers.set(name, command.handler);
			},
			appendEntry(type: string, data: Record<string, unknown>) {
				entries.push({type, data});
			},
		} as never);
	} finally {
		if (subagentFlag === undefined) delete process.env[WORKFLOW_SUBAGENT_ENV];
		else process.env[WORKFLOW_SUBAGENT_ENV] = subagentFlag;
	}
	const commandContext = {
		cwd,
		model: {provider: "provider", id: "model"},
		thinkingLevel: undefined,
		waitForIdle: async () => {},
		ui: {
			notify: (message: string, level: string) => notifications.push({message, level}),
			setStatus: () => {},
			setWidget: () => {},
		},
	};
	const originalScript = process.argv[1];
	process.argv[1] = fixturePath;
	try {
		const run = handlers.get("wip-eh-implement")?.("one task", commandContext);
		assert.ok(run);
		const startedStates = await waitForLogEntries(join(cwd, "states.log"), 6);
		assert.deepEqual(startedStates, [
			"create-contract",
			"write-black-box-tests",
			"implement",
			"verify-tests",
			"fix-failing-tests",
			"verify-tests",
		]);

		await handlers.get("wip-eh-implement-cancel")?.("", commandContext);
		await run;
	} finally {
		if (originalScript === undefined) delete process.argv[1];
		else process.argv[1] = originalScript;
	}

	await delay(50);
	assert.deepEqual((await readFile(join(cwd, "states.log"), "utf8")).trim().split("\n"), [
		"create-contract",
		"write-black-box-tests",
		"implement",
		"verify-tests",
		"fix-failing-tests",
		"verify-tests",
	]);
	assert.equal(await readFile(join(cwd, "partial-work.ts"), "utf8"), "preserved child edit\n");
	assert.equal(entries.length, 1);
	assert.equal(entries[0]?.type, "wip-eh-implement-result");
	assert.deepEqual(
		Object.fromEntries(Object.entries(entries[0]?.data ?? {}).filter(([key]) => key !== "cancelledAt")),
		{
			task: "one task",
			outcome: "cancelled",
			reason: "Cancelled by user",
			states: [
				{state: "create-contract", summary: "contract ready", handoff: undefined},
				{state: "write-black-box-tests", summary: "tests already present", handoff: undefined},
				{state: "implement", summary: "implementation complete", handoff: undefined},
				{state: "verify-tests", summary: "one repair required", handoff: undefined},
				{state: "fix-failing-tests", summary: "validation repaired", handoff: undefined},
			],
			initialTestFixes: 1,
			finalTestFixes: 0,
			reviewFixes: 0,
		},
	);
	assert.equal(notifications.some(({message, level}) => /cancelled/.test(message) && level === "warning"), true);
	assert.equal(notifications.some(({level}) => level === "error"), false);
});

test("teardown preserves cancellation type and completed-state audit", async () => {
	const cancellation = new WorkflowCancelled("Cancelled by user", {
		states: COMPLETED_RESULT.states,
		initialTestFixes: 1,
		finalTestFixes: 0,
		reviewFixes: 1,
	});
	await assert.rejects(
		runWithTeardown(
			async () => {
				throw cancellation;
			},
			[() => {}],
		),
		(error: unknown) => error === cancellation,
	);
});

test("cancellation teardown removes its baseline without rolling back partial work", async (context) => {
	const cwd = await mkdtemp(join(tmpdir(), "cancel-teardown-test-"));
	context.after(() => rm(cwd, {recursive: true, force: true}));
	execFileSync("git", ["init", "--quiet"], {cwd});
	const dirtyPath = "dirty-before-workflow.ts";
	await writeFile(join(cwd, dirtyPath), "indexed content\n");
	execFileSync("git", ["add", dirtyPath], {cwd});
	await writeFile(join(cwd, dirtyPath), "content at workflow start\n");
	const baseline = await captureWorktreeBaseline(cwd);
	const snapshotPath = baseline.changes.find(({path}) => path === dirtyPath)?.snapshotPath;
	assert.ok(snapshotPath);

	const dirtyWorktreePath = join(cwd, dirtyPath);
	await writeFile(dirtyWorktreePath, "partial changed work\n");
	const createdPath = "created-during-workflow.ts";
	const createdWorktreePath = join(cwd, createdPath);
	await writeFile(createdWorktreePath, "partial new work\n");
	const preservedTime = new Date("2000-01-01T00:00:00.000Z");
	await Promise.all([
		utimes(dirtyWorktreePath, preservedTime, preservedTime),
		utimes(createdWorktreePath, preservedTime, preservedTime),
	]);
	const dirtyMetadata = await fileMetadata(dirtyWorktreePath);
	const createdMetadata = await fileMetadata(createdWorktreePath);
	const cancellation = new WorkflowCancelled("Cancelled by user", {
		states: [],
		initialTestFixes: 0,
		finalTestFixes: 0,
		reviewFixes: 0,
	});

	await assert.rejects(
		runWithTeardown(
			async () => {
				throw cancellation;
			},
			[baseline.cleanup],
		),
		(error: unknown) => error === cancellation,
	);

	await assert.rejects(access(snapshotPath), /ENOENT/);
	await assert.rejects(access(dirname(snapshotPath)), /ENOENT/);
	assert.equal(await readFile(dirtyWorktreePath, "utf8"), "partial changed work\n");
	assert.equal(await readFile(createdWorktreePath, "utf8"), "partial new work\n");
	assert.deepEqual(await fileMetadata(dirtyWorktreePath), dirtyMetadata);
	assert.deepEqual(await fileMetadata(createdWorktreePath), createdMetadata);
});

test("cancellation during teardown converts a completed result and retains its audit", async () => {
	const controller = new AbortController();
	await assert.rejects(
		runWithTeardown(
			async () => COMPLETED_RESULT,
			[() => controller.abort()],
			controller.signal,
		),
		(error: unknown) => {
			assert.ok(error instanceof WorkflowCancelled);
			assert.deepEqual(error.audit.states, COMPLETED_RESULT.states);
			return true;
		},
	);
});

test("cancelled teardown retains cleanup failure details", async () => {
	const cancellation = new WorkflowCancelled("Cancelled by user", {
		states: COMPLETED_RESULT.states,
		initialTestFixes: 1,
		finalTestFixes: 0,
		reviewFixes: 1,
	});
	await assert.rejects(
		runWithTeardown(
			async () => {
				throw cancellation;
			},
			[() => {
				throw new Error("baseline cleanup failed");
			}],
		),
		(error: unknown) => {
			assert.ok(error instanceof WorkflowCancelled);
			assert.match(error.message, /Cancelled by user/);
			assert.match(error.message, /baseline cleanup failed/);
			assert.equal(error.reason, "Cancelled by user");
			assert.equal(cancellationReason(error), "Cancelled by user");
			assert.equal(error.audit, cancellation.audit);
			return true;
		},
	);
});

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
