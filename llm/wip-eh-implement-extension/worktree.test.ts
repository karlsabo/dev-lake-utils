import assert from "node:assert/strict";
import {execFileSync} from "node:child_process";
import {access, mkdtemp, readFile, rm, writeFile} from "node:fs/promises";
import {tmpdir} from "node:os";
import {join} from "node:path";
import test from "node:test";
import {captureWorktreeBaseline, parseChangedPaths} from "./worktree.ts";

test("parses tracked and untracked paths from null-delimited git status", () => {
	const status = " M tracked file.ts\0?? untracked file.ts\0";

	assert.deepEqual(parseChangedPaths(status), ["tracked file.ts", "untracked file.ts"]);
});

test("includes both paths for renames", () => {
	const status = "R  renamed.ts\0original.ts\0";

	assert.deepEqual(parseChangedPaths(status), ["original.ts", "renamed.ts"]);
});

test("retains the starting content of dirty files until cleanup", async (context) => {
	const cwd = await mkdtemp(join(tmpdir(), "worktree-baseline-test-"));
	context.after(() => rm(cwd, { recursive: true, force: true }));
	execFileSync("git", ["init", "--quiet"], { cwd });
	const changedPath = "dirty file.ts";
	await writeFile(join(cwd, changedPath), "indexed content\n");
	execFileSync("git", ["add", changedPath], { cwd });
	await writeFile(join(cwd, changedPath), "content at workflow start\n");

	const baseline = await captureWorktreeBaseline(cwd);
	const change = baseline.changes.find(({ path }) => path === changedPath);
	assert.ok(change?.snapshotPath);
	assert.equal(await readFile(change.snapshotPath, "utf8"), "content at workflow start\n");

	await writeFile(join(cwd, changedPath), "content after workflow\n");
	assert.equal(await readFile(change.snapshotPath, "utf8"), "content at workflow start\n");
	await baseline.cleanup();
	await assert.rejects(access(change.snapshotPath), /ENOENT/);
});
