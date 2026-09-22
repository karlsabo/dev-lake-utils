import {existsSync} from "node:fs";
import {homedir} from "node:os";
import {join} from "node:path";
import {fileURLToPath} from "node:url";
import type {ExtensionAPI} from "@earendil-works/pi-coding-agent";
import {CancellationError, cancellationError} from "./cancellation.ts";
import {createHandoffLedger} from "./handoff.ts";
import {startWorkflowProgress} from "./progress.ts";
import {createReviewEvidence} from "./review-evidence.ts";
import {isWorkflowSubagent, runPiSubagent} from "./subagent.ts";
import {type WorkflowRunAttempt, WorkflowRunGuard} from "./run-guard.ts";
import {
  combineWorkflowAndTeardownFailures,
  runImplementationWorkflow,
  WorkflowCancelled,
  WorkflowFailure,
  type WorkflowResult,
  type WorkflowState,
} from "./workflow.ts";
import {captureWorktreeBaseline} from "./worktree.ts";

const GUIDANCE_PATH = fileURLToPath(new URL("../notes.md", import.meta.url));
const PR_REVIEW_SKILL_PATH = join(homedir(), ".pi", "agent", "skills", "eh-pr-review", "SKILL.md");

export default function (pi: ExtensionAPI) {
	if (isWorkflowSubagent()) return;

	const runGuard = new WorkflowRunGuard();
	pi.registerCommand("wip-eh-implement-cancel", {
		description: "Cancel the active WIP EH implementation workflow",
		handler: async (_args, ctx) => {
			const request = runGuard.cancel();
			if (request === "not-active") {
				ctx.ui.notify("No implementation workflow is active", "warning");
			} else if (request === "already-requested") {
				ctx.ui.notify("Implementation workflow cancellation is already in progress", "warning");
			} else {
				ctx.ui.notify("Cancelling implementation workflow", "info");
			}
		},
	});
	pi.registerCommand("wip-eh-implement", {
		description: "Run the tool-capable WIP EH implementation workflow",
		handler: async (args, ctx) => {
			const task = args.trim();
			if (!task) {
				ctx.ui.notify("Usage: /wip-eh-implement <single implementation task>", "error");
				return;
			}
			if (!ctx.model) {
				ctx.ui.notify("No model selected", "error");
				return;
			}

			const model = `${ctx.model.provider}/${ctx.model.id}`;
			let attempt: WorkflowRunAttempt<WorkflowResult>;
			try {
				attempt = await runGuard.run(
					() => ctx.waitForIdle(),
					async (signal) => {
						const progress = startWorkflowProgress(ctx.ui);
						let baseline: Awaited<ReturnType<typeof captureWorktreeBaseline>> | undefined;
						return runWithTeardown(
							async () => {
								baseline = await captureWorktreeBaseline(ctx.cwd);
								return runImplementationWorkflow(
									task,
									async (_state, prompt) =>
										runPiSubagent(prompt, {
											cwd: ctx.cwd,
											model,
											thinkingLevel: ctx.thinkingLevel,
											signal,
										}),
									{
										guidancePath: GUIDANCE_PATH,
										initialChanges: baseline.changes,
										reviewEvidence: createReviewEvidence(ctx.cwd),
										handoffLedger: createHandoffLedger(ctx.cwd),
										prReviewSkillPath: existsSync(PR_REVIEW_SKILL_PATH)
											? PR_REVIEW_SKILL_PATH
											: undefined,
										onTransition: (state) => progress.transition(statusText(state)),
										signal,
									},
								);
							},
							[() => baseline?.cleanup() ?? Promise.resolve(), () => progress.stop()],
							signal,
						);
					},
				);
			} catch (error) {
				const errorMessage = error instanceof Error ? error.message : String(error);
				if (error instanceof CancellationError) {
					const reason = cancellationReason(error);
					pi.appendEntry("wip-eh-implement-result", {
						task,
						outcome: "cancelled",
						reason,
						...(error instanceof WorkflowCancelled ? error.audit : emptyAudit()),
						cancelledAt: new Date().toISOString(),
					});
					ctx.ui.notify(`Implementation workflow cancelled: ${errorMessage}`, "warning");
					return;
				}
				pi.appendEntry("wip-eh-implement-result", {
					task,
					outcome: "failed",
					reason: errorMessage,
					...(error instanceof WorkflowFailure ? error.audit : emptyAudit()),
					failedAt: new Date().toISOString(),
				});
				ctx.ui.notify(errorMessage, "error");
				return;
			}

			if (!attempt.started) {
				ctx.ui.notify("Implementation workflow is already active", "warning");
				return;
			}
			pi.appendEntry("wip-eh-implement-result", {
				task,
				...attempt.value,
				completedAt: new Date().toISOString(),
			});
			ctx.ui.notify(
				`Implementation completed across ${attempt.value.states.length} subagent state(s)`,
				"info",
			);
		},
	});
}

function emptyAudit() {
	return {states: [], initialTestFixes: 0, finalTestFixes: 0, reviewFixes: 0};
}

export function cancellationReason(error: CancellationError): string {
	return error instanceof WorkflowCancelled ? error.reason : error.message;
}

export async function runWithTeardown(
	run: () => Promise<WorkflowResult>,
	teardowns: ReadonlyArray<() => Promise<void> | void>,
	signal?: AbortSignal,
): Promise<WorkflowResult> {
	let result: WorkflowResult | undefined;
	let failure: unknown;
	const teardownFailures: unknown[] = [];
	try {
		result = await run();
	} catch (error) {
		failure = error;
	}
	for (const teardown of teardowns) {
		try {
			await teardown();
		} catch (error) {
			teardownFailures.push(error);
		}
	}
	if (failure === undefined && signal?.aborted) failure = cancellationError(signal);
	if (failure !== undefined || teardownFailures.length > 0) {
		throw combineWorkflowAndTeardownFailures(failure, teardownFailures, {
			states: result?.states ?? [],
			initialTestFixes: result?.initialTestFixes ?? 0,
			finalTestFixes: result?.finalTestFixes ?? 0,
			reviewFixes: result?.reviewFixes ?? 0,
		});
	}
	if (!result) throw new WorkflowFailure("Workflow produced no result", emptyAudit());
	return result;
}

function statusText(state: WorkflowState): string {
	const labels: Record<WorkflowState, string> = {
		"create-contract": "Implement: creating contract",
		"write-black-box-tests": "Implement: writing black-box tests",
		"review-black-box-tests": "Implement: reviewing black-box tests",
		"fix-black-box-test-findings": "Implement: fixing black-box tests",
		implement: "Implement: writing production code",
		"verify-tests": "Implement: verifying tests",
		"fix-failing-tests": "Implement: fixing failures",
		"write-white-box-tests": "Implement: writing white-box tests",
		"review-white-box-tests": "Implement: reviewing white-box tests",
		"fix-white-box-test-findings": "Implement: fixing white-box tests",
		"review-changes": "Implement: drafting final review",
		"skeptic-review-changes": "Implement: independently checking final review",
		"fix-review-findings": "Implement: fixing review findings",
	};
	return labels[state];
}
