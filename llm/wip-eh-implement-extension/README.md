# WIP EH implement extension

This extension runs the implementation workflow as a sequence of isolated, tool-capable Pi subagents:

```text
contract
  -> black-box tests -> test review -> test fixes
  -> implementation
  -> verification <-> failure fixes
  -> white-box tests -> test review -> test fixes
  -> change review <-> review fixes
  -> final verification <-> failure fixes
  -> done
```

Each state starts a separate `pi` process in the current repository with the active model and thinking level. Worker states can read and edit files or run commands. Review states return structured findings that determine the next transition. Test and final-review repair loops are capped at two attempts, and repository validation runs again after the final review.

The command waits for the current agent to become idle and rejects overlapping workflow invocations. While it runs, a persistent widget below the editor shows the active workflow state and elapsed time. It snapshots pre-existing dirty files before work starts so final review includes only the workflow's additional changes to those files.

The workflow reads `../notes.md`, honors repository `AGENTS.md` instructions, rejects work containing multiple acceptance-test slices, and applies the `eh-pr-review` guidance during final review when that skill is installed globally.

## Install

Register the extension globally:

```bash
pi install dev-lake-utils:llm/wip-eh-implement-extension/index.ts
```

Restart Pi or run `/reload` after changing the extension.

For a one-off test:

```bash
pi -e dev-lake-utils:llm/wip-eh-implement-extension/index.ts
```

## Run

Invoke the workflow from the repository to modify:

```text
/wip-eh-implement implement one narrowly scoped behavior
```

The command runs through the complete workflow without placing a plan in the editor or requiring another Enter press. It modifies the current worktree, runs repository-required validation, and stores a `wip-eh-implement-result` entry in the current Pi session.

## Test

```bash
cd dev-lake-utils:llm/wip-eh-implement-extension
npm test
```
