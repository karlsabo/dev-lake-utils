import assert from "node:assert/strict";
import {execFileSync} from "node:child_process";
import {access, chmod, mkdtemp, stat, writeFile} from "node:fs/promises";
import {tmpdir} from "node:os";
import {dirname, join} from "node:path";
import test from "node:test";
import {assertArtifactShape, createReviewEvidence, extractInlineFindings} from "./review-evidence.ts";

const CLEAN_ARTIFACT = `# Review

---

## Overall PR Comment

No actionable findings.

---

## Inline Comments
`;

const FINDING_ARTIFACT = `# Review

---

## Overall PR Comment

Couple of things to look at:

---

## Inline Comments
`;

const FINDING = `### Bug: stale command (README.md)

**File:** \`README.md\`
**Line:** 10

The documented command still points at the old location.`;

test("validates exactly one ordered pair of review artifact sections", () => {
	assert.doesNotThrow(() => assertArtifactShape(CLEAN_ARTIFACT));
	for (const malformed of [
		"## Inline Comments\n",
		"## Inline Comments\n\n## Overall PR Comment\n",
		`${CLEAN_ARTIFACT}\n## Inline Comments\n`,
		`${CLEAN_ARTIFACT}\n## Overall PR Comment\n`,
	]) {
		assert.throws(() => assertArtifactShape(malformed), /exactly one ordered/);
	}
});

test("rejects a headings-only artifact without an overall review comment", () => {
	assert.throws(
		() => assertArtifactShape("## Overall PR Comment\n\n## Inline Comments\n"),
		/Overall PR Comment must contain a substantive non-empty comment/,
	);
});

test("rejects actionable text in the overall comment when inline comments are empty", () => {
	assert.throws(
		() => assertArtifactShape("## Overall PR Comment\n\nThe public command is broken and needs updating.\n\n## Inline Comments\n"),
		/must use exactly "No actionable findings\."/,
	);
});

test("rejects arbitrary prose before the planned-comments sections", () => {
	assert.throws(
		() => assertArtifactShape(`Unstructured review notes.\n\n${CLEAN_ARTIFACT}`),
		/only an optional H1 title and horizontal separator/,
	);
});

test("uses the clean marker only for artifacts without inline comments", () => {
	assert.throws(
		() => assertArtifactShape(`${CLEAN_ARTIFACT}\n${FINDING}\n`),
		/may use "No actionable findings\." only when Inline Comments is empty/,
	);
	assert.doesNotThrow(() => assertArtifactShape(`${CLEAN_ARTIFACT}\n---\n`));
});

test("rejects non-empty inline content that is not a complete planned comment", () => {
	for (const inline of [
		"This looks broken.",
		"### Bug: missing metadata (file.ts)\n\nThis has no location.",
		"### Bug without a basename\n\n**File:** `file.ts`\n**Line:** 1\n\nBroken.",
		"### Bug: out of order (file.ts)\n\n**Line:** 1\n**File:** `file.ts`\n\nBroken.",
		"### Bug: duplicate metadata (file.ts)\n\n**File:** `file.ts`\n**File:** `other.ts`\n**Line:** 1\n\nBroken.",
		"### Bug: missing body (file.ts)\n\n**File:** `file.ts`\n**Line:** 1",
		`${FINDING}\n\nUnstructured text\n\n### Bug: second (other.ts)\n\n**File:** \`other.ts\`\n**Line:** 2\n\nBroken.`,
	]) {
		assert.throws(() => assertArtifactShape(`${FINDING_ARTIFACT}\n${inline}\n`), /Inline [Cc]omment/);
	}
});

test("derives surviving findings from the skeptic-revised artifact", () => {
	const artifact = `${FINDING_ARTIFACT}
${FINDING}

---

### Testing gap: caller (workflow.test.ts)

**File:** \`workflow.test.ts\`
**Line:** 20 caller test

The unchanged caller path is not covered.
`;
	assert.deepEqual(extractInlineFindings(artifact), [
		"Bug: stale command (README.md)\n\n**File:** `README.md`\n**Line:** 10\n\nThe documented command still points at the old location.",
		"Testing gap: caller (workflow.test.ts)\n\n**File:** `workflow.test.ts`\n**Line:** 20 caller test\n\nThe unchanged caller path is not covered.",
	]);
});

test("stores artifacts in a private per-run directory and removes them on cleanup", async (context) => {
	const cwd = await createRepository(context);
	const evidence = createReviewEvidence(cwd);
	const attempt = await evidence.begin(1);
	assert.equal((await stat(dirname(attempt.artifactPath))).mode & 0o777, 0o700);
	await writeFile(attempt.artifactPath, CLEAN_ARTIFACT);
	const document = await evidence.validate(attempt, {
		attemptId: attempt.attemptId,
		artifactPath: attempt.artifactPath,
		summary: "clean",
	});
	assert.equal(document, CLEAN_ARTIFACT);
	await evidence.cleanup();
	await assert.rejects(access(attempt.artifactPath), /ENOENT/);
});

test("rejects a regular-file mode change during review", async (context) => {
	const cwd = await createDirtyScriptRepository(context);
	const evidence = createReviewEvidence(cwd);
	const attempt = await evidence.begin(1);
	await writeFile(attempt.artifactPath, CLEAN_ARTIFACT);
	await chmod(join(cwd, "script.sh"), 0o755);

	await assert.rejects(
		evidence.validate(attempt, {
			attemptId: attempt.attemptId,
			artifactPath: attempt.artifactPath,
			summary: "clean",
		}),
		/Repository changed during final review/,
	);
	await evidence.cleanup();
});

test("changes the final-validation fingerprint when a regular-file mode changes", async (context) => {
	const cwd = await createDirtyScriptRepository(context);
	const evidence = createReviewEvidence(cwd);
	const reviewedFingerprint = await evidence.fingerprint();
	await chmod(join(cwd, "script.sh"), 0o755);

	assert.notEqual(await evidence.fingerprint(), reviewedFingerprint);
	await evidence.cleanup();
});

test("rejects changed submodules instead of fingerprinting only the directory", async (context) => {
	const parent = await createRepository(context);
	const child = await createRepository(context);
	await writeFile(join(child, "tracked.txt"), "original\n");
	git(child, "add", "tracked.txt");
	git(child, "commit", "-m", "initial");
	git(parent, "-c", "protocol.file.allow=always", "submodule", "add", child, "module");
	git(parent, "commit", "-am", "add submodule");
	await writeFile(join(parent, "module", "tracked.txt"), "changed\n");

	const evidence = createReviewEvidence(parent);
	await assert.rejects(evidence.begin(1), /Changed submodules are not supported: module/);
	await evidence.cleanup();
});

async function createDirtyScriptRepository(context: {after(callback: () => Promise<void>): void}): Promise<string> {
	const cwd = await createRepository(context);
	await writeFile(join(cwd, "script.sh"), "#!/bin/sh\necho original\n", {mode: 0o644});
	git(cwd, "add", "script.sh");
	git(cwd, "commit", "-m", "add script");
	await writeFile(join(cwd, "script.sh"), "#!/bin/sh\necho changed\n");
	return cwd;
}

async function createRepository(context: {after(callback: () => Promise<void>): void}): Promise<string> {
	const cwd = await mkdtemp(join(tmpdir(), "review-evidence-test-"));
	context.after(async () => {
		const {rm} = await import("node:fs/promises");
		await rm(cwd, {recursive: true, force: true});
	});
	git(cwd, "init", "--quiet");
	git(cwd, "config", "user.email", "test@example.com");
	git(cwd, "config", "user.name", "Test User");
	return cwd;
}

function git(cwd: string, ...args: string[]): void {
	execFileSync("git", args, {cwd, stdio: "ignore"});
}
