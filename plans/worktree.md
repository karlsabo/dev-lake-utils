# Create Worktrees from Existing Branches and Pull Requests

**Goal:** Make it quick to create local worktrees from existing branches or GitHub pull request numbers, either from a repository’s existing Create Worktree dialog or from the global `⋯` actions menu.

**Context:**

- The repository-specific Create Worktree dialog currently accepts a target branch and supplies a selected worktree or inferred default branch as its base (`eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeDialogs.kt`, `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreePanelState.kt`).
- Repository and worktree row actions both open that dialog (`eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeRepositoryRows.kt`, `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeActionMenu.kt`).
- Existing local branches can already be reused, subject to an ancestry confirmation. Existing branches found only on `origin` are deliberately rejected as conflicting target names (`utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitWorktreeService.kt`). Supporting existing remote branches therefore requires a distinct “check out existing branch” path rather than treating all input as a request to create a new branch.
- Pull-request UI state already contains the repository full name, PR number, and head branch (`eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/state/PullRequestUiState.kt`). Its current Setup action can ensure a repository exists, create or reuse a worktree for a known branch, and run configured setup commands (`eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/PullRequestItem.kt`, `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/CheckoutController.kt`). It does not explicitly launch an IDE.
- The global `⋯` menu currently contains only Settings and supports searchable actions (`eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/screen/EngHubScreen.kt`, `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/EngHubActionPopup.kt`).
- PR numbers are scoped to a repository, and branch names can exist in more than one repository. Searches therefore return labeled choices rather than guessing which match the user intended.
- The user chooses an explicit **New** or **Existing** mode. New preserves the current create-from-base workflow; Existing searches applicable branch and PR sources in parallel. For example, `123` can return both branch `123` and PR `#123`.
- Search results must identify their type, repository, and resolved branch. A repository-specific dialog searches only its repository; the global action searches every configured local repository and presents all matches for the user to choose.
- Unconfigured repositories are not searched or cloned. Global search is scoped to `localRepositories` in Eng Hub configuration (`eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHubConfig.kt`).
- A configured repository's GitHub `owner/repository` identity is derived from its `origin` URL, supporting common GitHub HTTPS and SSH forms. Repositories without a recognized GitHub origin remain eligible for branch search but not PR search. Reading the origin, fetching, listing branches, and checking out remote branches belong in the Git APIs (`utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitCommandApi.kt`, `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitWorktreeApi.kt`); PR lookup and fork metadata belong in the GitHub APIs (`utilities/src/commonMain/kotlin/com/github/karlsabo/github/GitHubApi.kt`).
- Each configured repository searches existing worktrees, local branches without worktrees, and branches on `origin`, deduplicated by branch name. `localRepositories` determines repository scope; no other local repositories or Git remotes are searched.
- Branch and PR discovery run independently in the background and never block the UI thread. Each dialog invocation refreshes applicable `origin` branch information once, not on every keystroke. A spinner remains visible while sources are still loading. Source failures are logged rather than presented in the UI, and successful results from other sources remain usable.
- Exact PR-number lookup permits open, closed, or merged PRs when the head branch still exists on the base repository. Fork PRs are omitted from selectable results; an exact reference that resolves only to a fork shows a concise unsupported-fork message. Fork-head checkout remains out of scope.
- Supported PR input forms are exactly `123`, `#123`, `owner/repo#123`, and `https://github.com/owner/repo/pull/123`. Qualified input in a repository-specific dialog must match that repository and never changes its scope. Qualified global input narrows the configured repositories queried.
- Branch results use fuzzy matching and are ordered best-match first. The action search already has Levenshtein-distance ranking, but it is private and tuned to action words, so implementation should evaluate extracting/adapting it rather than duplicating matching knowledge (`eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/EngHubAction.kt`).
- The user must explicitly select a result, even when only one exact result exists. The confirmation button remains disabled until selection. Enter selects a highlighted unselected result; after selection, Enter confirms. Selection alone never starts setup.
- Confirmation creates or reuses the worktree and runs that repository's configured `setupCommands`, following the existing setup workflow; it does not explicitly launch an IDE. If the worktree already exists, it is reused without an additional warning and setup commands run again after normal dialog confirmation.
- Required repository validation after implementation is `./gradlew clean build` (`AGENTS.md`).

## Grounding audit

The overall workflow is grounded in the current code, but several required foundations do not exist yet and must be included in the stories:

- The existing dialog and request model support only create-from-base input; they have no New/Existing mode or search-result state (`eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeDialogs.kt`, `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreePanelState.kt`).
- Existing local branches and exact-path worktrees can be reused. The create-from-base planner explicitly rejects a remote-only `origin` branch, so Existing mode needs a separate remote checkout operation rather than weakening New mode (`utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitWorktreeService.kt`).
- `GitCommandApi` can test one local or remote branch but cannot list branches or read a repository's remote URL. Branch discovery and repository identity therefore need new Git API capabilities (`utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitCommandApi.kt`, `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitCommandService.kt`).
- Configured repositories contain only a local path and setup commands; GitHub `owner/repository` identity is not stored (`eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHubConfig.kt`).
- GitHub supports fetching PR details by URL, and the returned model exposes the head ref. The model does not expose head-repository identity, so it cannot currently enforce the base-repository-only rule for fork PRs (`utilities/src/commonMain/kotlin/com/github/karlsabo/github/GitHubPullRequestReviewApi.kt`, `utilities/src/commonMain/kotlin/com/github/karlsabo/github/PullRequest.kt`).
- Worktree setup already supports create/reuse followed by repository-specific setup commands. The controllers do not explicitly launch an IDE; any opening behavior comes from configured commands (`utilities/src/commonMain/kotlin/com/github/karlsabo/git/WorktreeSetupCoordinator.kt`, `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/LocalWorktreeFromBaseCreator.kt`, `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/ExistingWorktreeController.kt`).
- The global action popup is reusable for finding the new action, but the worktree-result search belongs in a dialog after invoking that action rather than mixing dynamic branch/PR results into action filtering (`eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/EngHubActionPopup.kt`, `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/EngHubAction.kt`, `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/screen/EngHubScreen.kt`).

## Current understanding

Two user entry points are requested:

1. Enhance the existing repository-specific Create Worktree dialog so the user can enter an existing branch or PR number and create/reuse the corresponding worktree in that known repository.
2. Add a global action under the `⋯` menu where the user can enter a branch or PR reference, resolve the correct repository, and create/reuse the corresponding local worktree.

The intended outcome is a thin workflow that avoids manually locating a repository, fetching a branch, and running `git worktree add`.

## Approved acceptance tests

Each accepted core scenario should map to one story, one ticket, and one PR. Scenario 8 is currently proposed as a regression criterion rather than a standalone story; scenarios 9–10 are proposed follow-ups. Error paths and additional polish are deferred unless called out explicitly.

1. **Repository dialog checks out an existing remote branch**
   - Given configured repository `dev-lake-utils` has remote branch `feature/existing-worktree` but no local branch or worktree for it, when Karl opens that repository's Create Worktree dialog, chooses Existing, searches for `existing-worktree`, selects the labeled branch result, and confirms, then a worktree for `feature/existing-worktree` is created and the configured setup workflow runs.

2. **Repository dialog checks out a pull request**
   - Given configured repository `dev-lake-utils` has PR `#123` whose head is `feature/pr-worktree` on the base repository, when Karl chooses Existing in that repository's Create Worktree dialog, searches for `123`, selects the labeled PR result, and confirms with Enter, then a worktree for `feature/pr-worktree` is created and the configured setup workflow runs.

3. **Existing search preserves ambiguous numeric results**
   - Given configured repository `dev-lake-utils` has both branch `123` and PR `#123` with head `feature/pr-123`, when Karl searches for `123` in Existing mode, then the dropdown shows separate results labeled as branch `123` and PR `#123` with its resolved head branch, ordered by match quality.

4. **Global action checks out a branch from a configured repository**
   - Given `dev-lake-utils` and `engineering-docs` are configured and only `engineering-docs` has branch `feature/doc-search`, when Karl invokes Create Worktree from the global `⋯` menu, searches for `doc-search`, selects the `engineering-docs` branch result, and confirms, then its worktree is created under the configured `engineering-docs` repository and that repository's setup workflow runs.

5. **Global action lets the user choose among repositories**
   - Given two configured repositories both have branch `release/123`, when Karl searches globally for `release/123`, then the dropdown shows two branch results labeled with their respective repositories and waits for Karl to select one.

6. **Global action checks out a PR from a configured repository**
   - Given multiple repositories are configured and `engineering-docs` has PR `#456` with base-repository head `feature/pr-search`, when Karl searches globally for `456`, selects the labeled `engineering-docs` PR result, and confirms, then its worktree is created under `engineering-docs` and that repository's setup workflow runs.

7. **Existing worktree is reused**
   - Given `feature/already-local` already has a worktree, when Karl selects that branch from Existing search and confirms, then the existing worktree is reused without an additional warning and its configured setup workflow runs again.

8. **New mode retains create-from-base behavior**
   - Given Karl opens Create Worktree from worktree `feature/base`, when he chooses New, enters `feature/new-child`, and submits, then `feature/new-child` is created from `feature/base` using the current creation workflow.

9. **Supported PR reference aliases resolve to the same PR result**
   - Given configured repository `dev-lake-utils` has PR `#123`, when Karl searches using a supported PR reference form such as `123`, `#123`, `owner/dev-lake-utils#123`, or its full GitHub PR URL, then the search presents the same labeled PR result.
   - This is proposed as one parameterized acceptance test because the aliases are alternate syntax for the same observable capability, not separate user workflows.

10. **Qualified branch input narrows global results**
    - Given more than one configured repository has branch `feature/shared`, when Karl searches globally for `owner/dev-lake-utils:feature/shared`, then only the matching `dev-lake-utils` branch result is shown.

## Approved decomposition

Create seven core stories from scenarios 1–7. Scenario 8 is regression coverage and a scope constraint for changes to the shared dialog rather than a standalone ticket. Create scenarios 9–10 as follow-up stories after the core workflow. Scenario 3 follows branch and PR lookup because its observable behavior depends on both result sources.

This produces seven core stories plus two follow-ups. The first tracer bullet is repository-specific remote branch checkout; PR resolution follows; global workflows then reuse the same discovery and confirmation capabilities.


## Stories

### 1. Check out an existing remote branch from a repository dialog

**Acceptance criteria:** Given configured repository `dev-lake-utils` has remote branch `feature/existing-worktree` but no local branch or worktree for it, when Karl opens that repository's Create Worktree dialog, chooses Existing, searches for `existing-worktree`, selects the labeled branch result, and confirms, then a worktree for local branch `feature/existing-worktree` is created from `origin/feature/existing-worktree` and the repository's configured setup commands run.

**Expected edits:**

- Git branch discovery and explicit remote-branch checkout in `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitCommandApi.kt`, `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitCommandService.kt`, `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitWorktreeApi.kt`, and `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitWorktreeService.kt`.
- Existing-worktree setup request support in `utilities/src/commonMain/kotlin/com/github/karlsabo/git/WorktreeSetupCoordinator.kt` if the current request variants cannot express an explicit existing remote branch safely.
- New/Existing dialog state, asynchronous branch loading, selection, keyboard handling, and confirmation in `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreePanelState.kt`, `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeDialogs.kt`, and `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreePanel.kt`.
- View-model orchestration and setup-command reuse in `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModel.kt`, `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/LocalWorktreeCreateController.kt`, and a focused new Existing-worktree discovery/checkout controller if needed.
- Tests in `utilities/src/commonTest/kotlin/com/github/karlsabo/git/GitCommandServiceTest.kt`, `utilities/src/commonTest/kotlin/com/github/karlsabo/git/GitWorktreeServiceCreateTest.kt`, `utilities/src/commonTest/kotlin/com/github/karlsabo/git/WorktreeSetupCoordinatorTest.kt`, `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreePanelTest.kt`, and focused view-model tests under `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/`.

**Scope:** In scope are one repository, existing worktree/local/`origin` branch discovery, one background fetch per dialog invocation, fuzzy local filtering, a loading spinner, explicit selection, remote checkout, and setup commands. New mode must retain the current create-from-selected-base behavior from acceptance scenario 8. PR results, global search, aliases beyond plain numeric input, fork support, other remotes, explicit IDE launching, and user-visible source errors are out.

**Notes:** This is the tracer bullet. Do not route remote-only branches through `planBranchWorktreeCreation`, which deliberately rejects them (`utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitWorktreeService.kt`). Add a distinct operation that creates the expected local branch from `origin/<branch>` and creates/reuses its worktree. Deduplicate worktrees, local branches, and `origin` branches by branch name. Discovery and Git commands run off the UI thread; failures are logged. Confirmation remains disabled until explicit selection. Enter first selects a highlighted result and then confirms on a subsequent press. Validate with `./gradlew clean build`.

### 2. Check out a pull request from a repository dialog

**Acceptance criteria:** Given configured repository `dev-lake-utils` has PR `#123` whose head is `feature/pr-worktree` on the base repository, when Karl opens that repository's Create Worktree dialog, chooses Existing, searches for `123`, selects the result labeled `PR #123`, repository `owner/dev-lake-utils`, and branch `feature/pr-worktree`, and confirms with Enter, then that branch's worktree is created or reused and the repository's configured setup commands run.

**Expected edits:**

- Origin URL reading and GitHub repository identity parsing in the Git utility module, likely `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitCommandApi.kt`, `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitCommandService.kt`, plus a focused parser file under `utilities/src/commonMain/kotlin/com/github/karlsabo/github/`.
- PR lookup/fork metadata in `utilities/src/commonMain/kotlin/com/github/karlsabo/github/GitHubPullRequestReviewApi.kt`, `utilities/src/commonMain/kotlin/com/github/karlsabo/github/GitHubPullRequestReviewRestApi.kt`, `utilities/src/commonMain/kotlin/com/github/karlsabo/github/PullRequest.kt`, and `utilities/src/commonMain/kotlin/com/github/karlsabo/github/GitHubApi.kt` as needed.
- Existing-result models and repository-scoped PR discovery in `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreePanelState.kt`, `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeDialogs.kt`, `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModelServices.kt`, and the Existing-worktree controller introduced by story 1.
- Tests beside the affected GitHub utilities and in `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreePanelTest.kt` plus focused view-model tests.

**Scope:** In scope are plain numeric exact PR lookup for the repository selected by the dialog, open/closed/merged PRs, base-repository heads, labeled PR results, explicit selection, Enter confirmation, create/reuse, and setup commands. Fork checkout, global PR search, all qualified aliases, and IDE launching are out. An exact fork PR is not selectable and shows a concise unsupported-fork message.

**Notes:** Derive `owner/repository` from common GitHub HTTPS or SSH `origin` URLs; do not add duplicate identity to `LocalRepositoryConfig`. A repository with an unrecognized origin still supports branch search but returns no PR results. Extend the PR response model enough to compare head-repository identity with the base repository before exposing the result. PR discovery and branch discovery are independent background sources; one source failing is logged and does not discard the other's results. Validate with `./gradlew clean build`.

### 3. Preserve separate branch and PR results for numeric input

**Acceptance criteria:** Given configured repository `dev-lake-utils` has both branch `123` and PR `#123` with head `feature/pr-123`, when Karl searches for `123` in Existing mode, then the dropdown shows two distinct selectable results: branch `123`, and PR `#123` resolving to `feature/pr-123`.

**Expected edits:**

- Shared result identity and ranking logic, likely in a focused file under `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/`, with any reusable matching extraction from `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/EngHubAction.kt`.
- Result rendering and selection stability in `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeDialogs.kt` and state models in `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreePanelState.kt`.
- Unit and Compose tests in `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/component/EngHubActionTest.kt` if matching is extracted, and `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreePanelTest.kt`.

**Scope:** In scope are retaining same-text results from different source types, type/repository/resolved-branch labels, fuzzy ranking, deterministic ties, and selection that remains attached to result identity as asynchronous sources finish. New lookup sources, global search, and confirmation behavior changes are out.

**Notes:** Deduplicate branch records only with other branch records of the same repository and branch name; never deduplicate a PR result against a branch result merely because the query or resolved branch overlaps. Ranking must not depend on coroutine completion order. Use stable tie breakers—match quality first, then branch before PR, then repository and display label—unless an existing extracted matcher provides an equivalent deterministic order. Validate with `./gradlew clean build`.

### 4. Check out a branch from the global action

**Acceptance criteria:** Given `dev-lake-utils` and `engineering-docs` are configured in `localRepositories` and only `engineering-docs` has branch `feature/doc-search`, when Karl invokes Create Worktree from the global `⋯` menu, searches for `doc-search`, selects the labeled `engineering-docs` branch result, and confirms, then its worktree is created under the configured `engineering-docs` repository and that repository's setup commands run.

**Expected edits:**

- Global action registration and dialog hosting in `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/screen/EngHubScreen.kt` and `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/EngHubActionPopup.kt` only where needed to invoke the action.
- Global dialog state/action plumbing in `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/screen/EngHubScreenState.kt`, `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModel.kt`, and the Existing-worktree controller created by earlier stories.
- Reuse or extraction of the Existing dialog UI from `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeDialogs.kt` rather than creating a second search implementation.
- Tests in `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/component/EngHubActionPopupTest.kt`, `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/screen/EngHubScreenTest.kt`, and focused view-model tests.

**Scope:** In scope are a searchable `Create Worktree` action under the global menu, all configured `localRepositories`, parallel background branch discovery per repository, repository labels, explicit selection, create/reuse, and repository-specific setup commands. PR lookup, qualified input, unconfigured repositories, cloning, and other remotes are out.

**Notes:** Invoking the global menu item opens the worktree search dialog; dynamic branch results do not belong inside the action popup itself. Fetch each configured repository's `origin` at most once for that dialog invocation and keep the UI responsive with a loading spinner while any source remains active. Use the configured repository path directly for worktree placement and setup-command lookup; do not derive it from `repositoriesBaseDir` or clone missing repositories. Validate with `./gradlew clean build`.

### 5. Let the user choose the repository for an ambiguous global branch

**Acceptance criteria:** Given two configured repositories both have branch `release/123`, when Karl searches globally for `release/123`, then the dropdown shows two separate branch results labeled with their respective repositories and waits for Karl to select one; neither repository is chosen automatically.

**Expected edits:**

- Global result identity, labeling, ordering, and selection in the Existing-worktree search models introduced by stories 1–4.
- Compose rendering in `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreeDialogs.kt` or the extracted shared Existing dialog file.
- Tests in `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreePanelTest.kt` and/or `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/screen/EngHubScreenTest.kt`.

**Scope:** In scope are same-named branches from different configured repositories, visible repository identity, stable ordering, and mandatory explicit selection. Qualified narrowing, PR ambiguity, and checkout changes are out.

**Notes:** Result identity must include repository path plus type plus branch; deduplication is repository-scoped. Sort equal-quality global results deterministically by normalized repository label/path rather than source completion order. The confirm button stays disabled until Karl selects one result, even if only one result was initially available before a second repository finished loading. Validate with `./gradlew clean build`.

### 6. Check out a pull request from the global action

**Acceptance criteria:** Given multiple repositories are configured and `engineering-docs` has PR `#456` with base-repository head `feature/pr-search`, when Karl opens the global Create Worktree action, searches for `456`, selects the result labeled with `engineering-docs`, PR `#456`, and resolved branch `feature/pr-search`, and confirms, then its worktree is created under the configured `engineering-docs` repository and that repository's setup commands run.

**Expected edits:**

- Global PR discovery orchestration in the Existing-worktree controller and state exposed by `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModel.kt`.
- Global dialog state/action collection in `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/screen/EngHubScreenState.kt` and rendering in the shared Existing dialog component.
- Tests in focused view-model test files and `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/screen/EngHubScreenTest.kt`.

**Scope:** In scope are plain numeric PR lookup across configured repositories with recognized GitHub origins, independent parallel source loading, repository/type/branch labels, fork filtering, explicit selection, worktree create/reuse, and configured setup commands. Qualified aliases, unconfigured repositories, cloning, and fork checkout are out.

**Notes:** A numeric query may produce branch and PR results in every applicable repository; preserve all distinct results. Reuse repository identity and PR validation from story 2 rather than consulting the already-polled Pull Requests pane, whose contents are author/organization filtered and not a complete repository lookup source. Repositories without recognized GitHub origins still contribute branch results. Validate with `./gradlew clean build`.

### 7. Reuse an existing worktree from Existing search

**Acceptance criteria:** Given configured repository `dev-lake-utils` already has a worktree for `feature/already-local`, when Karl selects that branch from Existing search and confirms, then the existing worktree is reused in place without an additional warning and the repository's configured setup commands run again.

**Expected edits:**

- Existing-result metadata and confirmation routing in the Existing-worktree controller introduced by story 1.
- Reuse of setup behavior from `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/ExistingWorktreeController.kt` and `utilities/src/commonMain/kotlin/com/github/karlsabo/git/WorktreeSetupCoordinator.kt`.
- Any required distinction in `utilities/src/commonMain/kotlin/com/github/karlsabo/git/GitWorktreeService.kt` between discovery-time existing paths and checkout operations.
- Tests in `utilities/src/commonTest/kotlin/com/github/karlsabo/git/WorktreeSetupCoordinatorTest.kt`, `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubExistingWorktreeOpenViewModelTest.kt`, and Existing-dialog component tests.

**Scope:** In scope are exact existing-worktree reuse after normal dialog confirmation and rerunning setup commands. Automatic confirmation, warning dialogs, setup-result caching, IDE launching, and branches checked out at unexpected external paths are out.

**Notes:** Discovery may have fetched before selection as part of normal dialog loading; confirmation itself must not recreate or relocate an existing worktree. Use the discovered canonical worktree path rather than blindly rebuilding it if the existing API proves it is the configured repository's worktree for that branch. Do not show the unrelated-existing-branch confirmation used by New mode. Validate with `./gradlew clean build`.

### 8. Resolve supported PR aliases consistently

**Acceptance criteria:** Given configured repository `dev-lake-utils` has PR `#123`, each search input `123`, `#123`, `owner/dev-lake-utils#123`, and `https://github.com/owner/dev-lake-utils/pull/123` presents the same labeled PR result. In a repository-specific dialog, a qualified alias for another repository presents no PR result and does not change dialog scope.

**Expected edits:**

- A focused PR-reference parser under `utilities/src/commonMain/kotlin/com/github/karlsabo/github/` or `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/`, depending on whether it is UI-independent.
- Query normalization in the Existing-worktree discovery controller and dialog state.
- Parameterized parser/controller tests plus Compose coverage in `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/component/WorktreePanelTest.kt`.

**Scope:** In scope are exactly the four approved PR forms, repository-scope validation, and equivalent result identity. Additional GitHub URL forms, shorthand without a PR number, cross-host URLs, and branch qualification are out.

**Notes:** Parse references structurally before fuzzy branch matching; do not infer arbitrary digit substrings as PR numbers. Plain `123` remains both a valid branch query and PR `#123`, so parsing must not suppress branch results. Repository-specific qualified references only narrow/validate scope and never select an unconfigured repository. Validate with `./gradlew clean build`.

### 9. Narrow global branch results with qualified input

**Acceptance criteria:** Given more than one configured repository has branch `feature/shared`, when Karl searches globally for `owner/dev-lake-utils:feature/shared`, then only the `dev-lake-utils` branch result is shown, labeled with its repository and branch.

**Expected edits:**

- Qualified-branch parsing and repository matching in the shared Existing-worktree query parser/controller.
- Global filtering in the Existing-worktree result pipeline and rendering tests in `eng-hub/src/commonTest/kotlin/com/github/karlsabo/devlake/enghub/screen/EngHubScreenTest.kt` or the shared dialog test file.
- Focused parser unit tests under the module that owns the parser.

**Scope:** In scope is exact `owner/repository:branch` qualification for global branch search against configured repositories with matching origin identity. Unqualified behavior, PR aliases, wildcard owners/repositories, other separators, unconfigured repositories, and cloning are out.

**Notes:** Qualification narrows the configured repository set before presenting results but does not make unconfigured repositories eligible. Match `owner/repository` using normalized GitHub origin identity, not the local directory name alone. Preserve the full text after the first valid repository separator as the branch query so branch slashes remain intact. Validate with `./gradlew clean build`.
