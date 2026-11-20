# kimi-continue

A local [pi](https://pi.dev) extension. Nothing here is published; pi loads these files
through the `extensions` array in `~/.pi/agent/settings.json`.

Kimi K3.1 Pro (`accounts/fireworks/models/kimi-k3p1-pro`) sometimes ends a turn after
saying what it will do next without making the tool call, so the user has to type
"continue". This extension detects that stall and continues the run automatically.

### What counts as a stall

All of these must hold:

- the model id is exactly `accounts/fireworks/models/kimi-k3p1-pro`
- `stopReason` is `"stop"`, and the turn completed with no tool results
- the message has no `thinking` and no `toolCall` parts
- the last non-empty text line contains a sentence that *starts* with an intent to act:
  - `I'll`, `I will`, `Let me`, `I can`, `Use `, or `Should ` at the start of a sentence
  - an imperative action verb such as `Read docs.` or `Check the diff.` at the start
    of a sentence, including after `Now`, `Then`, or `Next`
  - `Now`, `Then`, or `Next` at the start of a sentence followed by an explicit intent
    clause (`I'll`, `I will`, `Let me`, `I can`, `we'll`). A comma or colon after the
    sequencing word is optional and is consumed, so punctuation alone is not an action:
    "Now, the implementation is complete." is a completed-task summary and does not count.
  - a direct final-answer introduction, `So`, optionally followed by a comma, or
    `Perhaps`, followed by an optional `short` and then `answer`, `response`, or `respond`
    at the start of the final sentence. This covers Kimi stopping at "So short answer:
    producing the answer; descriptions such as "The short answer is complete." do not
    match.

Matching only at the start of a sentence keeps completed-task summaries out, such as
"The Next.js upgrade is complete." or "All tests pass. Now, the implementation is
complete." A false positive costs one extra turn, where the model restates that it is
done. A false negative is the old behavior: the run waits for the user.

When a stall is detected, the extension appends a visible `custom_message` (shown in
the transcript) and asks pi for one more provider request:

> Continue: you stopped before acting. Make the tool call you described now, or give
> your final answer if the task is complete.

### The 3-nudge cap

At most 3 automatic nudges are sent per human prompt. The count is the total since the
last human prompt, not consecutive stalls, so a tool call in between does not reset
it. When the cap is reached, no further continue message is sent and pi shows
"Kimi stalled 3 times; type to continue". The next human prompt resets the count. The
cap guards against the endless loop pi's docs warn about for unconditional
`continue: true`.

### Enable

`~/.pi/agent/settings.json`:

```json
{
  "extensions": ["/Users/karl.sabo/git/dev-lake-utils/scripts/kimi-continue/index.ts"]
}
```

Run `/reload` in a running session, or start a new one. Adding this path is the only
wiring step; pi does not need the files copied into `~/.pi/agent/extensions/`.

### Disable

Remove the `extensions` entry (or this path from it) and run `/reload`. There is no
toggle command yet.

### Test

```sh
npm test
```

`node --test` runs the `.test.ts` files directly; the host-provided
`@earendil-works/*` packages are not dependencies because pi supplies them at runtime.
