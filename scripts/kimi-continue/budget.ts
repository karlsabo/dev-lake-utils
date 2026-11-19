/** Maximum automatic nudges since the last human prompt. */
export const MAX_NUDGES = 3;

export interface NudgeDecision {
  /** Whether to send the continue message now. */
  send: boolean;
  /** Nudge count to carry into the next stalled turn. */
  count: number;
  /** True when the cap blocks this nudge, so the user must type to continue. */
  exhausted: boolean;
}

/**
 * Budget for automatic continues. The count is the total since the last human
 * prompt, not consecutive stalls: a tool call in between does not reset it.
 */
export function nextNudge(count: number, stalled: boolean): NudgeDecision {
  if (!stalled) return { send: false, count, exhausted: false };
  if (count >= MAX_NUDGES) return { send: false, count, exhausted: true };
  return { send: true, count: count + 1, exhausted: false };
}
