# GitLab support

## Repository move

Eng Hub moved out of this monorepo (see [Eng Hub standalone plan, story 9](https://gitlab.nimblygames.com/NimblyGames/business/-/blob/master/NimblyGames/planning/eng-hub-standalone-repo.md)).
This plan is preserved as history and its paths are updated for the new layout:

- [Eng Hub repo](https://gitlab.nimblygames.com/NimblyGames/eng-hub) — paths beginning `src/` are relative to this
  repo, and Eng Hub's package is now `com.nimblygames.enghub`.
- [Framework `GitLab` module](https://gitlab.nimblygames.com/nimblygamespublic/framework/-/tree/master/GitLab) —
  the live GitLab client for the standalone Eng Hub, which imports `com.nimblygames.gitlab` from this module.
- The `utilities/src/commonMain/kotlin/com/github/karlsabo/gitlab/` sources remain in this repository but are
  unreferenced legacy code, dead until the utilities migration follow-up. Treat that copy as retained history, not an
  implementation target. Other `utilities/` paths still refer to this repository.

The Eng Hub TODO backlog that lived in the root README, including its `img.png` screenshot, was not carried into the
standalone repo or GitLab issues. It stays discoverable through these pinned permalinks without restoring the removed
app code:

- [Historical Eng Hub TODO backlog](https://github.com/karlsabo/dev-lake-utils/blob/d3bc4800b623f91682abf798b9f1903b68942379/README.md#todo)
- [Historical TODO screenshot](https://github.com/karlsabo/dev-lake-utils/blob/d3bc4800b623f91682abf798b9f1903b68942379/img.png)

## Goal

Support self-managed GitLab alongside GitHub in dev-lake-utils, beginning with authored open merge requests in Eng Hub.

## Context

- Request: “add support for gitlab”; planning artifact belongs in `/home/karl/git/dev-lake-utils/plans`.
- `https://gitlab.nimblygames.com/NimblyGames/eng-hub/-/blob/main/README.md` describes a GitHub-only desktop experience for pull requests and notifications, including browser, setup, review, and notification actions.
- `src/commonMain/kotlin/com/nimblygames/enghub/EngHubConfig.kt` stores GitHub author and organization settings; `src/commonMain/kotlin/com/nimblygames/enghub/EngHubComponent.kt` wires GitHub API services.
- `/home/karl/git/dev-lake-utils/utilities/src/commonMain/kotlin/com/github/karlsabo/github/` contains GitHub integration; no corresponding GitLab package was found under `utilities/src/commonMain/kotlin/com/github/karlsabo/`.
- Decisions: GitLab must be self-managed; keep GitHub working alongside GitLab, with provider identification. Start with a read-only authored-open-merge-request view in Eng Hub, then expand toward GitHub feature parity. These choices avoid migrating existing users and make the first slice independently demonstrable.
- `src/commonMain/kotlin/com/nimblygames/enghub/viewmodel/PullRequestPolling.kt` polls authored GitHub PRs and fetches CI/review summaries; `src/commonMain/kotlin/com/nimblygames/enghub/viewmodel/NotificationActionController.kt` implements GitHub notification write actions.
- Scope decision: only Eng Hub, not the publishers, agent skills, or release automation. `/home/karl/git/dev-lake-utils/summary-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/tools/SummaryPublisherDependencies.kt` and `/home/karl/git/dev-lake-utils/user-metrics-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/metrics/UserMetricPublisherDependencies.kt` inject GitHub PR APIs but are out of scope.
- One configurable HTTPS self-managed instance; authored MRs from all projects visible to the account; GitHub and GitLab appear side by side. Do not hardcode a host. Start with GitLab To-Dos as the inbox analogue; do not port the GitHub-specific auto-approve rule without an explicit request.
- Authentication decision: OAuth app registration is currently unavailable to the user; use a user-created personal access token (PAT), entered through GitLab Settings in Eng Hub. The user asked whether CIMD (Client ID Metadata Documents) could avoid registration. GitLab's documented [OAuth flow](https://docs.gitlab.com/api/oauth2/) requires a registered application, including for PKCE; no CIMD support was verified for the target instance. Do not make CIMD, OAuth registration, a client secret, or a loopback callback prerequisites for this work. Revisit registration-free OAuth only if the target instance explicitly supports it.
- Token storage decision: The user accepted the protected secret-file recommendation. Persist the GitLab PAT separately from GitHub, with restrictive permissions and atomic replacement; keep only non-secret settings and the secret-file path in ordinary config. This is cross-platform and follows the existing pattern in `utilities/src/commonMain/kotlin/com/github/karlsabo/github/config/GitHubConfig.kt` and `src/commonMain/kotlin/com/nimblygames/enghub/viewmodel/EngHubGitHubTokenController.kt`, but provides weaker at-rest protection than an OS credential store. Mask token entry and never display saved tokens or log secrets/authorization headers.
- Token lifecycle and permissions: Start with a `read_api` PAT for the read-only slice; later write actions require an `api` PAT and explicit setup guidance rather than silently requesting broader access. Verify required scopes against the target instance. PATs do not have OAuth refresh tokens; an expired/revoked token requires the user to create and save a replacement. See [GitLab personal access tokens](https://docs.gitlab.com/user/profile/personal_access_tokens/).
- `src/commonMain/kotlin/com/nimblygames/enghub/state/PullRequestUiState.kt` and `NotificationUiState.kt` hold GitHub-shaped display models; `src/commonMain/kotlin/com/nimblygames/enghub/viewmodel/CheckoutController.kt` hardcodes github.com clone URLs. GitLab host/project identity must be retained through UI actions to avoid cloning the wrong repository or conflating GitHub and GitLab items.
- Target GitLab version: 19.5 or higher. Use equivalent observable outcomes, not endpoint-by-endpoint parity. Omit Unsubscribe/Request Changes on GitLab unless their effects can be verified on the target instance; don't mislabel a dismissed To-Do or ordinary comment. Use existing Git credentials/SSH remotes for private-project worktrees, never embed a GitLab API token in clone URLs or logs; cross-project MRs are a separate slice. GitHub-specific auto-approve remains out of scope.
- Validation prerequisites: obtain access to a test GitLab 19.5+ instance/account with permission to create a PAT; verify authored-MR reads and, before the relevant later slices, To-Do/review API behavior. Test-instance access has not been confirmed. Automated tests can use fake API responses, but live acceptance remains unverified until instance access is available. No OAuth app registration is required.

## Acceptance tests (proposed sequence)

One user-visible test per story/PR. Examples use `https://gitlab.internal.example/games/atlas/-/merge_requests/42` on a configured self-managed instance. GitHub stays functional; do not interpret this example host as a required deployment URL. Actions without verified GitLab equivalents remain conditional.

1. Given a configured GitLab account that authored open MR `games/atlas!42`, when Eng Hub refreshes the merge-request pane, then the user sees `!42` identified as GitLab.
2. Given `games/atlas!42` in the pane, when the user chooses Open in Browser, then the self-managed MR URL opens.
3. Given `games/atlas!42` with a fetchable source branch, when the user chooses Setup, then a worktree for that branch opens locally.
4. Given `games/atlas!42` with a failing pipeline, when Eng Hub refreshes, then the MR displays a failing CI status.
5. Given `games/atlas!42` awaiting review, when Eng Hub refreshes, then its review summary shows the outstanding approval state.
6. Given a GitLab To-Do for `games/atlas!42`, when Eng Hub refreshes notifications, then the user sees that item identified as GitLab.
7. Given that To-Do, when the user chooses Open, then its target opens in the browser.
8. Given that To-Do, when the user chooses Done, then it is removed from the inbox on refresh.
9. Given that To-Do, when the user chooses a verified Unsubscribe equivalent, then notifications for that MR stop reappearing. (Conditional on verified GitLab capability; otherwise omit this story and hide the action.)
10. Given a GitLab MR To-Do, when the user chooses Approve, then the MR is approved on the GitLab instance.
11. Given a GitLab MR To-Do, when the user submits a comment review, then the comment appears on the MR.
12. Given a GitLab MR To-Do, when the user requests changes through a verified GitLab equivalent, then the MR reflects a blocking review outcome. (Conditional on verified GitLab capability; otherwise omit this story and hide the action.)
13. Given a GitLab MR To-Do for a fetchable branch, when the user chooses Setup, then its worktree is available locally.
14. Given a merged MR To-Do, when Eng Hub refreshes, then the completed item is cleared.
15. Given a GitLab MR To-Do, when the user submits an approval review from Review, then the MR is approved on GitLab.
16. Given an open draft `games/atlas!42`, when Eng Hub refreshes, then the MR is labelled draft.
17. Given an authored GitHub PR and `games/atlas!42`, when Eng Hub refreshes, then both are visible with distinct provider labels.
18. Given a configured local checkout of `games/atlas`, when the user enters its GitLab MR URL in repository worktree discovery, then the matching branch is offered for setup.
19. Given a configured local checkout of `games/atlas`, when the user enters its GitLab MR URL in global worktree discovery, then the matching worktree is offered.
20. Given a closed (not merged) MR To-Do, when Eng Hub refreshes, then the obsolete item is cleared.
21. Given a saved GitLab PAT that has expired, when the user saves a valid replacement PAT in Settings, then authored `games/atlas!42` appears on the next refresh.
22. Given a connected GitLab user, when they disconnect GitLab in Settings, then Eng Hub stops accessing that instance until they reconnect.
23. Given a cross-project MR `games/atlas!42` whose source branch lives in `games/atlas-fork`, when the user chooses Setup, then Eng Hub checks out the source branch from `games/atlas-fork`.

The first slice is story 1. Story 17 tests coexistence explicitly rather than assuming it from a GitLab-only list test; GitHub regression coverage belongs in every affected PR.

## Stories (proposed: one ticket and one PR each)

Paths beginning `src/` are relative to the Eng Hub repo; paths under `utilities/` are relative to `/home/karl/git/dev-lake-utils/`. Each acceptance criterion is the same-numbered test above. The live GitLab client is the framework `GitLab` module (`com.nimblygames.gitlab`); where a story below lists GitLab API work under `utilities/src/commonMain/kotlin/com/github/karlsabo/gitlab/`, that is the retained, unreferenced legacy copy, so implement API changes in the framework module instead of extending the orphaned copy. Put tests alongside the corresponding module's existing tests. All stories preserve GitHub behavior; feature-specific adapters can be introduced when needed rather than replacing GitHub interfaces wholesale.

### 1. See authored GitLab merge requests
**Acceptance criteria:** Given an account on `gitlab.internal.example` that authored open `games/atlas!42`, when Eng Hub refreshes, then `!42` appears identified as GitLab.
**Expected edits:** `src/commonMain/kotlin/com/nimblygames/enghub/EngHubConfig.kt`, `EngHubDependencies.kt`, `screen/EngHubSettingsScreen.kt`, `viewmodel/PullRequestPolling.kt`, `state/PullRequestUiState.kt`, `component/PullRequestItem.kt`; new GitLab config/API under `utilities/src/commonMain/kotlin/com/github/karlsabo/gitlab/`; `https://gitlab.nimblygames.com/NimblyGames/eng-hub/-/blob/main/README.md`.
**Scope:** One HTTPS instance, all visible authored MRs; no CI/reviews, browser, setup, or inbox action yet. Existing GitHub settings must still work. **Notes:** Current wiring is GitHub-only in `src/commonMain/kotlin/com/nimblygames/enghub/EngHubDependencies.kt` and polling is GitHub-only in `src/commonMain/kotlin/com/nimblygames/enghub/viewmodel/PullRequestPolling.kt`. Add instance URL and masked PAT entry in Settings; validate the account through the configured instance and persist its PAT in a separate protected secret file with restrictive permissions and atomic writes. Use `read_api` for this read-only slice; identify the author from the authenticated account. No OAuth registration or browser callback is required. Never expose secrets in logs or display saved tokens. If this slice exceeds a few days, narrow the test to configuring access and displaying one MR, not a standalone authentication-infrastructure story.

### 2. Open a GitLab MR in the browser
**Acceptance criteria:** Given `games/atlas!42` in Eng Hub, when Open in Browser is chosen, then its self-managed MR URL opens.
**Expected edits:** `src/commonMain/kotlin/com/nimblygames/enghub/component/PullRequestItem.kt`, `state/PullRequestUiState.kt`.
**Scope:** URL action only. **Notes:** Preserve the API-provided web URL; do not construct a github.com URL. The current display model is in `src/commonMain/kotlin/com/nimblygames/enghub/state/PullRequestUiState.kt`.

### 3. Set up a GitLab MR worktree
**Acceptance criteria:** Given `games/atlas!42` with a fetchable source branch, when Setup is chosen, then its branch is checked out into a local worktree.
**Expected edits:** `src/commonMain/kotlin/com/nimblygames/enghub/viewmodel/CheckoutController.kt`, `state/PullRequestUiState.kt`, `component/PullRequestItem.kt`.
**Scope:** Same-project MR first; cross-project MR is story 23. **Notes:** `CheckoutController.kt` hardcodes `https://github.com/$repoFullName.git`; carry host/clone URL and provider identity through setup; use existing Git credentials/SSH remotes, never put GitLab API tokens in clone URLs.

### 4. Show GitLab pipeline status
**Acceptance criteria:** Given a failing pipeline on `games/atlas!42`, when Eng Hub refreshes, then the MR shows failing CI status.
**Expected edits:** GitLab API under `utilities/src/commonMain/kotlin/com/github/karlsabo/gitlab/`; `src/commonMain/kotlin/com/nimblygames/enghub/viewmodel/PullRequestPolling.kt`, `state/PullRequestUiState.kt`.
**Scope:** Status display, not pipeline controls. **Notes:** GitHub enrichment currently happens in `PullRequestPolling.kt`; adapt GitLab pipeline state independently.

### 5. Show GitLab approval progress
**Acceptance criteria:** Given `games/atlas!42` awaiting an approval, when Eng Hub refreshes, then its review summary shows the outstanding approval state.
**Expected edits:** GitLab API under `utilities/src/commonMain/kotlin/com/github/karlsabo/gitlab/`; `src/commonMain/kotlin/com/nimblygames/enghub/viewmodel/PullRequestPolling.kt`, `state/PullRequestUiState.kt`.
**Scope:** Read-only approval summary. **Notes:** `PullRequestUiState.kt` formats GitHub requested/approved counts; use GitLab's actual approval rules rather than inventing reviewer counts.

### 6. See GitLab To-Dos
**Acceptance criteria:** Given a GitLab To-Do for `games/atlas!42`, when Eng Hub refreshes notifications, then the item appears identified as GitLab.
**Expected edits:** GitLab API under `utilities/src/commonMain/kotlin/com/github/karlsabo/gitlab/`; `src/commonMain/kotlin/com/nimblygames/enghub/viewmodel/NotificationPolling.kt`, `state/NotificationUiState.kt`, `component/NotificationItem.kt`.
**Scope:** Read-only To-Dos first, no actions. **Notes:** `NotificationPolling.kt` processes GitHub notifications; retain provider and instance identity so ignored-thread IDs cannot collide across systems.

### 7. Open a GitLab To-Do
**Acceptance criteria:** Given the MR To-Do, when Open is chosen, then its GitLab target opens in the browser.
**Expected edits:** `src/commonMain/kotlin/com/nimblygames/enghub/state/NotificationUiState.kt`, `component/NotificationItem.kt`.
**Scope:** Browser action only. **Notes:** Existing `NotificationUiState.kt` converts GitHub API URLs; GitLab should use the target's actual web URL.

### 8. Mark a GitLab To-Do done
**Acceptance criteria:** Given the MR To-Do, when Done is chosen, then it disappears from the inbox after refresh.
**Expected edits:** GitLab API under `utilities/src/commonMain/kotlin/com/github/karlsabo/gitlab/`; `src/commonMain/kotlin/com/nimblygames/enghub/viewmodel/NotificationActionController.kt`, `viewmodel/IgnoredNotificationPersistence.kt`.
**Scope:** Mark done only. **Notes:** `NotificationActionController.kt` currently invokes GitHub notification APIs; preserve provider-specific IDs in persistence and retry behavior.

### 9. Stop GitLab To-Dos reappearing (conditional)
**Acceptance criteria:** Given the MR To-Do, when the user chooses a verified unsubscribe equivalent, then notifications for that MR stop reappearing.
**Expected edits:** GitLab API under `utilities/src/commonMain/kotlin/com/github/karlsabo/gitlab/`; `src/commonMain/kotlin/com/nimblygames/enghub/viewmodel/NotificationActionController.kt`, `component/NotificationActions.kt`.
**Scope:** Only if the target instance supports an equivalent outcome; otherwise revisit/omit this story. **Notes:** GitHub Unsubscribe currently uses `NotificationActionController.kt`; dismissing one To-Do is not equivalent to unsubscribing. Omit this story if no equivalent exists.

### 10. Approve a GitLab MR from the inbox
**Acceptance criteria:** Given the MR To-Do, when Approve is chosen, then GitLab records the approval.
**Expected edits:** GitLab API under `utilities/src/commonMain/kotlin/com/github/karlsabo/gitlab/`; `src/commonMain/kotlin/com/nimblygames/enghub/viewmodel/NotificationActionController.kt`, `component/NotificationActions.kt`.
**Scope:** Approval only; To-Do auto-dismissal is separate unless required for the action to complete. **Notes:** Route by provider; avoid passing GitLab MR URLs into the GitHub review API in `NotificationActionController.kt`.

### 11. Comment on a GitLab MR
**Acceptance criteria:** Given the MR To-Do, when the user submits a comment review, then that comment appears on the GitLab MR.
**Expected edits:** GitLab API under `utilities/src/commonMain/kotlin/com/github/karlsabo/gitlab/`; `src/commonMain/kotlin/com/nimblygames/enghub/viewmodel/EngHubViewModel.kt`, `component/NotificationItem.kt`.
**Scope:** Comment only. **Notes:** Trace the existing review dialog from `component/NotificationItem.kt` and preserve its GitHub path.

### 12. Request changes on a GitLab MR (conditional)
**Acceptance criteria:** Given the MR To-Do, when Request Changes is chosen, then the MR reflects a verified blocking review outcome.
**Expected edits:** GitLab API under `utilities/src/commonMain/kotlin/com/github/karlsabo/gitlab/`; `src/commonMain/kotlin/com/nimblygames/enghub/viewmodel/EngHubViewModel.kt`, `component/NotificationItem.kt`.
**Scope:** Only if supported on the target instance; do not substitute an ordinary comment. **Notes:** Omit this story if no equivalent exists.

### 13. Set up a worktree from a GitLab To-Do
**Acceptance criteria:** Given an MR To-Do with a fetchable branch, when Setup is chosen, then a local worktree becomes available.
**Expected edits:** `src/commonMain/kotlin/com/nimblygames/enghub/viewmodel/NotificationPolling.kt`, `viewmodel/CheckoutController.kt`, `state/NotificationUiState.kt`.
**Scope:** Setup from inbox, not general MR discovery. **Notes:** Reuse the clone-safe path introduced by story 3; `CheckoutController.kt` currently hardcodes GitHub.

### 14. Clear merged-MR To-Dos
**Acceptance criteria:** Given a To-Do whose MR is merged, when Eng Hub refreshes, then the completed item is cleared.
**Expected edits:** GitLab API under `utilities/src/commonMain/kotlin/com/github/karlsabo/gitlab/`; `src/commonMain/kotlin/com/nimblygames/enghub/viewmodel/NotificationPolling.kt`.
**Scope:** Merged MR cleanup only; no GitHub-specific title-based auto-approve. **Notes:** `https://gitlab.nimblygames.com/NimblyGames/eng-hub/-/blob/main/README.md` documents the existing GitHub merged-PR cleanup; check GitLab state and To-Do semantics before mutating it.

### 15. Approve from the Review dialog
**Acceptance criteria:** Given an MR To-Do, when Approve is submitted in Review, then GitLab records the approval.
**Expected edits:** GitLab API under `utilities/src/commonMain/kotlin/com/github/karlsabo/gitlab/`; `src/commonMain/kotlin/com/nimblygames/enghub/viewmodel/EngHubViewModel.kt`, `component/NotificationItem.kt`.
**Scope:** Dialog approval only. **Notes:** May reuse story 10's provider-routed approval operation; keep distinct user flow/test.

### 16. Identify draft GitLab MRs
**Acceptance criteria:** Given draft `games/atlas!42`, when Eng Hub refreshes, then it is labelled draft.
**Expected edits:** GitLab API under `utilities/src/commonMain/kotlin/com/github/karlsabo/gitlab/`; `src/commonMain/kotlin/com/nimblygames/enghub/state/PullRequestUiState.kt`, `component/PullRequestItem.kt`.
**Scope:** Display only. **Notes:** GitHub UI already uses `isDraft` in `PullRequestUiState.kt`; map GitLab's draft flag accurately.

### 17. See GitHub and GitLab together
**Acceptance criteria:** Given an authored GitHub PR and `games/atlas!42`, when Eng Hub refreshes, then both show with distinct provider labels.
**Expected edits:** `src/commonMain/kotlin/com/nimblygames/enghub/viewmodel/PullRequestPolling.kt`, `state/PullRequestUiState.kt`, `component/PullRequestPanel.kt`.
**Scope:** Simultaneous account operation; separate errors/retries so one provider's failure does not hide the other's items. **Notes:** Existing GitHub polling in `PullRequestPolling.kt` is gated by GitHub readiness; GitLab-only users must also work from story 1. Prefer making coexistence part of story 1 if it can be shown by the same acceptance test; otherwise keep a separate user-visible slice.

### 18. Find a GitLab MR branch from a repository
**Acceptance criteria:** Given a local checkout of `games/atlas`, when its GitLab MR URL is entered in repository worktree discovery, then the matching branch is offered for setup.
**Expected edits:** `src/commonMain/kotlin/com/nimblygames/enghub/viewmodel/RepositoryPullRequestWorktreeDiscovery.kt`, `component/ExistingPullRequestMatch.kt`; GitLab MR reference parsing under `utilities/src/commonMain/kotlin/com/github/karlsabo/gitlab/`.
**Scope:** Same-project MR URL; forks deferred. **Notes:** `RepositoryPullRequestWorktreeDiscovery.kt` parses only GitHub references and rejects fork PRs; use host-aware repository identity, not a number-only match across providers.

### 19. Find a GitLab MR worktree globally
**Acceptance criteria:** Given a local checkout of `games/atlas`, when its GitLab MR URL is entered in global worktree discovery, then the matching worktree is offered.
**Expected edits:** `src/commonMain/kotlin/com/nimblygames/enghub/viewmodel/GlobalExistingWorktreeDiscoveryController.kt`, `component/ExistingPullRequestMatch.kt`; GitLab reference parsing under `utilities/src/commonMain/kotlin/com/github/karlsabo/gitlab/`.
**Scope:** Global worktree discovery only. **Notes:** `GlobalExistingWorktreeDiscoveryController.kt` currently accepts GitHub PR references; reuse the host-aware parsing from story 18.

### 20. Clear closed-MR To-Dos
**Acceptance criteria:** Given a closed but unmerged MR To-Do, when Eng Hub refreshes, then that obsolete item is cleared.
**Expected edits:** GitLab API under `utilities/src/commonMain/kotlin/com/github/karlsabo/gitlab/`; `src/commonMain/kotlin/com/nimblygames/enghub/viewmodel/NotificationPolling.kt`.
**Scope:** Closed-only cleanup, separate from merged-MR cleanup. **Notes:** Existing GitHub cleanup in `https://gitlab.nimblygames.com/NimblyGames/eng-hub/-/blob/main/README.md` covers both states; verify GitLab To-Do behavior before writing.

### 21. Replace an expired GitLab token
**Acceptance criteria:** Given a saved GitLab PAT that has expired, when the user saves a valid replacement PAT in Settings, then authored `games/atlas!42` appears on the next refresh.
**Expected edits:** GitLab token/config code under `utilities/src/commonMain/kotlin/com/github/karlsabo/gitlab/`; `src/commonMain/kotlin/com/nimblygames/enghub/screen/EngHubSettingsScreen.kt`, `viewmodel/EngHubSettingsViewModel.kt`, `EngHubDependencies.kt`, `viewmodel/PullRequestPolling.kt`.
**Scope:** Recover access by manually replacing the PAT; no automatic OAuth refresh or PAT rotation. **Notes:** Explain expired/revoked credentials without exposing token contents. Validate replacement credentials before committing them, then atomically replace the protected secret file and update polling without restarting Eng Hub. Preserve GitHub access. Follow the existing GitHub token-entry/persistence patterns rather than adding an OAuth token lifecycle.

### 22. Disconnect GitLab
**Acceptance criteria:** Given a connected GitLab user, when they disconnect GitLab in Settings, then Eng Hub stops accessing that instance until they reconnect.
**Expected edits:** `src/commonMain/kotlin/com/nimblygames/enghub/screen/EngHubSettingsScreen.kt`, `viewmodel/EngHubSettingsViewModel.kt`, `EngHubDependencies.kt`; GitLab token/config code under `utilities/src/commonMain/kotlin/com/github/karlsabo/gitlab/`.
**Scope:** Local disconnect: cancel GitLab requests and clear persisted credentials; no automatic server-side PAT revocation. **Notes:** Tell the user that local disconnect does not revoke the PAT and that they can revoke it in GitLab. Ensure GitHub remains connected; the current GitHub setup lives in `src/commonMain/kotlin/com/nimblygames/enghub/EngHubDependencies.kt`. Prevent in-flight polling results from repopulating disconnected GitLab state.

### 23. Set up a cross-project GitLab MR
**Acceptance criteria:** Given `games/atlas!42` sourced from `games/atlas-fork`, when Setup is chosen, then Eng Hub checks out the branch from `games/atlas-fork`.
**Expected edits:** GitLab MR API under `utilities/src/commonMain/kotlin/com/github/karlsabo/gitlab/`; `src/commonMain/kotlin/com/nimblygames/enghub/viewmodel/CheckoutController.kt`, `state/PullRequestUiState.kt`.
**Scope:** Cross-project MR branch checkout; permissions to clone source project are required. **Notes:** `CheckoutController.kt` currently constructs a GitHub clone URL from the target repo name; the source repository identity and remote must survive the MR mapping and use existing Git credentials.

**Sequence:** 1 → 21/22 → 2/3/4/5/16/17 → 6 → 7/8/10/11/13/15 → 14/20; 3 → 23; 18 → 19 after MR discovery; 9 and 12 depend on capability verification. Story 17 can be absorbed into story 1 if that acceptance example already demonstrates both providers. Update this mapping after reviewing the tests with the user.

**Progress:** stories 1-2 done (PRs #42, #44); story 2’s acceptance test landed in #44 on top of story 1’s URL mapping. Next: 21/22. Live GitLab 19.5+ verification remains unavailable.
