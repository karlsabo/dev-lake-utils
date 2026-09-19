import assert from "node:assert/strict";
import {mkdtemp, rm, writeFile} from "node:fs/promises";
import {tmpdir} from "node:os";
import {join} from "node:path";
import test from "node:test";
import {createHandoffLedger, type WorkflowHandoff} from "./handoff.ts";

const HANDOFF: WorkflowHandoff = {
	facts: [{claim: "Foo delegates validation to Bar", evidence: [{path: "foo.ts", line: 1}]}],
	relevantPaths: ["foo.ts", "bar.ts"],
	commandsRun: ["npm test (passed)"],
};

async function withRepository(run: (cwd: string) => Promise<void>): Promise<void> {
	const cwd = await mkdtemp(join(tmpdir(), "handoff-test-"));
	try {
		await writeFile(join(cwd, "foo.ts"), "export const foo = 1;\n", "utf8");
		await run(cwd);
	} finally {
		await rm(cwd, {recursive: true, force: true});
	}
}

test("shares cited facts as explicitly untrusted context", async () => {
	await withRepository(async (cwd) => {
		const ledger = createHandoffLedger(cwd);
		await ledger.record("create-contract", HANDOFF);

		const context = await ledger.contextFor("implement");
		assert.match(context ?? "", /untrusted research assistance/);
		assert.match(context ?? "", /Foo delegates validation to Bar \[foo\.ts:1]/);
		assert.match(context ?? "", /npm test \(passed\)/);
	});
});

test("invalidates facts when cited files change while retaining mechanical paths and commands", async () => {
	await withRepository(async (cwd) => {
		const ledger = createHandoffLedger(cwd);
		await ledger.record("create-contract", HANDOFF);
		await writeFile(join(cwd, "foo.ts"), "export const foo = 2;\n", "utf8");

		const context = await ledger.contextFor("implement");
		assert.doesNotMatch(context ?? "", /Foo delegates validation to Bar/);
		assert.match(context ?? "", /foo\.ts/);
		assert.match(context ?? "", /npm test \(passed\)/);
	});
});

test("keeps review claims out of review prompts and fully isolates the skeptic", async () => {
	await withRepository(async (cwd) => {
		const ledger = createHandoffLedger(cwd);
		await ledger.record("implement", HANDOFF);

		const draftContext = await ledger.contextFor("review-changes");
		assert.doesNotMatch(draftContext ?? "", /Foo delegates validation to Bar/);
		assert.match(draftContext ?? "", /Previously relevant paths/);
		assert.equal(await ledger.contextFor("skeptic-review-changes"), undefined);

		await ledger.record("review-white-box-tests", {
			facts: [{claim: "review conclusion", evidence: [{path: "foo.ts"}]}],
			relevantPaths: [],
			commandsRun: [],
		});
		assert.doesNotMatch(await ledger.contextFor("implement") ?? "", /review conclusion/);
	});
});

test("rejects uncited, missing, and repository-external facts", async () => {
	await withRepository(async (cwd) => {
		const ledger = createHandoffLedger(cwd);
		await ledger.record("create-contract", {
			facts: [
				{claim: "uncited", evidence: []},
				{claim: "missing", evidence: [{path: "missing.ts"}]},
				{claim: "external", evidence: [{path: "../outside.ts"}]},
			],
			relevantPaths: [],
			commandsRun: [],
		});

		assert.equal(await ledger.contextFor("implement"), undefined);
	});
});
