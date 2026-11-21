import assert from "node:assert/strict";
import {test} from "node:test";

import {isStalledTurn, KIMI_K3_1_PRO_MODEL_ID, type StallCandidate,} from "./stall.ts";

const KIMI = KIMI_K3_1_PRO_MODEL_ID;

function textTurn(text: string, stopReason: StallCandidate["stopReason"] = "stop"): StallCandidate {
  return { role: "assistant", stopReason, content: [{ type: "text", text }] };
}

function thinkingTurn(text: string): StallCandidate {
  return {
    role: "assistant",
    stopReason: "stop",
    content: [
      { type: "thinking", thinking: "Weighing the next step." },
      { type: "text", text },
    ],
  };
}

function toolCallTurn(text: string): StallCandidate {
  return {
    role: "assistant",
    stopReason: "toolUse",
    content: [
      { type: "text", text },
      { type: "toolCall", id: "call_1", name: "read", arguments: {} },
    ],
  };
}

// Stall fixtures copied from ~/.pi/agent/sessions; the tests never read sessions.
const STALLS = [
  // 2026-11-17T17-00-16-034Z_01a1e* (next user message: "continue")
  "Great. I'll write planned comments doc. If no findings, empty overall and inline.",
  // 2026-11-09T20-07-05-700Z* (next user message: "continue")
  "Git status clean earlier. Current branch base? Should have no change. Use read relevant files and rg.",
  // 2026-11-16T15-28-47-047Z*
  "So fix needed. Should have exact comment and how many review max5.\nI'll use write prompt and edit parallel.",
  // 2026-11-16T23-51-44-356Z* (next user message: "huh?")
  "No subagent. Should have no yaml module. Could use `ruby -ryaml`.\nShould read notification policy path exact list `rg --files notification-policies/iam`. Then read main identity file perhaps generated name.",
  // 2026-11-19T22-46-06-014Z* (response to the first automatic nudge)
  "I have tool calls above actually need continue. No response. Read docs.",
  // 2026-11-06T21-15-58-468Z* (next user message: "What's your answer?")
  "What I should distinguish is evals: they run under Pants and already import klaviyo, so no same restriction. Routing them through helper avoids divergence but extends duplication and requires loosening BUILD visibility. Reusing knotary there would arguably be cleaner; for maintenance scripts true reuse requires changing execution contract to Pants/packaged env or extracting small standalone shared lib. So short answer: duplicate was deliberate to preserve direct-python CLI workflow, not technically required, and it's fair to question",
];

test("detects the six recorded Kimi K3.1 Pro stalls", () => {
  for (const text of STALLS) {
    assert.equal(isStalledTurn(textTurn(text), KIMI), true, text);
  }
});

test("accepts an imperative action at the start of a sentence", () => {
  assert.equal(isStalledTurn(textTurn("Read docs."), KIMI), true);
  assert.equal(isStalledTurn(textTurn("Check the diff, then run the tests."), KIMI), true);
});

test("detects an unfinished final-answer introduction", () => {
  const intents = [
    "So short answer: duplicate was deliberate, and it's fair to question",
    "So respond: we should keep the existing API",
    "Perhaps respond with a three-paragraph explanation.",
    "Would this violate sharing? So short answer: no, because the execution contracts differ",
  ];
  for (const text of intents) {
    assert.equal(isStalledTurn(textTurn(text), KIMI), true, text);
  }

  const finals = [
    "The short answer is complete.",
    "So the answer is documented below.",
    "We gave a short answer and recorded the supporting evidence.",
    "Perhaps respond: should I use option A?",
  ];
  for (const text of finals) {
    assert.equal(isStalledTurn(textTurn(text), KIMI), false, text);
  }
});

test("passes genuine final answers through", () => {
  const finals = [
    "Completed the review of PR #96240.\nA second evidence-and-calibration pass preserved the no-findings result. Nothing was staged or published.",
    "Validation: `git diff --check` passed.",
    "Please review the document and let me know what you think?",
    // Completed-task summaries: the intent words appear, but the sentences do not
    // start with an intent to act.
    "The Next.js upgrade is complete.",
    "All tests pass. Now the implementation is complete.",
    "Done. Now, the implementation is complete.",
    "The next step is documented in the README.",
    "Next.js dev server verified on port 3000.",
  ];
  for (const text of finals) {
    assert.equal(isStalledTurn(textTurn(text), KIMI), false, text);
  }
});

test("requires an action after Now, Then, and Next", () => {
  const intents = [
    "Next, I'll run the full suite.",
    "Now let me read the config.",
    "Then check the generated diff.",
    "Next write the test.",
    // Punctuation is consumed, so what follows it still has to be an action.
    "Now, I'll run the tests.",
    "Then: read the config.",
    "Next, check the diff.",
  ];
  for (const text of intents) {
    assert.equal(isStalledTurn(textTurn(text), KIMI), true, text);
  }

  const summaries = [
    "Now the implementation is complete.",
    "Then the tests were green.",
    "Next the release notes were updated.",
    // A comma or colon alone must not satisfy the action requirement.
    "Now, the implementation is complete.",
    "Next: nothing remains.",
    "Then, all tests pass.",
  ];
  for (const text of summaries) {
    assert.equal(isStalledTurn(textTurn(text), KIMI), false, text);
  }
});

test("does not treat a negated ability as intent", () => {
  const finals = [
    "I can't run the tests in this environment.",
    "I can’t verify this without production access.",
    "I cannot run the tests in this environment.",
    "I cannot verify this without production access.",
    // The same endings after a sequencing word must stay summaries.
    "Now, I can't run the tests in this environment.",
    "Now, I can’t verify this without production access.",
    "Now, I cannot verify this without production access.",
    "Then, I can't confirm the generated diff.",
    "Next I can’t check the logs from here.",
  ];
  for (const text of finals) {
    assert.equal(isStalledTurn(textTurn(text), KIMI), false, text);
  }

  // Positive control: "I can" followed by whitespace still counts as intent.
  const intents = [
    "I can run the tests in this environment.",
    "Now, I can verify this locally.",
  ];
  for (const text of intents) {
    assert.equal(isStalledTurn(textTurn(text), KIMI), true, text);
  }
});

test("ignores turns with a thinking block or a tool call", () => {
  assert.equal(isStalledTurn(thinkingTurn(STALLS[0]), KIMI), false);
  assert.equal(isStalledTurn(toolCallTurn(STALLS[0]), KIMI), false);
});

test("ignores error and aborted responses", () => {
  assert.equal(isStalledTurn(textTurn(STALLS[0], "error"), KIMI), false);
  assert.equal(isStalledTurn(textTurn(STALLS[0], "aborted"), KIMI), false);
});

test("matches Kimi K3.1 Pro across provider prefixes and id spellings", () => {
  const ids = [
    "moonshotai/kimi-k3.1-pro",
    "accounts/fireworks/models/kimi-k3p1-pro",
    "kimi-k3.1-pro",
    "kimi-k3p1-pro",
    "kimi-k3-1-pro",
    "kimi-k31-pro",
    "Kimi-K3.1-Pro",
    "moonshotai/kimi-k3.1-pro:free",
    "kimi-k3.1-pro-preview",
  ];
  for (const id of ids) {
    assert.equal(isStalledTurn(textTurn(STALLS[0]), id), true, id);
  }
});

test("ignores other Kimi models", () => {
  const ids = [
    "moonshotai/kimi-k3",
    "kimi-k3",
    "accounts/fireworks/models/kimi-k3",
    "kimi-k3.1",
    "moonshotai/kimi-k2.6",
    "cf/moonshotai/kimi-k2.6",
    "kimi-k3.2-pro",
    "my-kimi-k3.1-pro",
  ];
  for (const id of ids) {
    assert.equal(isStalledTurn(textTurn(STALLS[0]), id), false, id);
  }
  assert.equal(isStalledTurn(textTurn(STALLS[0]), undefined), false);
});

test("ignores empty text", () => {
  assert.equal(isStalledTurn(textTurn("   \n\n"), KIMI), false);
});
