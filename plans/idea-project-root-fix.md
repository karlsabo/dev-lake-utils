# Repair an IDEA project pointing at the wrong directory

Use when IntelliJ indexes a parent directory, shows sibling repositories, or retains their changes after moving a project.

## 1. Identify and back up

- Confirm the intended repository/worktree and the active IDE project. Do not assume the agent's working directory or this document's repository is the target. Ask if ambiguous.
- Locate the actual `.idea` directory; follow symlinks and module references. Use bounded searches, including ignored files (`rg --hidden --no-ignore`).
- Have the user save and close the affected project so IDEA cannot overwrite repairs. Back up `.idea` outside the project and record source-file hashes/status. Never delete source files, reset Git, or discard the entire configuration.

## 2. Inspect and repair

| File                             | Look for                                                                                                                    | Change only if incorrect                                                                                                                            |
|----------------------------------|-----------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------|
| `.idea/modules.xml`              | Module `fileurl`/`filepath` pointing elsewhere or missing files.                                                            | Point both attributes at the intended existing `.iml`, usually under `$PROJECT_DIR$/.idea/`.                                                        |
| Referenced `*.iml`               | `<content>`, `<sourceFolder>`, and `<excludeFolder>` URLs covering the parent or stale sibling paths.                       | Anchor content/source roots to the intended project or submodule; remove obsolete exclusions, retaining local environment/build exclusions.         |
| `.idea/vcs.xml`                  | Git mappings to the parent or unrelated repositories.                                                                       | For a single-repository project, map Git to `$PROJECT_DIR$`; preserve intentional multi-root mappings.                                              |
| `.idea/workspace.xml`            | Stale sibling `<change>` entries under `ChangeListManager`; incorrect `Git.Settings`, run, or terminal working directories. | Remove only stale cached entries and correct wrong directories. Preserve local entries, changelist IDs, run configurations, and unrelated settings. |
| `.idea/gradle.xml`, when present | Incorrect linked `externalProjectPath` or module paths.                                                                     | Correct the linked build root and reimport. Prefer reimporting generated Gradle/Maven modules over hand-editing them.                               |

Resolve macros before judging paths: `$PROJECT_DIR$` is the project root; `$MODULE_DIR$` is the `.iml` directory. `$MODULE_DIR$/..` is normally correct for a module inside `.idea`. Preserve intentional external modules and application arguments; never blanket-replace every external path.

For huge `workspace.xml` files, inspect component boundaries rather than dumping the file. Remove stale entries structurally; preserve all other components.

## 3. Verify and reload

- Parse changed XML/IML; resolve module references and root URLs. Confirm required targets exist and indexing/VCS roots no longer unintentionally include the parent.
- Compare against the backup: unrelated settings and source contents must remain unchanged. Account for concurrent commits rather than relying only on `git diff`.
- Reopen the intended project/worktree, not its parent. Check the Project and Git views; reimport the build if needed. XML validation or a successful build alone does not prove the live IDE root changed.
- Report changed files, validation, backup location, and any reload still required.
