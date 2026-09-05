export interface WorkflowProgressUI {
	setStatus(key: string, text: string | undefined): void;
	setWidget(
		key: string,
		content: string[] | undefined,
		options?: { placement?: "aboveEditor" | "belowEditor" },
	): void;
}

export interface WorkflowProgress {
	transition(label: string): void;
	stop(): void;
}

const STATUS_KEY = "wip-eh-implement";

export function startWorkflowProgress(ui: WorkflowProgressUI, now: () => number = Date.now): WorkflowProgress {
	const startedAt = now();
	let label = "Preparing worktree baseline";
	let stopped = false;

	const render = () => {
		if (stopped) return;
		ui.setStatus(STATUS_KEY, label);
		ui.setWidget(
			STATUS_KEY,
			[
				`WIP EH implement · ${formatElapsed(now() - startedAt)} elapsed`,
				label,
				"A separate Pi subagent is working on this state.",
			],
			{ placement: "belowEditor" },
		);
	};

	render();
	const timer = setInterval(render, 1_000);
	timer.unref();

	return {
		transition(nextLabel) {
			label = nextLabel;
			render();
		},
		stop() {
			if (stopped) return;
			stopped = true;
			clearInterval(timer);
			ui.setStatus(STATUS_KEY, undefined);
			ui.setWidget(STATUS_KEY, undefined);
		},
	};
}

export function formatElapsed(milliseconds: number): string {
	const totalSeconds = Math.max(0, Math.floor(milliseconds / 1_000));
	const hours = Math.floor(totalSeconds / 3_600);
	const minutes = Math.floor((totalSeconds % 3_600) / 60);
	const seconds = totalSeconds % 60;
	const minuteSeconds = `${String(minutes).padStart(2, "0")}:${String(seconds).padStart(2, "0")}`;
	return hours > 0 ? `${String(hours).padStart(2, "0")}:${minuteSeconds}` : minuteSeconds;
}
