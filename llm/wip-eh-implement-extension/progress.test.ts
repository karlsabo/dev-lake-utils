import assert from "node:assert/strict";
import test from "node:test";
import {formatElapsed, startWorkflowProgress, type WorkflowProgressUI} from "./progress.ts";

test("shows persistent workflow state and clears it when stopped", () => {
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
		"A separate Pi subagent is working on this state.",
	]);

	time = 65_000;
	progress.transition("Writing black-box tests");
	assert.equal(statuses.at(-1), "Writing black-box tests");
	assert.deepEqual(widgets.at(-1), [
		"WIP EH implement · 01:05 elapsed",
		"Writing black-box tests",
		"A separate Pi subagent is working on this state.",
	]);

	progress.stop();
	assert.equal(statuses.at(-1), undefined);
	assert.equal(widgets.at(-1), undefined);
});

test("formats elapsed durations", () => {
	assert.equal(formatElapsed(999), "00:00");
	assert.equal(formatElapsed(3_661_000), "01:01:01");
	assert.equal(formatElapsed(-1), "00:00");
});
