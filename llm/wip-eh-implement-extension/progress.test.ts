import assert from "node:assert/strict";
import test from "node:test";
import {formatElapsed, startWorkflowProgress, type WorkflowProgressUI} from "./progress.ts";

test("advertises cancellation throughout workflow progress and clears it when stopped", (context) => {
	context.mock.timers.enable({apis: ["setInterval"]});
	const statuses: Array<string | undefined> = [];
	const widgets: Array<string[] | undefined> = [];
	const ui: WorkflowProgressUI = {
		setStatus: (_key, text) => statuses.push(text),
		setWidget: (_key, content) => widgets.push(content),
	};
	let time = 0;

	const progress = startWorkflowProgress(ui, () => time);
	assert.deepEqual(widgets.at(-1), [
		"WIP EH implement · 00:00 elapsed",
		"Preparing worktree baseline",
		"Cancel: /wip-eh-implement-cancel",
	]);

	time = 1_000;
	context.mock.timers.tick(1_000);
	assert.deepEqual(widgets.at(-1), [
		"WIP EH implement · 00:01 elapsed",
		"Preparing worktree baseline",
		"Cancel: /wip-eh-implement-cancel",
	]);

	time = 65_000;
	progress.transition("Writing black-box tests");
	assert.equal(statuses.at(-1), "Writing black-box tests");
	assert.deepEqual(widgets.at(-1), [
		"WIP EH implement · 01:05 elapsed",
		"Writing black-box tests",
		"Cancel: /wip-eh-implement-cancel",
	]);

	progress.stop();
	assert.equal(statuses.at(-1), undefined);
	assert.equal(widgets.at(-1), undefined);
});

test("ignores transitions and repeated stops after progress is stopped", (context) => {
	context.mock.timers.enable({apis: ["setInterval"]});
	let statusUpdates = 0;
	let widgetUpdates = 0;
	const ui: WorkflowProgressUI = {
		setStatus: () => statusUpdates++,
		setWidget: () => widgetUpdates++,
	};

	const progress = startWorkflowProgress(ui, () => 0);
	progress.stop();
	progress.transition("This must not be displayed");
	progress.stop();
	context.mock.timers.tick(1_000);

	assert.equal(statusUpdates, 2);
	assert.equal(widgetUpdates, 2);
});

test("formats elapsed durations", () => {
	assert.equal(formatElapsed(999), "00:00");
	assert.equal(formatElapsed(3_661_000), "01:01:01");
	assert.equal(formatElapsed(-1), "00:00");
});
