import type {HandoffLedger, WorkflowHandoff} from "./handoff.ts";
import type {ReviewAttempt, ReviewClaim, ReviewEvidence} from "./review-evidence.ts";
import type {InitialChange} from "./worktree.ts";

export type WorkflowState =
	| "create-contract"
	| "write-black-box-tests"
	| "review-black-box-tests"
	| "fix-black-box-test-findings"
	| "implement"
	| "verify-tests"
	| "fix-failing-tests"
	| "write-white-box-tests"
	| "review-white-box-tests"
	| "fix-white-box-test-findings"
	| "review-changes"
	| "skeptic-review-changes"
	| "fix-review-findings";

export interface StateResult {
	state: WorkflowState;
	summary: string;
	attempt?: number;
	artifactPath?: string;
	artifactContent?: string;
	fingerprint?: string;
	findings?: string[];
	handoff?: WorkflowHandoff;
}

export interface WorkflowResult {
	outcome: "completed";
	states: StateResult[];
	initialTestFixes: number;
	finalTestFixes: number;
	reviewFixes: number;
}

export interface WorkflowOptions {
	guidancePath: string;
	initialChanges: readonly InitialChange[];
	reviewEvidence: ReviewEvidence;
	handoffLedger?: HandoffLedger;
	prReviewSkillPath?: string;
	maxInitialTestFixes?: number;
	maxFinalTestFixes?: number;
	maxReviewFixes?: number;
	onTransition?: (state: WorkflowState) => void;
}

export interface WorkflowAudit {
	states: readonly StateResult[];
	initialTestFixes: number;
	finalTestFixes: number;
	reviewFixes: number;
}

export class WorkflowFailure extends Error {
	readonly audit: WorkflowAudit;

	constructor(message: string, audit: WorkflowAudit) {
		super(message);
		this.name = "WorkflowFailure";
		this.audit = audit;
	}
}

export function combineWorkflowAndTeardownFailures(
	failure: unknown,
	teardownFailures: readonly unknown[],
	audit: WorkflowAudit,
): WorkflowFailure {
	if (teardownFailures.length === 0 && failure instanceof WorkflowFailure) return failure;
	const primaryAudit = failure instanceof WorkflowFailure ? failure.audit : audit;
	const primaryMessage = failure === undefined ? undefined : errorMessage(failure);
	const teardownMessage = teardownFailures.length === 0
		? undefined
		: `Teardown failure(s): ${teardownFailures.map(errorMessage).join("; ")}`;
	return new WorkflowFailure(
		[primaryMessage, teardownMessage].filter((message): message is string => message !== undefined).join("\n"),
		primaryAudit,
	);
}

export type StateAgent = (state: WorkflowState, prompt: string) => Promise<string>;

interface Completion {
	outcome: "completed" | "blocked";
	workPerformed: boolean;
	summary: string;
	handoff?: WorkflowHandoff;
}

interface Review {
	findings: string[];
	summary: string;
	handoff?: WorkflowHandoff;
}

interface Verification {
	passed: boolean;
	summary: string;
	handoff?: WorkflowHandoff;
}

const DEFAULT_MAX_INITIAL_TEST_FIXES = 2;
const DEFAULT_MAX_FINAL_TEST_FIXES = 2;
const DEFAULT_MAX_REVIEW_FIXES = 2;

export async function runImplementationWorkflow(
	task: string,
	runAgent: StateAgent,
	options: WorkflowOptions,
): Promise<WorkflowResult> {
	if (!task.trim()) throw new Error("Task must not be empty");
	assertNonNegativeInteger(options.maxInitialTestFixes ?? DEFAULT_MAX_INITIAL_TEST_FIXES, "maxInitialTestFixes");
	assertNonNegativeInteger(options.maxFinalTestFixes ?? DEFAULT_MAX_FINAL_TEST_FIXES, "maxFinalTestFixes");
	assertNonNegativeInteger(options.maxReviewFixes ?? DEFAULT_MAX_REVIEW_FIXES, "maxReviewFixes");

	const workflow = new WorkflowExecution(task.trim(), runAgent, options);
	let result: WorkflowResult | undefined;
	let failure: unknown;
	const teardownFailures: unknown[] = [];
	try {
		result = await workflow.run();
	} catch (error) {
		failure = error;
	}
	try {
		await options.reviewEvidence.cleanup();
	} catch (error) {
		teardownFailures.push(error);
	}
	if (failure !== undefined || teardownFailures.length > 0) {
		throw combineWorkflowAndTeardownFailures(failure, teardownFailures, workflow.audit());
	}
	if (!result) throw new WorkflowFailure("Workflow produced no result", workflow.audit());
	return result;
}

class WorkflowExecution {
	private readonly states: StateResult[] = [];
	private readonly task: string;
	private readonly runAgent: StateAgent;
	private readonly options: WorkflowOptions;
	private initialTestFixes = 0;
	private finalTestFixes = 0;
	private reviewFixes = 0;
	private reviewAttempts = 0;

	constructor(task: string, runAgent: StateAgent, options: WorkflowOptions) {
		this.task = task;
		this.runAgent = runAgent;
		this.options = options;
	}

	audit(): WorkflowAudit {
		return {
			states: this.states,
			initialTestFixes: this.initialTestFixes,
			finalTestFixes: this.finalTestFixes,
			reviewFixes: this.reviewFixes,
		};
	}

	async run(): Promise<WorkflowResult> {
		await this.complete("create-contract", createContractPrompt(this.context()));
		const blackBoxTests = await this.complete("write-black-box-tests", writeBlackBoxTestsPrompt(this.context()));
		if (blackBoxTests.workPerformed) {
			const review = await this.review("review-black-box-tests", reviewBlackBoxTestsPrompt(this.context()));
			if (review.findings.length > 0) {
				await this.complete("fix-black-box-test-findings", fixTestFindingsPrompt(this.context(), "black-box", review.findings));
			}
		}
		await this.complete("implement", implementPrompt(this.context()));
		await this.initialValidation();
		const whiteBoxTests = await this.complete("write-white-box-tests", writeWhiteBoxTestsPrompt(this.context()));
		if (whiteBoxTests.workPerformed) {
			const review = await this.review("review-white-box-tests", reviewWhiteBoxTestsPrompt(this.context()));
			if (review.findings.length > 0) {
				await this.complete("fix-white-box-test-findings", fixTestFindingsPrompt(this.context(), "white-box", review.findings));
			}
		}
		await this.converge();
		return {
			outcome: "completed",
			states: this.states,
			initialTestFixes: this.initialTestFixes,
			finalTestFixes: this.finalTestFixes,
			reviewFixes: this.reviewFixes,
		};
	}

	private context(): PromptContext {
		return {task: this.task, guidancePath: this.options.guidancePath, initialChanges: this.options.initialChanges, prReviewSkillPath: this.options.prReviewSkillPath};
	}

	private async initialValidation(): Promise<void> {
		const maximum = this.options.maxInitialTestFixes ?? DEFAULT_MAX_INITIAL_TEST_FIXES;
		let verification = await this.verify();
		while (!verification.passed) {
			if (this.initialTestFixes >= maximum) throw exhausted("Initial validation", maximum, verification.summary);
			await this.complete("fix-failing-tests", fixFailingTestsPrompt(this.context(), verification.summary));
			this.initialTestFixes += 1;
			verification = await this.verify();
		}
	}

	private async converge(): Promise<void> {
		const maxReviewFixes = this.options.maxReviewFixes ?? DEFAULT_MAX_REVIEW_FIXES;
		const maxFinalTestFixes = this.options.maxFinalTestFixes ?? DEFAULT_MAX_FINAL_TEST_FIXES;
		while (true) {
			const {attempt, findings} = await this.finalReview();
			if (findings.length > 0) {
				if (this.reviewFixes >= maxReviewFixes) throw exhausted("Review", maxReviewFixes, findings.join("; "));
				await this.complete("fix-review-findings", fixReviewFindingsPrompt(this.context(), findings));
				this.reviewFixes += 1;
				continue;
			}

			const verification = await this.verify();
			const fingerprint = await this.options.reviewEvidence.fingerprint();
			if (fingerprint !== attempt.fingerprint) throw new Error("Repository changed during final validation");
			if (verification.passed) return;
			if (this.finalTestFixes >= maxFinalTestFixes) throw exhausted("Final validation", maxFinalTestFixes, verification.summary);
			await this.complete("fix-failing-tests", fixFailingTestsPrompt(this.context(), verification.summary));
			this.finalTestFixes += 1;
		}
	}

	private async finalReview(): Promise<{attempt: ReviewAttempt; findings: string[]}> {
		this.reviewAttempts += 1;
		const attempt = await this.options.reviewEvidence.begin(this.reviewAttempts);
		const draft = await this.reviewClaim("review-changes", draftReviewPrompt(this.context(), attempt));
		const draftArtifact = await this.options.reviewEvidence.validate(attempt, draft);
		this.states.push(reviewState("review-changes", draft, attempt, draftArtifact));
		const skeptic = await this.reviewClaim("skeptic-review-changes", skepticReviewPrompt(this.context(), attempt));
		const skepticArtifact = await this.options.reviewEvidence.validate(attempt, skeptic);
		const findings = this.options.reviewEvidence.findings(skepticArtifact);
		this.states.push({...reviewState("skeptic-review-changes", skeptic, attempt, skepticArtifact), findings});
		return {attempt, findings};
	}

	private async reviewClaim(state: WorkflowState, prompt: string): Promise<ReviewClaim> {
		const response = await this.call(state, prompt);
		try {
			return parseReviewClaim(response);
		} catch {
			this.states.push({state, summary: "Invalid state response"});
			const corrected = await this.call(state, correctionPrompt(state, prompt, response));
			try {
				return parseReviewClaim(corrected);
			} catch (error) {
				this.states.push({state, summary: "Invalid state response"});
				const reason = error instanceof Error ? error.message : String(error);
				throw new Error(`${state} returned an invalid response after correction: ${reason}; response: ${truncate(corrected)}`);
			}
		}
	}

	private async complete(state: WorkflowState, prompt: string): Promise<Completion> {
		const completion = await this.parse(state, prompt, parseCompletion);
		if (completion.outcome === "blocked") throw new Error(`${state} blocked: ${completion.summary}`);
		return completion;
	}

	private async review(state: WorkflowState, prompt: string): Promise<Review> {
		return this.parse(state, prompt, parseReview);
	}

	private async verify(): Promise<Verification> {
		return this.parse("verify-tests", verifyTestsPrompt(this.context()), parseVerification);
	}

	private async parse<T extends { summary: string; handoff?: WorkflowHandoff }>(
		state: WorkflowState,
		prompt: string,
		parser: (response: string) => T,
	): Promise<T> {
		const response = await this.call(state, prompt);
		try {
			return await this.recordParsed(state, parser(response));
		} catch {
			this.states.push({state, summary: "Invalid state response"});
			const corrected = await this.call(state, correctionPrompt(state, prompt, response));
			try {
				return await this.recordParsed(state, parser(corrected));
			} catch (error) {
				this.states.push({state, summary: "Invalid state response"});
				const reason = error instanceof Error ? error.message : String(error);
				throw new Error(
					`${state} returned an invalid response after correction: ${reason}; response: ${truncate(corrected)}`,
				);
			}
		}
	}

	private async recordParsed<T extends {summary: string; handoff?: WorkflowHandoff}>(
		state: WorkflowState,
		parsed: T,
	): Promise<T> {
		this.states.push({state, summary: parsed.summary, handoff: parsed.handoff});
		if (parsed.handoff) await this.options.handoffLedger?.record(state, parsed.handoff);
		return parsed;
	}

	private async call(state: WorkflowState, prompt: string): Promise<string> {
		this.options.onTransition?.(state);
		const retainedContext = await this.options.handoffLedger?.contextFor(state);
		return this.runAgent(state, retainedContext ? `${prompt}\n\n${retainedContext}` : prompt);
	}
}

function reviewState(
	state: WorkflowState,
	claim: ReviewClaim,
	attempt: ReviewAttempt,
	artifactContent: string,
): StateResult {
	return {
		state,
		summary: claim.summary,
		attempt: Number(attempt.attemptId.split("-").at(-1)),
		artifactPath: attempt.artifactPath,
		artifactContent,
		fingerprint: attempt.fingerprint,
	};
}

function exhausted(label: string, repairs: number, detail: string): Error {
	return new Error(`${label} budget exhausted after ${repairs} repair(s): ${detail}`);
}

interface PromptContext {
	task: string;
	guidancePath: string;
	initialChanges: readonly InitialChange[];
	prReviewSkillPath?: string;
}

function basePrompt(context: PromptContext, responsibility: string): string {
	return `You are one tool-capable subagent in a code-owned implementation workflow.

Task:
${context.task}

Before acting, read the repository's applicable AGENTS.md files and ${context.guidancePath}. Work directly in the current repository.

For a normal implementation request, stay within one acceptance-test slice. If a normal request contains multiple acceptance tests or unrelated behavior, do not edit files and report a blocked outcome.

A request to address a planned-comments or review-comments artifact is a review-remediation batch, not a normal implementation request. It may contain multiple independent comments from the same review. Do not block it merely because it has multiple comments or acceptance-test slices. Evaluate each comment independently, implement every reasonable and supported fix, and skip unsupported comments with the reason recorded in your summary.

Your responsibility:
${responsibility}

Do not delegate this state unless its responsibility explicitly requires a skeptic subagent pass. Finish the repository work for this state, then return only the requested JSON object in your final response, with no prose before or after it.`;
}

function completionContract(): string {
	return `${completionExample()}\nSet workPerformed=false when no repository files were changed because this state needed no work. Use outcome "blocked" with workPerformed=false only when the state cannot proceed safely.${handoffInstructions()}`;
}

function reviewContract(): string {
	return `${JSON.stringify({findings: ["specific actionable finding"], summary: "concise review result", handoff: handoffExample()})}\nUse an empty findings array when the reviewed work is clean.${handoffInstructions()}`;
}

function completionExample(): string {
	return JSON.stringify({
		outcome: "completed",
		workPerformed: true,
		summary: "concise description of work performed or why none was needed",
		handoff: handoffExample(),
	});
}

function handoffExample(): WorkflowHandoff {
	return {
		facts: [{claim: "concise mechanical fact", evidence: [{path: "relative/file.ts", line: 1}]}],
		relevantPaths: ["relative/file.ts"],
		commandsRun: ["exact command and concise result"],
	};
}

function handoffInstructions(): string {
	return " The optional handoff is for reusable mechanical evidence only. Omit it when there is nothing useful. Do not include recommendations, conclusions, or reasoning. Every fact requires repository-relative file evidence; keep all fields concise.";
}

function createContractPrompt(context: PromptContext): string {
	return `${basePrompt(context, "Inspect the existing seams and flesh out the smallest classes, interfaces, or functions needed to express the new contract. Edit production files only as needed to establish those contracts; do not implement the behavior yet. If the existing contracts already express the required behavior, make no speculative contract changes and report completion so the test-writing state can proceed.")}\n\nFinal response schema:\n${completionContract()}`;
}

function writeBlackBoxTestsPrompt(context: PromptContext): string {
	return `${basePrompt(context, "Inspect the contracts now present in the worktree and write black-box unit tests for their externally observable behavior. Keep normal implementation requests to one acceptance-test slice; for a review-remediation batch, cover each supported comment independently. Do not implement production behavior beyond minimal compile-time scaffolding. New tests are expected to fail until the implementation state; do not block merely because production behavior does not satisfy them yet.")}\n\nFinal response schema:\n${completionContract()}`;
}

function reviewBlackBoxTestsPrompt(context: PromptContext): string {
	return `${basePrompt(context, "Review the new black-box tests against the task and contracts. Do not edit files. Flag tests that are missing observable behavior, pass for the wrong reason, over-couple to implementation, or expand beyond the slice.")}\n\nFinal response schema:\n${reviewContract()}`;
}

function fixTestFindingsPrompt(context: PromptContext, kind: string, findings: string[]): string {
	return `${basePrompt(context, `Apply all review findings to the ${kind} tests. If the findings list is empty, inspect the tests, make no speculative changes, and report completion.\n\nFindings:\n${formatFindings(findings)}`)}\n\nFinal response schema:\n${completionContract()}`;
}

function implementPrompt(context: PromptContext): string {
	return `${basePrompt(context, "Write the smallest production implementation that satisfies the black-box tests. Keep functions focused, validate contracts early, remove actual knowledge duplication, and avoid comments that narrate the code.")}\n\nFinal response schema:\n${completionContract()}`;
}

function verifyTestsPrompt(context: PromptContext): string {
	const contract = `${JSON.stringify({passed: true, summary: "commands run and result", handoff: handoffExample()})}${handoffInstructions()}`;
	return `${basePrompt(context, "Run the narrowest relevant tests, then every validation command required by the repository's AGENTS.md. Do not edit files in this state. Report passed=false if any required command fails, with enough failure detail for the next state.")}\n\nFinal response schema:\n${contract}`;
}

function fixFailingTestsPrompt(context: PromptContext, failure: string): string {
	return `${basePrompt(context, `Diagnose the failing validation, then fix the underlying production code or tests without weakening coverage or static analysis.\n\nPrevious verification failure:\n${failure}`)}\n\nFinal response schema:\n${completionContract()}`;
}

function writeWhiteBoxTestsPrompt(context: PromptContext): string {
	return `${basePrompt(context, "Inspect the implementation and add focused white-box unit tests only for important internal branches, invariants, or failure behavior not already covered by the black-box acceptance test. Avoid duplicating existing assertions.")}\n\nFinal response schema:\n${completionContract()}`;
}

function reviewWhiteBoxTestsPrompt(context: PromptContext): string {
	return `${basePrompt(context, "Review the new white-box tests for meaningful branch and invariant coverage, implementation over-coupling, duplication, and false-positive assertions. Do not edit files.")}\n\nFinal response schema:\n${reviewContract()}`;
}

function draftReviewPrompt(context: PromptContext, attempt: ReviewAttempt): string {
	const skillInstruction = context.prReviewSkillPath
		? `Read ${context.prReviewSkillPath} and its references. Follow its review lenses and planned-comments format, but do not delegate the skeptic pass, post to GitHub, or wait for feedback.`
		: "Apply the eh-pr-review lenses, calibration, and planned-comments format.";
	const responsibility = `${skillInstruction}
Review the complete current uncommitted scope listed below, reading every changed file in full. Initial dirtiness is provenance only: do not exclude those paths or any part of their current content. Trace references and imports to explicitly assess relevant unchanged callers, consumers, tests, configuration, and documentation, including documented commands that may have become stale.
Write the review to exactly ${attempt.artifactPath}. It must contain the exact headings "## Overall PR Comment" and "## Inline Comments". Put every actionable finding in a complete structured planned-comment entry under Inline Comments; never leave a finding only in the overall comment or in prose outside those entries. If and only if there are no actionable findings, leave Inline Comments empty and set Overall PR Comment to exactly "No actionable findings." Do not edit repository files and do not run a skeptic yourself.

Host-captured current scope:
${formatScope(attempt.scope)}

Starting-worktree provenance (never an exclusion):
${formatInitialChanges(context.initialChanges)}`;
	return `${basePrompt(context, responsibility)}\n\nFinal response schema:\n${reviewClaimContract(attempt)}`;
}

function skepticReviewPrompt(context: PromptContext, attempt: ReviewAttempt): string {
	const skillInstruction = context.prReviewSkillPath ? `Read ${context.prReviewSkillPath} and its references.` : "Apply the eh-pr-review calibration.";
	return `${basePrompt(context, `${skillInstruction} Independently inspect the complete current uncommitted scope and relevant unchanged callers, consumers, tests, configuration, and documentation. Then skeptically revise ${attempt.artifactPath}: remove unsupported comments, rewrite weak ones, and add supported findings missed by the draft. Preserve the exact required headings. Put every surviving actionable finding in a complete structured planned-comment entry under Inline Comments; never leave a finding only in the overall comment or in prose outside those entries. If and only if there are no actionable findings, leave Inline Comments empty and set Overall PR Comment to exactly "No actionable findings." Initial dirtiness is provenance, not an exclusion. Do not edit repository files or delegate another review.\n\nHost-captured current scope:\n${formatScope(attempt.scope)}`)}\n\nFinal response schema:\n${reviewClaimContract(attempt)}`;
}

function reviewClaimContract(attempt: ReviewAttempt): string {
	return JSON.stringify({attemptId: attempt.attemptId, artifactPath: attempt.artifactPath, summary: "concise review result"});
}

function formatScope(scope: readonly string[]): string {
	return scope.length === 0 ? "No changed paths." : scope.map((path) => `- ${JSON.stringify(path)}`).join("\n");
}

function formatInitialChanges(changes: readonly InitialChange[]): string {
	if (changes.length === 0) return "None; the worktree was clean when the workflow started.";
	return changes
		.map(({ path, snapshotPath }) =>
			snapshotPath
				? `- path ${JSON.stringify(path)}: snapshot ${JSON.stringify(snapshotPath)}`
				: `- path ${JSON.stringify(path)}: absent at workflow start`,
		)
		.join("\n");
}

function fixReviewFindingsPrompt(context: PromptContext, findings: string[]): string {
	return `${basePrompt(context, `Fix every supported review finding, including appropriate tests. Do not suppress or weaken validation.\n\nReview findings:\n${formatFindings(findings)}`)}\n\nFinal response schema:\n${completionContract()}`;
}

function formatFindings(findings: string[]): string {
	return findings.length === 0 ? "None." : findings.map((finding, index) => `${index + 1}. ${finding}`).join("\n");
}

function parseCompletion(response: string): Completion {
	return parseJson(response, (parsed) => {
		const value = requireRecord(parsed, "completion");
		if (value.outcome !== "completed" && value.outcome !== "blocked") {
			throw new Error('completion.outcome must be "completed" or "blocked"');
		}
		if (typeof value.workPerformed !== "boolean") {
			throw new Error("completion.workPerformed must be a boolean");
		}
		return {
			outcome: value.outcome,
			workPerformed: value.workPerformed,
			summary: requireString(value.summary, "completion.summary"),
			handoff: parseHandoff(value.handoff),
		};
	});
}

function parseReview(response: string): Review {
	return parseJson(response, (parsed) => {
		const value = requireRecord(parsed, "review");
		return {
			findings: requireStringArray(value.findings, "review.findings"),
			summary: requireString(value.summary, "review.summary"),
			handoff: parseHandoff(value.handoff),
		};
	});
}

function parseReviewClaim(response: string): ReviewClaim {
	return parseJson(response, (parsed) => {
		const value = requireRecord(parsed, "review evidence");
		return {
			attemptId: requireString(value.attemptId, "review evidence.attemptId"),
			artifactPath: requireString(value.artifactPath, "review evidence.artifactPath"),
			summary: requireString(value.summary, "review evidence.summary"),
		};
	});
}

function parseVerification(response: string): Verification {
	return parseJson(response, (parsed) => {
		const value = requireRecord(parsed, "verification");
		if (typeof value.passed !== "boolean") throw new Error("verification.passed must be a boolean");
		return {
			passed: value.passed,
			summary: requireString(value.summary, "verification.summary"),
			handoff: parseHandoff(value.handoff),
		};
	});
}

function correctionPrompt(state: WorkflowState, originalPrompt: string, response: string): string {
	return `Your previous final response for the ${state} state was not the required JSON object, so it could not be processed.

Previous response:
${response}

Check the repository state before responding. If the work for this state is already complete, do not redo it. If it is incomplete or missing, finish it. Then return only the required JSON object in your final response, summarizing the work performed.

Original instructions:
${originalPrompt}`;
}

function truncate(text: string, limit = 200): string {
	return text.length <= limit ? text : `${text.slice(0, limit)}…`;
}

function parseJson<T>(response: string, parser: (value: unknown) => T): T {
	const trimmed = response.trim();
	const fenced = trimmed.match(/^```(?:json)?\s*([\s\S]*?)\s*```$/i);
	const candidate = fenced?.[1] ?? trimmed;
	let parsedCandidate: unknown;
	let parsedExactly: boolean;
	try {
		parsedCandidate = JSON.parse(candidate);
		parsedExactly = true;
	} catch {
		parsedExactly = false;
	}
	if (parsedExactly) return parser(parsedCandidate);

	let lastError: unknown = new Error("no JSON object found in response");
	for (const object of extractJsonObjects(candidate)) {
		try {
			return parser(JSON.parse(object));
		} catch (error) {
			lastError = error;
		}
	}
	throw lastError;
}

function extractJsonObjects(text: string): string[] {
	const objects: string[] = [];
	let start = text.indexOf("{");
	while (start !== -1) {
		const object = extractJsonObject(text, start);
		if (object === undefined) {
			start = text.indexOf("{", start + 1);
			continue;
		}
		objects.push(object);
		start = text.indexOf("{", start + object.length);
	}
	return objects;
}

function extractJsonObject(text: string, start: number): string | undefined {
	let depth = 0;
	let inString = false;
	let escaped = false;
	for (let index = start; index < text.length; index += 1) {
		const char = text[index];
		if (inString) {
			if (escaped) escaped = false;
			else if (char === "\\") escaped = true;
			else if (char === '"') inString = false;
			continue;
		}
		if (char === '"') inString = true;
		else if (char === "{") depth += 1;
		else if (char === "}") {
			depth -= 1;
			if (depth === 0) return text.slice(start, index + 1);
		}
	}
	return undefined;
}

function parseHandoff(value: unknown): WorkflowHandoff | undefined {
	if (value === undefined) return undefined;
	const handoff = requireRecord(value, "handoff");
	const facts = requireArray(handoff.facts, "handoff.facts").map((item, index) => {
		const fact = requireRecord(item, `handoff.facts[${index}]`);
		return {
			claim: requireBoundedString(fact.claim, `handoff.facts[${index}].claim`, 240),
			evidence: requireArray(fact.evidence, `handoff.facts[${index}].evidence`, 4).map((entry, evidenceIndex) => {
				const citation = requireRecord(entry, `handoff.facts[${index}].evidence[${evidenceIndex}]`);
				const line = citation.line;
				if (line !== undefined && (!Number.isInteger(line) || (line as number) < 1)) {
					throw new Error(`handoff.facts[${index}].evidence[${evidenceIndex}].line must be a positive integer`);
				}
				return {
					path: requireBoundedString(citation.path, `handoff.facts[${index}].evidence[${evidenceIndex}].path`, 200),
					...(line === undefined ? {} : {line: line as number}),
				};
			}),
		};
	});
	return {
		facts,
		relevantPaths: requireArray(handoff.relevantPaths, "handoff.relevantPaths", 20)
			.map((path, index) => requireBoundedString(path, `handoff.relevantPaths[${index}]`, 200)),
		commandsRun: requireArray(handoff.commandsRun, "handoff.commandsRun", 8)
			.map((command, index) => requireBoundedString(command, `handoff.commandsRun[${index}]`, 300)),
	};
}

function requireArray(value: unknown, field: string, maximum = 12): unknown[] {
	if (!Array.isArray(value) || value.length > maximum) {
		throw new Error(`${field} must be an array with at most ${maximum} entries`);
	}
	return value;
}

function requireBoundedString(value: unknown, field: string, maximum: number): string {
	const string = requireString(value, field);
	if (string.length > maximum) throw new Error(`${field} must be at most ${maximum} characters`);
	if (/[\r\n\0]/u.test(string)) throw new Error(`${field} must be a single line`);
	return string;
}

function requireRecord(value: unknown, field: string): Record<string, unknown> {
	if (typeof value !== "object" || value === null || Array.isArray(value)) throw new Error(`${field} must be an object`);
	return value as Record<string, unknown>;
}

function requireString(value: unknown, field: string): string {
	if (typeof value !== "string" || !value.trim()) throw new Error(`${field} must be a non-empty string`);
	return value.trim();
}

function requireStringArray(value: unknown, field: string): string[] {
	if (!Array.isArray(value) || value.some((item) => typeof item !== "string" || !item.trim())) {
		throw new Error(`${field} must be an array of non-empty strings`);
	}
	return value.map((item) => item.trim());
}

function errorMessage(error: unknown): string {
	return error instanceof Error ? error.message : String(error);
}

function assertNonNegativeInteger(value: number, field: string): void {
	if (!Number.isInteger(value) || value < 0) throw new Error(`${field} must be a non-negative integer`);
}
