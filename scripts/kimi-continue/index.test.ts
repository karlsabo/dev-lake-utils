import assert from "node:assert/strict";
import {test} from "node:test";

import type {BeforeAgentStartEvent, ExtensionAPI, TurnEndEvent,} from "@earendil-works/pi-coding-agent";

import createExtension from "./index.ts";
import {KIMI_K3_1_PRO_MODEL_ID} from "./stall.ts";

const STALL_TEXT = "Git status clean earlier. Should have no change. Use read relevant files and rg.";
const NUDGE =
  "Continue: you stopped before acting. Make the tool call you described now, or give your final answer if the task is complete.";

type Handler = (event: any, ctx: any) => any;

function loadExtension(modelId = KIMI_K3_1_PRO_MODEL_ID) {
  const handlers: Record<string, Handler> = {};
  const pi = {
    on(event: string, handler: Handler) {
      handlers[event] = handler;
      return () => {};
    },
  } as unknown as ExtensionAPI;

  createExtension(pi);

  const notifications: { message: string; type?: string }[] = [];
  const ctx = {
    model: { id: modelId },
    ui: {
      notify(message: string, type?: "info" | "warning" | "error") {
        notifications.push({ message, type });
      },
    },
  };

  const startRun = (messageType: "user" | "custom" = "user") =>
    handlers.before_agent_start?.({ messageType } as BeforeAgentStartEvent, ctx);

  const endStalledTurn = (text = STALL_TEXT) =>
    handlers.turn_end?.(
      {
        outcome: "completed",
        toolResults: [],
        entries: [],
        message: {
          role: "assistant",
          stopReason: "stop",
          content: [{ type: "text", text }],
        },
      } as unknown as TurnEndEvent,
      ctx,
    );

  return { handlers, notifications, startRun, endStalledTurn };
}

test("registers the before_agent_start and turn_end hooks", () => {
  const { handlers } = loadExtension();
  assert.deepEqual(Object.keys(handlers).sort(), ["before_agent_start", "turn_end"]);
});

test("a stalled Kimi turn returns one visible continue message", () => {
  const { notifications, startRun, endStalledTurn } = loadExtension();
  startRun();

  const result = endStalledTurn();
  assert.equal(result.continue, true);
  assert.equal(result.entries.length, 1);
  assert.deepEqual(result.entries[0], {
    type: "custom_message",
    customType: "kimi-continue",
    content: NUDGE,
    display: true,
  });
  assert.deepEqual(notifications, []);
});

test("nudges again when Kimi responds to a nudge with another stalled plan", () => {
  const { notifications, startRun, endStalledTurn } = loadExtension();
  startRun();

  assert.equal(endStalledTurn().continue, true);
  assert.equal(
    endStalledTurn("I have tool calls above actually need continue. No response. Read docs.").continue,
    true,
  );
  assert.deepEqual(notifications, []);
});

test("caps at three nudges per human prompt and warns once", () => {
  const { notifications, startRun, endStalledTurn } = loadExtension();
  startRun();

  const results = [endStalledTurn(), endStalledTurn(), endStalledTurn()];
  for (const result of results) assert.equal(result.continue, true);

  const fourth = endStalledTurn();
  assert.equal(fourth, undefined);
  const fifth = endStalledTurn();
  assert.equal(fifth, undefined);

  assert.deepEqual(notifications, [
    { message: "Kimi stalled 3 times; type to continue", type: "warning" },
  ]);
});

test("a new human prompt resets the nudge budget", () => {
  const { notifications, startRun, endStalledTurn } = loadExtension();
  startRun();
  for (let i = 0; i < 4; i++) endStalledTurn();
  assert.equal(notifications.length, 1);

  startRun();
  const result = endStalledTurn();
  assert.equal(result.continue, true);
});

test("a custom-message run does not reset the budget", () => {
  const { startRun, endStalledTurn } = loadExtension();
  startRun();
  for (let i = 0; i < 3; i++) endStalledTurn();

  startRun("custom");
  assert.equal(endStalledTurn(), undefined);
});

test("does not nudge other models or non-completed turns", () => {
  const other = loadExtension("kimi-k3");
  other.startRun();
  assert.equal(other.endStalledTurn(), undefined);

  const { handlers, startRun } = loadExtension();
  startRun();
  const result = handlers.turn_end?.(
    {
      outcome: "error",
      toolResults: [],
      entries: [],
      message: { role: "assistant", stopReason: "stop", content: [{ type: "text", text: STALL_TEXT }] },
    } as unknown as TurnEndEvent,
    { model: { id: KIMI_K3_1_PRO_MODEL_ID }, ui: { notify() {} } },
  );
  assert.equal(result, undefined);
});
