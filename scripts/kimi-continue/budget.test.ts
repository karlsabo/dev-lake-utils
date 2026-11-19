import assert from "node:assert/strict";
import {test} from "node:test";

import {MAX_NUDGES, nextNudge} from "./budget.ts";

test("stalls at counts 0, 1, and 2 send a nudge", () => {
  assert.deepEqual(nextNudge(0, true), { send: true, count: 1, exhausted: false });
  assert.deepEqual(nextNudge(1, true), { send: true, count: 2, exhausted: false });
  assert.deepEqual(nextNudge(2, true), { send: true, count: 3, exhausted: false });
});

test("the stall at the cap is exhausted and does not send", () => {
  assert.equal(MAX_NUDGES, 3);
  for (const count of [3, 4, 9]) {
    assert.deepEqual(nextNudge(count, true), { send: false, count, exhausted: true });
  }
});

test("a non-stall never sends and does not consume budget", () => {
  for (const count of [0, 1, 2, 3]) {
    assert.deepEqual(nextNudge(count, false), { send: false, count, exhausted: false });
  }
});

test("three stalls per human prompt, then exhaustion", () => {
  let count = 0;
  const sent: boolean[] = [];
  for (let i = 0; i < 5; i++) {
    const decision = nextNudge(count, true);
    count = decision.count;
    sent.push(decision.send);
  }
  assert.deepEqual(sent, [true, true, true, false, false]);
  assert.equal(count, MAX_NUDGES);
});
