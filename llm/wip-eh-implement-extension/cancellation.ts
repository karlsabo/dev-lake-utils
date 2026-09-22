export const USER_CANCELLATION_REASON = "Cancelled by user";

export class CancellationError extends Error {
	constructor(message = USER_CANCELLATION_REASON) {
		super(message);
		this.name = "CancellationError";
	}
}

export function cancellationError(signal: AbortSignal): CancellationError {
	return signal.reason instanceof CancellationError
		? signal.reason
		: new CancellationError();
}

export function throwIfCancelled(signal?: AbortSignal): void {
	if (signal?.aborted) throw cancellationError(signal);
}
