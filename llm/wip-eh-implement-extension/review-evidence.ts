import {execFile} from "node:child_process";
import {createHash, randomUUID} from "node:crypto";
import {chmod, lstat, mkdtemp, readFile, readlink, rm} from "node:fs/promises";
import {tmpdir} from "node:os";
import {join} from "node:path";
import {promisify} from "node:util";
import {captureChangedPaths} from "./worktree.ts";

const execFileAsync = promisify(execFile);
const CLEAN_REVIEW_MARKER = "No actionable findings.";

export interface ReviewAttempt {
	attemptId: string;
	artifactPath: string;
	fingerprint: string;
	scope: string[];
}

export interface ReviewClaim {
	attemptId: string;
	artifactPath: string;
	summary: string;
}

export interface ReviewEvidence {
	begin(attemptNumber: number): Promise<ReviewAttempt>;
	validate(attempt: ReviewAttempt, claim: ReviewClaim): Promise<string>;
	findings(document: string): string[];
	fingerprint(): Promise<string>;
	cleanup(): Promise<void>;
}

export function createReviewEvidence(cwd: string): ReviewEvidence {
	const runId = randomUUID();
	let artifactDirectory: Promise<string> | undefined;
	const directory = () => {
		artifactDirectory ??= createPrivateDirectory();
		return artifactDirectory;
	};

	return {
		async begin(attemptNumber) {
			const artifactPath = join(await directory(), `${attemptNumber}-planned-comments.md`);
			return {
				attemptId: `${runId}-${attemptNumber}`,
				artifactPath,
				fingerprint: await fingerprintWorktree(cwd),
				scope: await captureChangedPaths(cwd),
			};
		},
		async validate(attempt, claim) {
			if (claim.attemptId !== attempt.attemptId || claim.artifactPath !== attempt.artifactPath) {
				throw new Error(`Review evidence does not belong to attempt ${attempt.attemptId}`);
			}
			const document = await readFile(attempt.artifactPath, "utf8").catch(() => {
				throw new Error(`Review artifact was not created: ${attempt.artifactPath}`);
			});
			assertArtifactShape(document);
			const currentFingerprint = await fingerprintWorktree(cwd);
			if (currentFingerprint !== attempt.fingerprint) {
				throw new Error("Repository changed during final review");
			}
			return document;
		},
		findings: extractInlineFindings,
		fingerprint: () => fingerprintWorktree(cwd),
		async cleanup() {
			if (artifactDirectory) await rm(await artifactDirectory, {recursive: true, force: true});
		},
	};
}

export function assertArtifactShape(document: string): void {
	parseArtifact(document);
}

export function extractInlineFindings(document: string): string[] {
	return parseArtifact(document);
}

function parseArtifact(document: string): string[] {
	const headings = [...document.matchAll(/^##\s+(.+?)\s*$/gm)];
	if (
		headings.length !== 2 ||
		headings[0]?.[1] !== "Overall PR Comment" ||
		headings[1]?.[1] !== "Inline Comments"
	) {
		throw new Error("Review artifact must contain exactly one ordered Overall PR Comment and Inline Comments section");
	}

	const overallHeadingStart = headings[0]?.index ?? 0;
	assertAllowedPrefix(document.slice(0, overallHeadingStart));
	const overallStart = overallHeadingStart + (headings[0]?.[0].length ?? 0);
	const overallEnd = headings[1]?.index ?? document.length;
	const overall = removeTrailingSeparator(document.slice(overallStart, overallEnd));
	if (!overall) throw new Error("Overall PR Comment must contain a substantive non-empty comment");

	const inlineStart = overallEnd + (headings[1]?.[0].length ?? 0);
	const inline = removeTrailingSeparator(document.slice(inlineStart));
	if (!inline) {
		if (overall !== CLEAN_REVIEW_MARKER) {
			throw new Error(`A review without inline comments must use exactly "${CLEAN_REVIEW_MARKER}"`);
		}
		return [];
	}
	if (overall === CLEAN_REVIEW_MARKER) {
		throw new Error(`Overall PR Comment may use "${CLEAN_REVIEW_MARKER}" only when Inline Comments is empty`);
	}
	if (!inline.startsWith("### ")) {
		throw new Error("Inline Comments content must use planned-comment sections");
	}

	const comments = inline.split(/\n\n---[ \t]*\n\n(?=### )/u);
	return comments.map(parseInlineComment);
}

function assertAllowedPrefix(prefix: string): void {
	const lines = prefix.split(/\r?\n/u).map((line) => line.trim()).filter(Boolean);
	const hasOptionalTitle = lines[0]?.startsWith("# ") ?? false;
	if (hasOptionalTitle) lines.shift();
	if (lines[0] === "---") lines.shift();
	if (lines.length > 0) {
		throw new Error("Review artifact may contain only an optional H1 title and horizontal separator before its sections");
	}
}

function removeTrailingSeparator(section: string): string {
	return section.trim().replace(/(?:^|\r?\n)[ \t]*---[ \t]*$/u, "").trim();
}

function parseInlineComment(comment: string): string {
	const match = comment.match(
		/^### ([^\n]+\s+\([^)\n]+\))\n\n\*\*File:\*\*\s+`([^`\n]+)`\n\*\*Line:\*\*\s+(\d+(?:\s+[^\n]+)?)\n\n([\s\S]+)$/u,
	);
	if (!match) throw new Error("Inline comment must match the planned-comment structure");
	const [, heading, file, line, body] = match;
	if (!body?.trim() || /^###\s+/m.test(body) || /^##\s+/m.test(body)) {
		throw new Error("Inline comment must contain one structured comment body");
	}
	return `${heading}\n\n**File:** \`${file}\`\n**Line:** ${line}\n\n${body.trim()}`;
}

async function createPrivateDirectory(): Promise<string> {
	const path = await mkdtemp(join(tmpdir(), "wip-eh-implement-review-"));
	await chmod(path, 0o700);
	return path;
}

async function fingerprintWorktree(cwd: string): Promise<string> {
	const {stdout: status} = await execFileAsync(
		"git",
		["status", "--porcelain=v1", "-z", "--untracked-files=all"],
		{cwd, encoding: "utf8"},
	);
	const {stdout: stagedDiff} = await execFileAsync(
		"git",
		["diff", "--cached", "--binary", "--no-ext-diff"],
		{cwd, encoding: "utf8", maxBuffer: 50 * 1024 * 1024},
	);
	const hash = createHash("sha256").update(status).update("\0staged\0").update(stagedDiff);
	for (const path of await captureChangedPaths(cwd)) {
		hash.update("\0path\0").update(path);
		try {
			const stats = await lstat(join(cwd, path));
			if (stats.isSymbolicLink()) hash.update("\0link\0").update(await readlink(join(cwd, path)));
			else if (stats.isFile()) {
				hash.update(`\0file\0${stats.mode}\0`).update(await readFile(join(cwd, path)));
			}
			else if (stats.isDirectory()) throw new Error(`Changed submodules are not supported: ${path}`);
			else hash.update(`\0mode\0${stats.mode}`);
		} catch (error) {
			if (!(error instanceof Error && "code" in error && error.code === "ENOENT")) throw error;
			hash.update("\0missing\0");
		}
	}
	return hash.digest("hex");
}
