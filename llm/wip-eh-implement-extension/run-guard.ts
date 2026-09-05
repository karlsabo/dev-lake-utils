export type WorkflowRunAttempt<T> =
	| { started: true; value: T }
	| { started: false };

export class WorkflowRunGuard {
	private active = false;

	async run<T>(waitForIdle: () => Promise<void>, workflow: () => Promise<T>): Promise<WorkflowRunAttempt<T>> {
		if (this.active) return { started: false };

		this.active = true;
		try {
			await waitForIdle();
			return { started: true, value: await workflow() };
		} finally {
			this.active = false;
		}
	}
}
