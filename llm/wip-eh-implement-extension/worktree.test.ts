import assert from "node:assert/strict";
import {execFileSync} from "node:child_process";
import {access, mkdtemp, readFile, rm, stat, utimes, writeFile} from "node:fs/promises";
import {tmpdir} from "node:os";
import {dirname, join} from "node:path";
import test from "node:test";
import {captureWorktreeBaseline, parseChangedPaths} from "./worktree.ts";

async function fileMetadata(path: string) {
	const {ino, size, mtimeMs, ctimeMs} = await stat(path);
	return {ino, size, mtimeMs, ctimeMs};
}

test("parses tracked and untracked paths from null-delimited git status", () => {
	const status = " M tracked file.ts\0?? untracked file.ts\0";

	assert.deepEqual(parseChangedPaths(status), ["tracked file.ts", "untracked file.ts"]);
});

test("includes both paths for renames", () => {
	const status = "R  renamed.ts\0original.ts\0";

	assert.deepEqual(parseChangedPaths(status), ["original.ts", "renamed.ts"]);
});

test("cleanup removes snapshots without changing worktree files", async (context) => {
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

	const changedWorktreePath = join(cwd, changedPath);
	await writeFile(changedWorktreePath, "content after workflow\n");
	const createdPath = "created during workflow.ts";
	const createdWorktreePath = join(cwd, createdPath);
	await writeFile(createdWorktreePath, "partial new work\n");
	const preservedTime = new Date("2000-01-01T00:00:00.000Z");
	await Promise.all([
		utimes(changedWorktreePath, preservedTime, preservedTime),
		utimes(createdWorktreePath, preservedTime, preservedTime),
	]);
	const changedMetadata = await fileMetadata(changedWorktreePath);
	const createdMetadata = await fileMetadata(createdWorktreePath);
	assert.equal(await readFile(change.snapshotPath, "utf8"), "content at workflow start\n");

	await baseline.cleanup();

	await assert.rejects(access(change.snapshotPath), /ENOENT/);
	await assert.rejects(access(dirname(change.snapshotPath)), /ENOENT/);
	assert.equal(await readFile(changedWorktreePath, "utf8"), "content after workflow\n");
	assert.equal(await readFile(createdWorktreePath, "utf8"), "partial new work\n");
	assert.deepEqual(await fileMetadata(changedWorktreePath), changedMetadata);
	assert.deepEqual(await fileMetadata(createdWorktreePath), createdMetadata);
});
