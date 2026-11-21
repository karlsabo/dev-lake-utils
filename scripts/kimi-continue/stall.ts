import type {AssistantMessage} from "@earendil-works/pi-ai";

/** Model id of Kimi K3.1 Pro on the OpenRouter provider, the one in use. */
export const KIMI_K3_1_PRO_MODEL_ID = "moonshotai/kimi-k3.1-pro";

/**
 * Kimi K3.1 Pro ids: `moonshotai/kimi-k3.1-pro` on OpenRouter,
 * `accounts/fireworks/models/kimi-k3p1-pro` on Fireworks (`p` for the version dot).
 * Sibling Kimi models such as `kimi-k3` and `kimi-k2.6` do not match.
 */
const KIMI_K3_1_PRO = /^kimi\s?k3\s?(?:p\s?)?1\s?pro(?:\s|$)/;

/** True when the model id names Kimi K3.1 Pro, whatever the provider prefix or separators. */
export function isKimiK31ProModel(modelId: string | undefined): boolean {
  if (modelId === undefined) return false;
  const segment = modelId.slice(modelId.lastIndexOf("/") + 1).toLowerCase();
  return KIMI_K3_1_PRO.test(segment.replace(/[^a-z0-9]+/g, " "));
}

/** The fields the stall rule reads from an assistant message. */
export type StallCandidate = Pick<AssistantMessage, "role" | "content" | "stopReason">;

/**
 * Sentence-initial phrases that express intent to act on their own. They only count at
 * the start of a sentence, so "The Next.js upgrade is complete." and other
 * completed-task summaries do not match.
 *
 * "I can" must be followed by whitespace so negated contractions that report a
 * limitation ("I can't ...", "I can’t ...") and "I cannot ..." are not intent.
 */
const INTENT_STARTER = /^(?:I['’]ll\b|I will\b|Let me\b|I can(?=\s)|Use\b|Should\b)/i;

/** Imperative verbs that can begin a planned action, with or without a sequencing word. */
const ACTION_VERBS = [
  "read",
  "re-read",
  "write",
  "edit",
  "run",
  "rerun",
  "re-run",
  "check",
  "verify",
  "inspect",
  "examine",
  "look",
  "open",
  "search",
  "grep",
  "rg",
  "find",
  "list",
  "scan",
  "trace",
  "debug",
  "update",
  "add",
  "remove",
  "delete",
  "create",
  "make",
  "move",
  "rename",
  "fix",
  "apply",
  "patch",
  "replace",
  "install",
  "build",
  "test",
  "lint",
  "format",
  "commit",
  "push",
  "pull",
  "fetch",
  "merge",
  "rebase",
  "stash",
  "review",
  "parse",
  "print",
  "show",
  "get",
  "set",
  "call",
  "send",
  "post",
  "query",
  "compare",
  "confirm",
  "ensure",
];

const ACTION_VERB_STARTER = new RegExp(`^(?:${ACTION_VERBS.join("|")})\\b`, "i");

/** A direct announcement of the final answer; unlike an action verb, the sentence is often unfinished. */
const FINAL_RESPONSE_START = /^(?:So|Perhaps),?\s+(?:short\s+)?(?:answer|response|respond)\b/i;

/** Sequencing words. "Now", "Then", and "Next" only express intent when an action follows. */
const SEQUENCE_STARTER = /^(?:Now|Then|Next)\b/i;

/**
 * What must follow a sequencing word for it to count as intent: an explicit intent
 * clause or an imperative action verb ("Then read ..."). Optional punctuation between
 * the sequencing word and that clause is consumed, not counted, so a comma or colon
 * alone never satisfies the rule: "Now, the implementation is complete." is a summary.
 */
const ACTION_AFTER_SEQUENCE = new RegExp(
  // "I can" requires trailing whitespace here too, so "Now, I can't ..." is a summary.
  String.raw`^\s*(?:[,:]\s*)?(?:(?:I['’]ll|I will|Let me|we['’]ll)\b|I can(?=\s)|(?:` +
    ACTION_VERBS.join("|") +
    String.raw`)\b)`,
  "i",
);

/** Split text into sentences. Only whitespace-delimited terminators split, so "Next.js" stays whole. */
function sentences(line: string): string[] {
  return line
    .split(/(?<=[.!?])\s+/)
    .map((sentence) => sentence.trim())
    .filter((sentence) => sentence.length > 0);
}

/** True when the sentence starts with an intent to act rather than a completed-task summary. */
function statesIntent(sentence: string): boolean {
  // A question is a request for the user, not a statement of what the model will do.
  if (sentence.endsWith("?")) return false;
  if (INTENT_STARTER.test(sentence) || ACTION_VERB_STARTER.test(sentence)) return true;
  const afterSequence = sentence.replace(SEQUENCE_STARTER, "");
  if (afterSequence === sentence) return false;
  return ACTION_AFTER_SEQUENCE.test(afterSequence);
}

/**
 * A turn stalled when the model finished a plain text answer while saying what it
 * would do next instead of doing it. The last non-empty line must contain a sentence
 * that starts with an intent marker, so completed-task summaries such as "The Next.js
 * upgrade is complete." or "Now, the implementation is complete." do not match. A false
 * positive costs one extra turn; a false negative keeps today's behavior of waiting for
 * the user to type "continue".
 */
export function isStalledTurn(message: StallCandidate, modelId: string | undefined): boolean {
  if (!isKimiK31ProModel(modelId)) return false;
  if (message.role !== "assistant") return false;
  if (message.stopReason !== "stop") return false;
  if (message.content.some((part) => part.type === "thinking" || part.type === "toolCall")) {
    return false;
  }

  const text = message.content
    .flatMap((part) => (part.type === "text" ? [part.text] : []))
    .join("\n");

  const lines = text
    .split("\n")
    .map((line) => line.trim())
    .filter((line) => line.length > 0);

  const lastLine = lines.at(-1);
  if (lastLine === undefined) return false;

  const finalSentences = sentences(lastLine);
  const lastSentence = finalSentences.at(-1);
  return (
    finalSentences.some(statesIntent) ||
    (lastSentence !== undefined &&
      !lastSentence.endsWith("?") &&
      FINAL_RESPONSE_START.test(lastSentence))
  );
}
