import type {ExtensionAPI} from "@earendil-works/pi-coding-agent";

import {MAX_NUDGES, nextNudge} from "./budget.ts";
import {isStalledTurn} from "./stall.ts";

const CUSTOM_TYPE = "kimi-continue";

const NUDGE =
  "Continue: you stopped before acting. Make the tool call you described now, or give your final answer if the task is complete.";

/**
 * Auto-continue for Kimi K3.1 Pro when it ends a turn saying what it will do next
 * without making the tool call. At most MAX_NUDGES nudges are sent per human prompt,
 * which bounds the cost of a false positive.
 */
export default function (pi: ExtensionAPI) {
  let nudges = 0;
  let warned = false;

  pi.on("before_agent_start", (event) => {
    // Only a human prompt resets the budget; the extension's own nudges are custom
    // messages inside the same run, so they do not count as human prompts.
    if (event.messageType !== "user") return;
    nudges = 0;
    warned = false;
  });

  pi.on("turn_end", (event, ctx) => {
    if (event.outcome !== "completed" || event.toolResults.length > 0) return;

    const message = event.message;
    if (message.role !== "assistant") return;
    if (!isStalledTurn(message, ctx.model?.id)) return;

    const decision = nextNudge(nudges, true);
    nudges = decision.count;

    if (!decision.send) {
      // Warn once per human prompt; later stalls stay silent.
      if (!warned) {
        warned = true;
        ctx.ui.notify(`Kimi stalled ${MAX_NUDGES} times; type to continue`, "warning");
      }
      return;
    }

    return {
      entries: [
        ...event.entries,
        {
          type: "custom_message" as const,
          customType: CUSTOM_TYPE,
          content: NUDGE,
          display: true,
        },
      ],
      continue: true,
    };
  });
}
