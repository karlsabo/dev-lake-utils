# GitLab support

## Goal

Support self-managed GitLab alongside GitHub in dev-lake-utils, beginning with authored open merge requests in Eng Hub.

## Context

- Request: “add support for gitlab”; planning artifact belongs in `/home/karl/git/dev-lake-utils/plans`.
- `/home/karl/git/dev-lake-utils/eng-hub/README.md` describes a GitHub-only desktop experience for pull requests and notifications, including browser, setup, review, and notification actions.
- `/home/karl/git/dev-lake-utils/eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHubConfig.kt` stores GitHub author and organization settings; `/home/karl/git/dev-lake-utils/eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHubComponent.kt` wires GitHub API services.
- `/home/karl/git/dev-lake-utils/utilities/src/commonMain/kotlin/com/github/karlsabo/github/` contains GitHub integration; no corresponding GitLab package was found under `utilities/src/commonMain/kotlin/com/github/karlsabo/`.
- Decisions: GitLab must be self-managed; keep GitHub working alongside GitLab, with provider identification. Start with a read-only authored-open-merge-request view in Eng Hub, then expand toward GitHub feature parity. These choices avoid migrating existing users and make the first slice independently demonstrable.
- `/home/karl/git/dev-lake-utils/eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/PullRequestPolling.kt` polls authored GitHub PRs and fetches CI/review summaries; `/home/karl/git/dev-lake-utils/eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/NotificationActionController.kt` implements GitHub notification write actions.
- Scope decision: only Eng Hub, not the publishers, agent skills, or release automation. `/home/karl/git/dev-lake-utils/summary-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/tools/SummaryPublisherDependencies.kt` and `/home/karl/git/dev-lake-utils/user-metrics-publisher/src/commonMain/kotlin/com/github/karlsabo/devlake/metrics/UserMetricPublisherDependencies.kt` inject GitHub PR APIs but are out of scope.
- One configurable HTTPS self-managed instance; authored MRs from all projects visible to the account; GitHub and GitLab appear side by side. Do not hardcode a host. Start with GitLab To-Dos as the inbox analogue; do not port the GitHub-specific auto-approve rule without an explicit request.
- OAuth decision: Eng Hub has an in-app Connect to GitLab button that opens the system browser and completes authorization-code + PKCE with a loopback callback. Register a per-instance OAuth app configured for desktop public clients; ship no client secret. Securely persist access/refresh tokens separately from GitHub, validate state/redirect URI/scopes, refresh expired tokens, and do not display them. Existing GitHub credentials are stored via config and a separate secret file in `utilities/src/commonMain/kotlin/com/github/karlsabo/github/config/GitHubConfig.kt` and `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubGitHubTokenController.kt`; that manual-token flow does not have OAuth. Verify the actual instance permits registration and the required redirect/PKCE configuration before implementation; user approval of this approach does not confirm registration has already happened.
- `/home/karl/git/dev-lake-utils/eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/state/PullRequestUiState.kt` and `NotificationUiState.kt` hold GitHub-shaped display models; `/home/karl/git/dev-lake-utils/eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/CheckoutController.kt` hardcodes github.com clone URLs. GitLab host/project identity must be retained through UI actions to avoid cloning the wrong repository or conflating GitHub and GitLab items.
- Target GitLab version: 19.5 or higher. Use equivalent observable outcomes, not endpoint-by-endpoint parity. Omit Unsubscribe/Request Changes on GitLab unless their effects can be verified on the target instance; don't mislabel a dismissed To-Do or ordinary comment. Use existing Git credentials/SSH remotes for private-project worktrees, never embed an OAuth token in clone URLs or logs; cross-project MRs are a separate slice. GitHub-specific auto-approve remains out of scope.
- Validation prerequisites: obtain a test GitLab 19.5+ instance with a registered public OAuth application and verify PKCE, loopback redirects, and To-Do/review API behavior. If the instance blocks OAuth registration, revisit authentication with the user rather than silently replacing OAuth with a PAT.

## Open Questions

1. **OAuth registration:** Who will register the OAuth application on the self-managed GitLab instance and provide its client ID plus approved loopback redirect URI? Is there a non-production instance/account for testing?
   - Recommendation: Have an instance admin register one public-client app and provide a non-secret client ID and permitted loopback URI; use a test account before connecting real data. Eng Hub cannot complete sign-in without a registration.
   - Answer: awaiting confirmation.
2. **Token storage:** Should GitLab OAuth tokens use the existing protected secret-file approach or OS credential storage (keychain/credential manager)?
   - Recommendation: Reuse the repo's protected secret-file pattern initially, with restrictive permissions and atomic rotation of the refresh token; it is cross-platform and matches current GitHub behavior, but offers weaker at-rest protection than an OS credential store.
   - Answer: awaiting confirmation.

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
21. Given a user connected to GitLab yesterday whose OAuth access token expired, when Eng Hub refreshes, then authored `games/atlas!42` still appears without another sign-in.
22. Given a connected GitLab user, when they disconnect GitLab in Settings, then Eng Hub stops accessing that instance until they reconnect.
23. Given a cross-project MR `games/atlas!42` whose source branch lives in `games/atlas-fork`, when the user chooses Setup, then Eng Hub checks out the source branch from `games/atlas-fork`.

The first slice is story 1. Story 17 tests coexistence explicitly rather than assuming it from a GitLab-only list test; GitHub regression coverage belongs in every affected PR.

## Stories (proposed: one ticket and one PR each)

Paths below are relative to `/home/karl/git/dev-lake-utils/`. Each acceptance criterion is the same-numbered test above. GitLab API code should live under `utilities/src/commonMain/kotlin/com/github/karlsabo/gitlab/` (new); tests alongside the corresponding module's existing tests. All stories preserve GitHub behavior; feature-specific adapters can be introduced when needed rather than replacing GitHub interfaces wholesale.

### 1. See authored GitLab merge requests
**Acceptance criteria:** Given an account on `gitlab.internal.example` that authored open `games/atlas!42`, when Eng Hub refreshes, then `!42` appears identified as GitLab.
**Expected edits:** `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHubConfig.kt`, `EngHubDependencies.kt`, `screen/EngHubSettingsScreen.kt`, `viewmodel/PullRequestPolling.kt`, `state/PullRequestUiState.kt`, `component/PullRequestItem.kt`; new GitLab config/API under `utilities/src/commonMain/kotlin/com/github/karlsabo/gitlab/`; `eng-hub/README.md`.
**Scope:** One HTTPS instance, all visible authored MRs; no CI/reviews, browser, setup, or inbox action yet. Existing GitHub settings must still work. **Notes:** Current wiring is GitHub-only in `eng-hub/.../EngHubDependencies.kt` and polling is GitHub-only in `eng-hub/.../viewmodel/PullRequestPolling.kt`. Prefer an in-app OAuth Connect flow with safe token storage as part of this first end-to-end slice, subject to OAuth availability/UX confirmation. Do not expose secrets in logs. If this slice exceeds a few days, narrow the test to connecting and displaying one MR, not a standalone OAuth-infrastructure story.

### 2. Open a GitLab MR in the browser
**Acceptance criteria:** Given `games/atlas!42` in Eng Hub, when Open in Browser is chosen, then its self-managed MR URL opens.
**Expected edits:** `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/component/PullRequestItem.kt`, `state/PullRequestUiState.kt`.
**Scope:** URL action only. **Notes:** Preserve the API-provided web URL; do not construct a github.com URL. The current display model is in `eng-hub/.../state/PullRequestUiState.kt`.

### 3. Set up a GitLab MR worktree
**Acceptance criteria:** Given `games/atlas!42` with a fetchable source branch, when Setup is chosen, then its branch is checked out into a local worktree.
**Expected edits:** `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/CheckoutController.kt`, `state/PullRequestUiState.kt`, `component/PullRequestItem.kt`.
**Scope:** Same-project MR first; cross-project MR is story 23. **Notes:** `CheckoutController.kt` hardcodes `https://github.com/$repoFullName.git`; carry host/clone URL and provider identity through setup; use existing Git credentials/SSH remotes, never put OAuth tokens in clone URLs.

### 4. Show GitLab pipeline status
**Acceptance criteria:** Given a failing pipeline on `games/atlas!42`, when Eng Hub refreshes, then the MR shows failing CI status.
**Expected edits:** GitLab API under `utilities/src/commonMain/kotlin/com/github/karlsabo/gitlab/`; `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/PullRequestPolling.kt`, `state/PullRequestUiState.kt`.
**Scope:** Status display, not pipeline controls. **Notes:** GitHub enrichment currently happens in `PullRequestPolling.kt`; adapt GitLab pipeline state independently.

### 5. Show GitLab approval progress
**Acceptance criteria:** Given `games/atlas!42` awaiting an approval, when Eng Hub refreshes, then its review summary shows the outstanding approval state.
**Expected edits:** GitLab API under `utilities/src/commonMain/kotlin/com/github/karlsabo/gitlab/`; `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/PullRequestPolling.kt`, `state/PullRequestUiState.kt`.
**Scope:** Read-only approval summary. **Notes:** `PullRequestUiState.kt` formats GitHub requested/approved counts; use GitLab's actual approval rules rather than inventing reviewer counts.

### 6. See GitLab To-Dos
**Acceptance criteria:** Given a GitLab To-Do for `games/atlas!42`, when Eng Hub refreshes notifications, then the item appears identified as GitLab.
**Expected edits:** GitLab API under `utilities/src/commonMain/kotlin/com/github/karlsabo/gitlab/`; `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/NotificationPolling.kt`, `state/NotificationUiState.kt`, `component/NotificationItem.kt`.
**Scope:** Read-only To-Dos first, no actions. **Notes:** `NotificationPolling.kt` processes GitHub notifications; retain provider and instance identity so ignored-thread IDs cannot collide across systems.

### 7. Open a GitLab To-Do
**Acceptance criteria:** Given the MR To-Do, when Open is chosen, then its GitLab target opens in the browser.
**Expected edits:** `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/state/NotificationUiState.kt`, `component/NotificationItem.kt`.
**Scope:** Browser action only. **Notes:** Existing `NotificationUiState.kt` converts GitHub API URLs; GitLab should use the target's actual web URL.

### 8. Mark a GitLab To-Do done
**Acceptance criteria:** Given the MR To-Do, when Done is chosen, then it disappears from the inbox after refresh.
**Expected edits:** GitLab API under `utilities/src/commonMain/kotlin/com/github/karlsabo/gitlab/`; `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/NotificationActionController.kt`, `viewmodel/IgnoredNotificationPersistence.kt`.
**Scope:** Mark done only. **Notes:** `NotificationActionController.kt` currently invokes GitHub notification APIs; preserve provider-specific IDs in persistence and retry behavior.

### 9. Stop GitLab To-Dos reappearing (conditional)
**Acceptance criteria:** Given the MR To-Do, when the user chooses a verified unsubscribe equivalent, then notifications for that MR stop reappearing.
**Expected edits:** GitLab API under `utilities/src/commonMain/kotlin/com/github/karlsabo/gitlab/`; `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/NotificationActionController.kt`, `component/NotificationActions.kt`.
**Scope:** Only if the target instance supports an equivalent outcome; otherwise revisit/omit this story. **Notes:** GitHub Unsubscribe currently uses `NotificationActionController.kt`; dismissing one To-Do is not equivalent to unsubscribing. Omit this story if no equivalent exists.

### 10. Approve a GitLab MR from the inbox
**Acceptance criteria:** Given the MR To-Do, when Approve is chosen, then GitLab records the approval.
**Expected edits:** GitLab API under `utilities/src/commonMain/kotlin/com/github/karlsabo/gitlab/`; `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/NotificationActionController.kt`, `component/NotificationActions.kt`.
**Scope:** Approval only; To-Do auto-dismissal is separate unless required for the action to complete. **Notes:** Route by provider; avoid passing GitLab MR URLs into the GitHub review API in `NotificationActionController.kt`.

### 11. Comment on a GitLab MR
**Acceptance criteria:** Given the MR To-Do, when the user submits a comment review, then that comment appears on the GitLab MR.
**Expected edits:** GitLab API under `utilities/src/commonMain/kotlin/com/github/karlsabo/gitlab/`; `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModel.kt`, `component/NotificationItem.kt`.
**Scope:** Comment only. **Notes:** Trace the existing review dialog from `component/NotificationItem.kt` and preserve its GitHub path.

### 12. Request changes on a GitLab MR (conditional)
**Acceptance criteria:** Given the MR To-Do, when Request Changes is chosen, then the MR reflects a verified blocking review outcome.
**Expected edits:** GitLab API under `utilities/src/commonMain/kotlin/com/github/karlsabo/gitlab/`; `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModel.kt`, `component/NotificationItem.kt`.
**Scope:** Only if supported on the target instance; do not substitute an ordinary comment. **Notes:** Omit this story if no equivalent exists.

### 13. Set up a worktree from a GitLab To-Do
**Acceptance criteria:** Given an MR To-Do with a fetchable branch, when Setup is chosen, then a local worktree becomes available.
**Expected edits:** `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/NotificationPolling.kt`, `viewmodel/CheckoutController.kt`, `state/NotificationUiState.kt`.
**Scope:** Setup from inbox, not general MR discovery. **Notes:** Reuse the clone-safe path introduced by story 3; `CheckoutController.kt` currently hardcodes GitHub.

### 14. Clear merged-MR To-Dos
**Acceptance criteria:** Given a To-Do whose MR is merged, when Eng Hub refreshes, then the completed item is cleared.
**Expected edits:** GitLab API under `utilities/src/commonMain/kotlin/com/github/karlsabo/gitlab/`; `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/NotificationPolling.kt`.
**Scope:** Merged MR cleanup only; no GitHub-specific title-based auto-approve. **Notes:** `eng-hub/README.md` documents the existing GitHub merged-PR cleanup; check GitLab state and To-Do semantics before mutating it.

### 15. Approve from the Review dialog
**Acceptance criteria:** Given an MR To-Do, when Approve is submitted in Review, then GitLab records the approval.
**Expected edits:** GitLab API under `utilities/src/commonMain/kotlin/com/github/karlsabo/gitlab/`; `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/EngHubViewModel.kt`, `component/NotificationItem.kt`.
**Scope:** Dialog approval only. **Notes:** May reuse story 10's provider-routed approval operation; keep distinct user flow/test.

### 16. Identify draft GitLab MRs
**Acceptance criteria:** Given draft `games/atlas!42`, when Eng Hub refreshes, then it is labelled draft.
**Expected edits:** GitLab API under `utilities/src/commonMain/kotlin/com/github/karlsabo/gitlab/`; `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/state/PullRequestUiState.kt`, `component/PullRequestItem.kt`.
**Scope:** Display only. **Notes:** GitHub UI already uses `isDraft` in `PullRequestUiState.kt`; map GitLab's draft flag accurately.

### 17. See GitHub and GitLab together
**Acceptance criteria:** Given an authored GitHub PR and `games/atlas!42`, when Eng Hub refreshes, then both show with distinct provider labels.
**Expected edits:** `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/PullRequestPolling.kt`, `state/PullRequestUiState.kt`, `component/PullRequestPanel.kt`.
**Scope:** Simultaneous account operation; separate errors/retries so one provider's failure does not hide the other's items. **Notes:** Existing GitHub polling in `PullRequestPolling.kt` is gated by GitHub readiness; GitLab-only users must also work from story 1. Prefer making coexistence part of story 1 if it can be shown by the same acceptance test; otherwise keep a separate user-visible slice.

### 18. Find a GitLab MR branch from a repository
**Acceptance criteria:** Given a local checkout of `games/atlas`, when its GitLab MR URL is entered in repository worktree discovery, then the matching branch is offered for setup.
**Expected edits:** `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/RepositoryPullRequestWorktreeDiscovery.kt`, `component/ExistingPullRequestMatch.kt`; GitLab MR reference parsing under `utilities/src/commonMain/kotlin/com/github/karlsabo/gitlab/`.
**Scope:** Same-project MR URL; forks deferred. **Notes:** `RepositoryPullRequestWorktreeDiscovery.kt` parses only GitHub references and rejects fork PRs; use host-aware repository identity, not a number-only match across providers.

### 19. Find a GitLab MR worktree globally
**Acceptance criteria:** Given a local checkout of `games/atlas`, when its GitLab MR URL is entered in global worktree discovery, then the matching worktree is offered.
**Expected edits:** `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/GlobalExistingWorktreeDiscoveryController.kt`, `component/ExistingPullRequestMatch.kt`; GitLab reference parsing under `utilities/src/commonMain/kotlin/com/github/karlsabo/gitlab/`.
**Scope:** Global worktree discovery only. **Notes:** `GlobalExistingWorktreeDiscoveryController.kt` currently accepts GitHub PR references; reuse the host-aware parsing from story 18.

### 20. Clear closed-MR To-Dos
**Acceptance criteria:** Given a closed but unmerged MR To-Do, when Eng Hub refreshes, then that obsolete item is cleared.
**Expected edits:** GitLab API under `utilities/src/commonMain/kotlin/com/github/karlsabo/gitlab/`; `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/NotificationPolling.kt`.
**Scope:** Closed-only cleanup, separate from merged-MR cleanup. **Notes:** Existing GitHub cleanup in `eng-hub/README.md` covers both states; verify GitLab To-Do behavior before writing.

### 21. Keep the OAuth connection alive
**Acceptance criteria:** Given a user connected to GitLab yesterday whose OAuth access token expired, when Eng Hub refreshes, then authored `games/atlas!42` still appears without another sign-in.
**Expected edits:** GitLab token/config code under `utilities/src/commonMain/kotlin/com/github/karlsabo/gitlab/`; `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/EngHubDependencies.kt`, `viewmodel/PullRequestPolling.kt`.
**Scope:** Refresh and securely persist rotated tokens; require reconnect if refresh is revoked. **Notes:** GitHub config in `utilities/src/commonMain/kotlin/com/github/karlsabo/github/config/GitHubConfig.kt` has no refresh-token lifecycle. Do not log token response bodies; handle concurrent refresh safely.

### 22. Disconnect GitLab
**Acceptance criteria:** Given a connected GitLab user, when they disconnect GitLab in Settings, then Eng Hub stops accessing that instance until they reconnect.
**Expected edits:** `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/screen/EngHubSettingsScreen.kt`, `viewmodel/EngHubSettingsViewModel.kt`, `EngHubDependencies.kt`; GitLab token/config code under `utilities/src/commonMain/kotlin/com/github/karlsabo/gitlab/`.
**Scope:** Local disconnect, clear persisted credentials; revoke token if supported by the instance. **Notes:** Ensure GitHub remains connected; the current GitHub setup lives in `eng-hub/.../EngHubDependencies.kt`.

### 23. Set up a cross-project GitLab MR
**Acceptance criteria:** Given `games/atlas!42` sourced from `games/atlas-fork`, when Setup is chosen, then Eng Hub checks out the branch from `games/atlas-fork`.
**Expected edits:** GitLab MR API under `utilities/src/commonMain/kotlin/com/github/karlsabo/gitlab/`; `eng-hub/src/commonMain/kotlin/com/github/karlsabo/devlake/enghub/viewmodel/CheckoutController.kt`, `state/PullRequestUiState.kt`.
**Scope:** Cross-project MR branch checkout; permissions to clone source project are required. **Notes:** `CheckoutController.kt` currently constructs a GitHub clone URL from the target repo name; the source repository identity and remote must survive the MR mapping and use existing Git credentials.

**Sequence:** 1 → 21/22 → 2/3/4/5/16/17 → 6 → 7/8/10/11/13/15 → 14/20; 3 → 23; 18 → 19 after MR discovery; 9 and 12 depend on capability verification. Story 17 can be absorbed into story 1 if that acceptance example already demonstrates both providers. Update this mapping after reviewing the tests with the user.
