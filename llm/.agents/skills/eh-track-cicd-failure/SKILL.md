---
name: eh-track-cicd-failure
description: Track why CI/CD is failing for the current or specified PR. Use for "why is CI failing", "track CI failure", or "record CI/CD failure".
user-invocable: true
allowed-tools: all
---

# Track CI/CD Failure

Goal: find the linked Buildkite failure and record the cause in:

`${PLANNING_MARKDOWN_DIR}/cicd-failed.md`

1. Identify the PR: use the provided PR, or run:
   ```bash
   gh pr view --json number,url,title,headRefName,statusCheckRollup
   ```

2. Find the failing Buildkite URL in `statusCheckRollup`. Parse:
    - pipeline from `https://buildkite.com/<org>/<pipeline>/builds/<build>`
    - build number from `/builds/<build>`

3. Load Buildkite:
   ```bash
   bk build view --output json --pipeline "$PIPELINE" "$BUILD_ID"
   ```
   If Buildkite errors about org/access/connectivity, stop and tell the user to connect to VPN. Do not work around it.

4. Inspect failed jobs, annotations, and logs as needed. Focus on `failed` jobs first; many `broken` jobs are downstream skipped/no-agent noise. Useful commands:
   ```bash
   jq -r '.jobs[] | select(.state!="passed" and .state!="skipped" and .state!=null) | [.state,.name,.id,(.exit_status//""),(.raw_log_url//""),(.web_url//"")] | @tsv'
   jq -r '.annotations[]? | [.style,.context,.body_html] | @tsv'
   RAW_LOG_URL=$(jq -r '.jobs[] | select(.id=="'$JOB_ID'") | .raw_log_url')
   curl -fsSL -H "Authorization: Bearer $(yq -r '.organizations."klaviyo-ci".api_token' ~/.config/bk.yaml)" "$RAW_LOG_URL" \
     | perl -pe 's/\e\[[0-9;]*[A-Za-z]//g; s/\e_bk;[^\a]*\a//g' > /tmp/bk-job.log
   rg -n "FAILED|ERROR|Traceback|ReadTimeout|Rate exceeded|throttl|No diff|diff-peek|test summary" /tmp/bk-job.log
   ```

5. Compare against PR files:
   ```bash
   gh pr diff --name-only
   ```
   Classify honestly: PR-caused, likely unrelated, infra, flaky, or generated metadata drift.

6. Append or update this section in that file. Do not duplicate the same PR/date:
   ```markdown
   ## PR #<number>
   ### YYYY-MM-DDTHH:mm:ssZ

   Reason:
   - BuildKite build `<build-id>` failed for PR #<number>.
   - <decisive failure reason, with exact job/error text>
   - <related/unrelated to PR changes, with evidence>

   Build: <build-url>
   ```

7. Final response: 2-5 bullets with the cause and the path updated. Do not fix CI unless asked.
