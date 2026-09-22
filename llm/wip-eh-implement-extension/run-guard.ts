import {CancellationError, throwIfCancelled} from "./cancellation.ts";

export type WorkflowRunAttempt<T> =
	| { started: true; value: T }
	| { started: false };

export type CancellationRequest = "requested" | "already-requested" | "not-active";

export class WorkflowRunGuard {
	private activeController: AbortController | undefined;

	async run<T>(
		waitForIdle: () => Promise<void>,
		workflow: (signal: AbortSignal) => Promise<T>,
	): Promise<WorkflowRunAttempt<T>> {
		if (this.activeController) return { started: false };

		const controller = new AbortController();
		this.activeController = controller;
		try {
			await waitForIdle();
			throwIfCancelled(controller.signal);
			return { started: true, value: await workflow(controller.signal) };
		} finally {
			if (this.activeController === controller) this.activeController = undefined;
		}
	}

	cancel(): CancellationRequest {
		const controller = this.activeController;
		if (!controller) return "not-active";
		if (controller.signal.aborted) return "already-requested";
		controller.abort(new CancellationError());
		return "requested";
	}
}
