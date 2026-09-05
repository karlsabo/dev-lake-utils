import {existsSync} from "node:fs";
import {homedir} from "node:os";
import {join} from "node:path";
import {fileURLToPath} from "node:url";
import type {ExtensionAPI} from "@earendil-works/pi-coding-agent";
import {startWorkflowProgress} from "./progress.ts";
import {isWorkflowSubagent, runPiSubagent} from "./subagent.ts";
import {WorkflowRunGuard} from "./run-guard.ts";
import {runImplementationWorkflow, type WorkflowState} from "./workflow.ts";
import {captureWorktreeBaseline} from "./worktree.ts";

const GUIDANCE_PATH = fileURLToPath(new URL("../notes.md", import.meta.url));
const PR_REVIEW_SKILL_PATH = join(homedir(), ".pi", "agent", "skills", "eh-pr-review", "SKILL.md");

export default function (pi: ExtensionAPI) {
	if (isWorkflowSubagent()) return;

	const runGuard = new WorkflowRunGuard();
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
			try {
				const attempt = await runGuard.run(
					() => ctx.waitForIdle(),
					async () => {
						const progress = startWorkflowProgress(ctx.ui);
						try {
							const baseline = await captureWorktreeBaseline(ctx.cwd);
							try {
								const result = await runImplementationWorkflow(
									task,
									async (_state, prompt) =>
										runPiSubagent(prompt, {
											cwd: ctx.cwd,
											model,
											thinkingLevel: ctx.thinkingLevel,
										}),
									{
										guidancePath: GUIDANCE_PATH,
										initialChanges: baseline.changes,
										prReviewSkillPath: existsSync(PR_REVIEW_SKILL_PATH)
											? PR_REVIEW_SKILL_PATH
											: undefined,
										onTransition: (state) => progress.transition(statusText(state)),
									},
								);

								pi.appendEntry("wip-eh-implement-result", {
									task,
									...result,
									completedAt: new Date().toISOString(),
								});
								ctx.ui.notify(
									`Implementation completed across ${result.states.length} subagent state(s)`,
									"info",
								);
							} finally {
								await baseline.cleanup();
							}
						} finally {
							progress.stop();
						}
					},
				);
				if (!attempt.started) ctx.ui.notify("Implementation workflow is already active", "warning");
			} catch (error) {
				ctx.ui.notify(error instanceof Error ? error.message : String(error), "error");
			}
		},
	});
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
		"review-changes": "Implement: reviewing all changes",
		"fix-review-findings": "Implement: fixing review findings",
	};
	return labels[state];
}
