# Templatize Alert Triage Skill

**Goal:** Make the alert-triage skill reusable outside Klaviyo while preserving Karl's current Klaviyo-specific investigation locations through local Eng Hub configuration.

**Context:**

- The source skill is `llm/.agents/skills/wip-eh-alert-triage/SKILL.md`; its Klaviyo-specific URLs, local repository paths, and service details are concentrated under `## Where to look`.
- The source skill currently proposes `${PLANNING_MARKDOWN_DIR:-.pi-notes}/alert-triage.md`, but Eng Hub only recognizes the exact `${PLANNING_MARKDOWN_DIR}` token. The reusable destination should instead be `${PLANNING_MARKDOWN_DIR}/alert-triage/{descriptive-name}.md`, with the agent choosing a short descriptive name for the investigation (`llm/.agents/skills/wip-eh-alert-triage/SKILL.md`, `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/LlmSkillSync.kt`).
- `eh-plan` provides the model for instructing an agent to choose a descriptive Markdown filename (`llm/.agents/skills/eh-plan/SKILL.md`).
- Markdown templating currently supports only a hard-coded `${PLANNING_MARKDOWN_DIR}` replacement. It applies to skill Markdown, nested Markdown references, guidelines, and notes, but not non-Markdown files (`eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/LlmSkillSync.kt`).
- The sync CLI loads `EngHubConfig` and passes only `planningMarkdownDir` to the synchronizer (`eng-hub/src/jvmMain/kotlin/com/github/karlsabo/devlake/enghub/LlmSkillSyncMain.kt`).
- Eng Hub configuration is serialized through `EngHubConfig`; missing fields receive defaults, allowing a new template-values field to remain backward compatible (`eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHubConfig.kt`, `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/EngHubConfigTest.kt`).
- Existing synchronization behavior and template coverage are tested in `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/LlmSkillSyncTest.kt`.
- Karl's local configuration is `/Users/karl.sabo/Library/Application Support/DevLakeUtils/eng-hub-config.json`. It must retain the current Klaviyo-specific `Where to look` content after the source skill becomes generic.
- Add `llmTemplateValues: Map<String, String> = emptyMap()` to `EngHubConfig`. Keys are clear, skill-specific template names without the `${...}` wrapper, making the mechanism reusable without coupling configuration to alert triage.
- Store each replacement as one opaque multiline Markdown string. Synchronization inserts it without reformatting.
- Use `${UPPER_SNAKE_CASE}` for generic templates and resolve them only in Markdown files, matching the existing templating scope. Non-Markdown files remain byte-for-byte unchanged (`eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/LlmSkillSync.kt`).
- Place `${ALERT_TRIAGE_WHERE_TO_LOOK}` immediately under the source skill's `## Where to look` heading. Karl's local `llmTemplateValues` maps `ALERT_TRIAGE_WHERE_TO_LOOK` to the current Klaviyo-specific Markdown body.
- A missing template value does not stop synchronization. Log an error and replace the token with conspicuous prompt text that identifies the missing setting, directs the user to configure it in Eng Hub, and instructs the LLM to tell the user that the skill is incomplete.
- Add an “LLM skill templates” Settings section with friendly, fixed fields for templates known by bundled skills, starting with the multiline “Alert triage: Where to look” field. Do not expose arbitrary add/remove/rename controls until there is a second concrete use case. Preserve unknown `llmTemplateValues` entries whenever a known field is saved.
- Treat a blank generic template value as missing. Log the same error and install the same corrective in-skill prompt used for an absent key.
- Reserve `PLANNING_MARKDOWN_DIR` for its existing dedicated behavior. A same-named generic map entry cannot override its absolute-path validation or directory creation; ignore that entry during replacement and log a warning rather than invalidating the user's entire Eng Hub configuration.
- Instruct the triage agent to choose a short kebab-case alert or incident name, such as `checkout-api-latency-2025-04-10`, and write to `${PLANNING_MARKDOWN_DIR}/alert-triage/{descriptive-name}.md`.
- Auto-save the multiline template field after the existing 750 ms text-setting debounce and flush pending edits on navigation or exit, consistent with other Settings fields (`eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubSettingsViewModel.kt`).
- Saving a template value only persists configuration. It does not immediately synchronize installed skills; users must rerun `syncLlmFiles`, and missing-value prompt text must say so.
- Promote `wip-eh-alert-triage` to `eh-alert-triage` only in the final story, after the generic behavior and Karl's configuration are ready.
- After successfully installing `eh-alert-triage`, synchronization removes the exact retired `wip-eh-alert-triage` directory from each selected target. Do not broadly delete destination-only skills.
- Replace vendor names in the source skill description with generic categories: incident management, documentation, chat, observability, logs, and local repositories. Karl's configured Markdown retains concrete vendor guidance.
- The repository already has unrelated uncommitted changes. Implementation must avoid modifying or reverting them.

## Acceptance Tests

1. **Configured investigation guidance is installed**
   - Given Eng Hub configuration maps `ALERT_TRIAGE_WHERE_TO_LOOK` to a concrete Markdown fragment, when LLM files are synchronized, then the installed alert-triage skill contains that fragment under `## Where to look`.
2. **Investigation logs use a descriptive configured planning path**
   - Given the source alert-triage skill is synchronized with a configured absolute planning Markdown directory, when the installed skill is read, then it directs the agent to create `${PLANNING_MARKDOWN_DIR}/alert-triage/{descriptive-name}.md` with the planning directory resolved to the configured absolute path and explains how to choose `{descriptive-name}`.
3. **Missing triage guidance is reported**
   - Given the source alert-triage skill references `${ALERT_TRIAGE_WHERE_TO_LOOK}` but configuration does not provide a usable value, when LLM files are synchronized, then the installed skill contains a prompt that names the missing setting and instructs the LLM to tell the user to configure it in Eng Hub.
4. **Alert-triage guidance is editable in Settings**
   - Given Eng Hub Settings is open, when the user edits “Alert triage: Where to look,” then the multiline Markdown is persisted under `llmTemplateValues.ALERT_TRIAGE_WHERE_TO_LOOK` without changing unknown template entries.
5. **Karl's configuration preserves current behavior**
   - Given Karl's current Eng Hub configuration, when it is updated for the generic template mechanism and synchronization runs, then the installed alert-triage skill still contains the current PagerDuty, incident.io, Confluence, Slack, Chronosphere, Grafana, Splunk, and local-code guidance.
6. **The completed skill is published without its WIP name**
   - Given all preceding alert-triage stories are complete, when LLM files are synchronized, then `eh-alert-triage` is installed and the retired `wip-eh-alert-triage` directory is removed.

## Stories

### 1. Install configured Markdown template values

**Status:** Done

**Acceptance criteria:** Given Eng Hub configuration maps `ALERT_TRIAGE_WHERE_TO_LOOK` to a concrete Markdown fragment, when LLM files are synchronized, then the installed alert-triage skill contains that fragment under `## Where to look`.

**Expected edits:**

- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHubConfig.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/LlmSkillSync.kt`
- `eng-hub/src/jvmMain/kotlin/com/github/karlsabo/devlake/enghub/LlmSkillSyncMain.kt`
- `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/EngHubConfigTest.kt`
- `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/LlmSkillSyncTest.kt`
- `eng-hub/README.md`

**Scope:** Add the backward-compatible `llmTemplateValues` config map and generic `${UPPER_SNAKE_CASE}` replacement in Markdown files. Preserve existing `${PLANNING_MARKDOWN_DIR}` validation and directory creation. A generic `PLANNING_MARKDOWN_DIR` entry is ignored with a warning. Do not change the real triage skill or Settings UI yet.

**Notes:** Pass the complete config template map from `LlmSkillSyncMain` into `LlmSkillSync`. Test with a synthetic alert-triage skill and verify non-Markdown files remain unchanged. Run `./gradlew clean build`.

### 2. Install guidance for a missing template value

**Status:** Done

**Acceptance criteria:** Given the source alert-triage skill references `${ALERT_TRIAGE_WHERE_TO_LOOK}` but configuration does not provide a usable value, when LLM files are synchronized, then the installed skill contains a prompt that names the missing setting and instructs the LLM to tell the user to configure it in Eng Hub.

**Expected edits:**

- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/LlmSkillSync.kt`
- `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/LlmSkillSyncTest.kt`
- `eng-hub/README.md`

**Scope:** Treat absent and blank generic values as missing. Log an error and replace each unresolved token with conspicuous Markdown that names the Settings field, says the skill is incomplete, tells the LLM to notify the user, and tells the user to rerun `syncLlmFiles`. Synchronization continues. Do not add Settings UI in this story.

**Notes:** Keep warning generation deterministic so tests can assert the installed content. Resolve all missing tokens in a Markdown file rather than leaving raw placeholders. Run `./gradlew clean build`.

### 3. Edit alert-triage guidance in Eng Hub Settings

**Status:** Done

**Acceptance criteria:** Given Eng Hub Settings is open, when the user edits “Alert triage: Where to look,” then the multiline Markdown is persisted under `llmTemplateValues.ALERT_TRIAGE_WHERE_TO_LOOK` without changing unknown template entries.

**Expected edits:**

- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/state/EngHubSettingsUiState.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/screen/EngHubSettingsScreen.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/screen/EngHubScreenState.kt`
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubSettingsViewModel.kt`
- `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/state/EngHubSettingsUiStateTest.kt`
- `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/screen/EngHubSettingsScreenTest.kt`
- `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubSettingsViewModelTest.kt`
- `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubSettingsSynchronizationRegressionTest.kt`
- `eng-hub/README.md`

**Scope:** Add a fixed “LLM skill templates” section and multiline “Alert triage: Where to look” field. Auto-save after 750 ms and flush pending edits. Preserve unknown map entries. Do not add arbitrary template controls or trigger synchronization after save.

**Notes:** Use a named constant for `ALERT_TRIAGE_WHERE_TO_LOOK` so UI and persistence cannot drift. Extract a focused settings controller if adding this behavior to `EngHubSettingsViewModel.kt` would violate size or complexity checks. Run `./gradlew clean build`.

### 4. Give each alert investigation a descriptive log file

**Status:** Done

**Acceptance criteria:** Given the source alert-triage skill is synchronized with a configured absolute planning Markdown directory, when the installed skill is read, then it directs the agent to create `${PLANNING_MARKDOWN_DIR}/alert-triage/{descriptive-name}.md` with the planning directory resolved to the configured absolute path and explains how to choose `{descriptive-name}`.

**Expected edits:**

- `llm/.agents/skills/wip-eh-alert-triage/SKILL.md`
- `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/LlmSkillSyncTest.kt`

**Scope:** Replace the unsupported shell-style fallback path with the exact planning-directory token and descriptive nested path. Add short kebab-case naming guidance and one example. Leave `Where to look`, metadata, and the WIP name unchanged.

**Notes:** This is intentionally independent from generic templating and can ship first if desired. The sync already creates the configured planning root; the agent creating the log is responsible for the `alert-triage` child directory. Run `./gradlew clean build`.

### 5. Templatize alert-triage locations without changing Karl's installed guidance

**Status:** Done

**Acceptance criteria:** Given Karl's current Eng Hub configuration, when it is updated for the generic template mechanism and synchronization runs, then the installed alert-triage skill still contains the current PagerDuty, incident.io, Confluence, Slack, Chronosphere, Grafana, Splunk, and local-code guidance.

**Expected edits:**

- `llm/.agents/skills/wip-eh-alert-triage/SKILL.md`
- `/Users/karl.sabo/Library/Application Support/DevLakeUtils/eng-hub-config.json`
- `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/LlmSkillSyncTest.kt`

**Scope:** Replace the source `Where to look` body with `${ALERT_TRIAGE_WHERE_TO_LOOK}` and generalize the frontmatter description. Add the removed Markdown body to Karl's local config as one string. Run synchronization and verify Karl's installed copy retains the concrete guidance. Do not rename the skill yet.

**Notes:** The local config edit is a rollout step, not committed repository content. Preserve every existing config field. The repository source must contain no Klaviyo domains, organization names, or local Klaviyo paths after this story. Run `./gradlew clean build` before the local synchronization check.

### 6. Publish alert triage and retire its WIP installation

**Status:** Done

**Acceptance criteria:** Given all preceding alert-triage stories are complete, when LLM files are synchronized, then `eh-alert-triage` is installed and the retired `wip-eh-alert-triage` directory is removed.

**Expected edits:**

- `llm/.agents/skills/wip-eh-alert-triage/SKILL.md` (remove)
- `llm/.agents/skills/eh-alert-triage/SKILL.md` (add)
- `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/LlmSkillSync.kt`
- `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/LlmSkillSyncTest.kt`
- `eng-hub/README.md`

**Scope:** Rename the source directory and frontmatter name. After the new skill is successfully copied to a selected target, delete only that target's exact `wip-eh-alert-triage` directory. Do not delete other destination-only skills or add general destination mirroring.

**Notes:** Test both successful retirement and the safety boundary that unrelated skills remain. Retirement must not run before the replacement skill is installed. Run `./gradlew clean build`.
