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
	| "fix-review-findings";

export interface StateResult {
	state: WorkflowState;
	summary: string;
}

export interface WorkflowResult {
	states: StateResult[];
	testFixes: number;
	reviewFixes: number;
}

export interface WorkflowOptions {
	guidancePath: string;
	initialChanges: readonly InitialChange[];
	prReviewSkillPath?: string;
	maxTestFixes?: number;
	maxReviewFixes?: number;
	onTransition?: (state: WorkflowState) => void;
}

export type StateAgent = (state: WorkflowState, prompt: string) => Promise<string>;

interface Completion {
	outcome: "completed" | "blocked";
	workPerformed: boolean;
	summary: string;
}

interface Review {
	findings: string[];
	summary: string;
}

interface Verification {
	passed: boolean;
	summary: string;
}

const DEFAULT_MAX_TEST_FIXES = 2;
const DEFAULT_MAX_REVIEW_FIXES = 2;

export async function runImplementationWorkflow(
	task: string,
	runAgent: StateAgent,
	options: WorkflowOptions,
): Promise<WorkflowResult> {
	if (!task.trim()) throw new Error("Task must not be empty");
	assertNonNegativeInteger(options.maxTestFixes ?? DEFAULT_MAX_TEST_FIXES, "maxTestFixes");
	assertNonNegativeInteger(options.maxReviewFixes ?? DEFAULT_MAX_REVIEW_FIXES, "maxReviewFixes");

	const workflow = new WorkflowExecution(task.trim(), runAgent, options);
	return workflow.run();
}

class WorkflowExecution {
	private readonly states: StateResult[] = [];
	private readonly task: string;
	private readonly runAgent: StateAgent;
	private readonly options: WorkflowOptions;
	private testFixes = 0;
	private reviewFixes = 0;

	constructor(task: string, runAgent: StateAgent, options: WorkflowOptions) {
		this.task = task;
		this.runAgent = runAgent;
		this.options = options;
	}

	async run(): Promise<WorkflowResult> {
		await this.complete("create-contract", createContractPrompt(this.context()));
		const blackBoxTests = await this.complete("write-black-box-tests", writeBlackBoxTestsPrompt(this.context()));
		if (blackBoxTests.workPerformed) {
			const review = await this.review("review-black-box-tests", reviewBlackBoxTestsPrompt(this.context()));
			if (review.findings.length > 0) {
				await this.complete(
					"fix-black-box-test-findings",
					fixTestFindingsPrompt(this.context(), "black-box", review.findings),
				);
			}
		}
		await this.complete("implement", implementPrompt(this.context()));
		await this.verifyUntilPassing();
		const whiteBoxTests = await this.complete("write-white-box-tests", writeWhiteBoxTestsPrompt(this.context()));
		if (whiteBoxTests.workPerformed) {
			const review = await this.review("review-white-box-tests", reviewWhiteBoxTestsPrompt(this.context()));
			if (review.findings.length > 0) {
				await this.complete(
					"fix-white-box-test-findings",
					fixTestFindingsPrompt(this.context(), "white-box", review.findings),
				);
			}
		}
		await this.reviewUntilClean();
		await this.verifyUntilPassing();

		return { states: this.states, testFixes: this.testFixes, reviewFixes: this.reviewFixes };
	}

	private context(): PromptContext {
		return {
			task: this.task,
			guidancePath: this.options.guidancePath,
			initialChanges: this.options.initialChanges,
			prReviewSkillPath: this.options.prReviewSkillPath,
		};
	}

	private async verifyUntilPassing(): Promise<void> {
		const maxFixes = this.options.maxTestFixes ?? DEFAULT_MAX_TEST_FIXES;
		let phaseFixes = 0;
		let verification = await this.verify();
		while (!verification.passed) {
			if (phaseFixes >= maxFixes) {
				throw new Error(`Tests still fail after ${maxFixes} fix attempt(s): ${verification.summary}`);
			}
			await this.complete("fix-failing-tests", fixFailingTestsPrompt(this.context(), verification.summary));
			phaseFixes += 1;
			this.testFixes += 1;
			verification = await this.verify();
		}
	}

	private async reviewUntilClean(): Promise<void> {
		const maxFixes = this.options.maxReviewFixes ?? DEFAULT_MAX_REVIEW_FIXES;
		let review = await this.review("review-changes", reviewChangesPrompt(this.context()));
		while (review.findings.length > 0) {
			if (this.reviewFixes >= maxFixes) {
				throw new Error(`Review still has findings after ${maxFixes} fix attempt(s): ${review.findings.join("; ")}`);
			}
			await this.complete("fix-review-findings", fixReviewFindingsPrompt(this.context(), review.findings));
			this.reviewFixes += 1;
			review = await this.review("review-changes", reviewChangesPrompt(this.context()));
		}
	}

	private async complete(state: WorkflowState, prompt: string): Promise<Completion> {
		const completion = parseCompletion(await this.call(state, prompt));
		if (completion.outcome === "blocked") throw new Error(`${state} blocked: ${completion.summary}`);
		return completion;
	}

	private async review(state: WorkflowState, prompt: string): Promise<Review> {
		return parseReview(await this.call(state, prompt));
	}

	private async verify(): Promise<Verification> {
		return parseVerification(await this.call("verify-tests", verifyTestsPrompt(this.context())));
	}

	private async call(state: WorkflowState, prompt: string): Promise<string> {
		this.options.onTransition?.(state);
		const response = await this.runAgent(state, prompt);
		const summary = responseSummary(response);
		this.states.push({ state, summary });
		return response;
	}
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

Do not delegate this state unless its responsibility explicitly requires a skeptic subagent pass. Finish the repository work for this state, then return only the requested JSON object in your final response.`;
}

function completionContract(): string {
	return '{"outcome":"completed","workPerformed":true,"summary":"concise description of work performed or why none was needed"}\nSet workPerformed=false when no repository files were changed because this state needed no work. Use outcome "blocked" with workPerformed=false only when the state cannot proceed safely.';
}

function reviewContract(): string {
	return '{"findings":["specific actionable finding"],"summary":"concise review result"}\nUse an empty findings array when the reviewed work is clean.';
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
	return `${basePrompt(context, "Run the narrowest relevant tests, then every validation command required by the repository's AGENTS.md. Do not edit files in this state. Report passed=false if any required command fails, with enough failure detail for the next state.")}\n\nFinal response schema:\n{"passed":true,"summary":"commands run and result"}`;
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

function reviewChangesPrompt(context: PromptContext): string {
	const skillInstruction = context.prReviewSkillPath
		? `Read ${context.prReviewSkillPath} and follow its uncommitted-change review process, including the planned-comments artifact and skeptic subagent pass. This is a non-interactive workflow state: do not post to GitHub or wait for user feedback; translate only the comments that survive the skeptic pass into the findings array.`
		: "Apply the eh-pr-review lenses and calibration, including a skeptic pass over proposed findings.";
	const initialChanges = formatInitialChanges(context.initialChanges);
	return `${basePrompt(context, `${skillInstruction} Build the current scope from git status --short --untracked-files=all. Review every newly changed file in full-file context. For each path that was already dirty when this workflow started, review only this workflow's delta from the retained starting version described below; do not report findings about pre-existing changes. A snapshot path is the file's exact starting content. A missing snapshot means the path did not exist at workflow start. Compare snapshots without modifying either copy (git diff --no-index is suitable even though differences return exit code 1). If a pre-existing path has no delta from its starting version, exclude it. Do not edit repository files. You may create or update only the planned-comments artifact at the path required by the review skill, and the skeptic pass may revise that artifact. Return only well-supported, actionable findings; do not manufacture comments.\n\nRetained starting versions for pre-existing paths:\n${initialChanges}`)}\n\nFinal response schema:\n${reviewContract()}`;
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
	const value = requireRecord(parseJson(response), "completion");
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
	};
}

function parseReview(response: string): Review {
	const value = requireRecord(parseJson(response), "review");
	return {
		findings: requireStringArray(value.findings, "review.findings"),
		summary: requireString(value.summary, "review.summary"),
	};
}

function parseVerification(response: string): Verification {
	const value = requireRecord(parseJson(response), "verification");
	if (typeof value.passed !== "boolean") throw new Error("verification.passed must be a boolean");
	return { passed: value.passed, summary: requireString(value.summary, "verification.summary") };
}

function parseJson(response: string): unknown {
	const trimmed = response.trim();
	const fenced = trimmed.match(/^```(?:json)?\s*([\s\S]*?)\s*```$/i);
	return JSON.parse(fenced?.[1] ?? trimmed);
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

function responseSummary(response: string): string {
	try {
		const value = requireRecord(parseJson(response), "response");
		return typeof value.summary === "string" ? value.summary.trim() : "State completed";
	} catch {
		return "Invalid state response";
	}
}

function assertNonNegativeInteger(value: number, field: string): void {
	if (!Number.isInteger(value) || value < 0) throw new Error(`${field} must be a non-negative integer`);
}
