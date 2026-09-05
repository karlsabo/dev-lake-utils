import {execFile} from "node:child_process";
import {copyFile, lstat, mkdtemp, readlink, rm, symlink} from "node:fs/promises";
import {tmpdir} from "node:os";
import {join} from "node:path";
import {promisify} from "node:util";

const execFileAsync = promisify(execFile);

export interface InitialChange {
	path: string;
	snapshotPath?: string;
}

export interface WorktreeBaseline {
	changes: InitialChange[];
	cleanup: () => Promise<void>;
}

export async function captureWorktreeBaseline(cwd: string): Promise<WorktreeBaseline> {
	const changedPaths = await captureChangedPaths(cwd);
	const snapshotDirectory = await mkdtemp(join(tmpdir(), "wip-eh-implement-"));

	try {
		const changes = await Promise.all(
			changedPaths.map(async (path, index): Promise<InitialChange> => {
				const worktreePath = join(cwd, path);
				try {
					const stats = await lstat(worktreePath);
					const snapshotPath = join(snapshotDirectory, String(index));
					if (stats.isFile()) await copyFile(worktreePath, snapshotPath);
					else if (stats.isSymbolicLink()) await symlink(await readlink(worktreePath), snapshotPath);
					else throw new Error(`Cannot retain starting version of non-file path: ${path}`);
					return { path, snapshotPath };
				} catch (error) {
					if (isMissingPath(error)) return { path };
					throw error;
				}
			}),
		);
		return {
			changes,
			cleanup: () => rm(snapshotDirectory, { recursive: true, force: true }),
		};
	} catch (error) {
		await rm(snapshotDirectory, { recursive: true, force: true });
		throw error;
	}
}

export async function captureChangedPaths(cwd: string): Promise<string[]> {
	const { stdout } = await execFileAsync(
		"git",
		["status", "--porcelain=v1", "-z", "--untracked-files=all"],
		{ cwd, encoding: "utf8" },
	);
	return parseChangedPaths(stdout);
}

function isMissingPath(error: unknown): boolean {
	return error instanceof Error && "code" in error && error.code === "ENOENT";
}

export function parseChangedPaths(status: string): string[] {
	const records = status.split("\0");
	const paths = new Set<string>();

	for (let index = 0; index < records.length; index += 1) {
		const record = records[index];
		if (!record) continue;
		if (record.length < 4 || record[2] !== " ") throw new Error(`Invalid git status record: ${record}`);

		paths.add(record.slice(3));
		if (record.slice(0, 2).match(/[RC]/)) {
			const originalPath = records[++index];
			if (!originalPath) throw new Error(`Missing original path for git status record: ${record}`);
			paths.add(originalPath);
		}
	}

	return [...paths].sort();
}
