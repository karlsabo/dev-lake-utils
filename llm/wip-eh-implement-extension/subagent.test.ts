import assert from "node:assert/strict";
import test from "node:test";
import registerWorkflowExtension from "./index.ts";
import {isWorkflowSubagent, piSubagentArgs, WORKFLOW_SUBAGENT_ENV} from "./subagent.ts";

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
