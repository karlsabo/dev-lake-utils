---
name: eh-alert-triage
description: Triage production alerts/incidents by correlating incident management, documentation, chat, observability, logs, and local repositories. Use for alert investigation, root-cause discovery, latency/error spikes, and dashboard discrepancies.
user-invocable: true
allowed-tools: all
---

# Alert Triage

Goal: identify what happened, establish blast radius, and maintain a durable evidence log. Prefer evidence over hypotheses; separate confirmed facts from possible causes.

## Start

1. Create a running log at `${PLANNING_MARKDOWN_DIR}/alert-triage/{descriptive-name}/{descriptive-name}.md`. Choose a short kebab-case name, such as `checkout-api-latency-2025-04-10`. Create the directory if needed.
2. Use the log as the investigation notepad and source of continuity. Update it after each step with what you checked, evidence or links found, conclusions, open questions, and the next action. Keep it concise but sufficient to resume if context is cleared; reread it before continuing after any interruption.
3. Fix the exact UTC window and alert metric. Convert epochs with `date -u -r <seconds>` or Python; Grafana/Chronosphere links use epoch **milliseconds**.
4. Identify the measurement boundary: caller, gateway, service handler, queue, database, or external provider. Do not compare unlike metrics.

## Where to look

${ALERT_TRIAGE_WHERE_TO_LOOK}

If browser tools encounter an authentication wall, notify the user and wait for them to authenticate in the affected tool before continuing. Do not attempt to bypass the authentication wall.

## Correlate evidence

1. Plot the alert metric and relevant correlated signals: caller/internal-client errors, downstream latency/errors, traffic, replicas, CPU, connections, pod health, and change events.
2. Look for exact timing fingerprints: scheduled waves, retries, timeout constants, batch intervals, cron boundaries, deploys, ASG/HPA transitions, and load-test start/stop.
3. Use logs to classify exceptions (`ReadTimeout`, `ConnectTimeout`, `PoolTimeout`) and determine whether errors are synthesized by a client or returned by a server.
4. Check caller retries: successful retries can inflate caller endpoint p99 while downstream dashboards stay healthy.
5. Validate alternatives explicitly: pod restarts, network allowance exhaustion, downstream slowness, single-host/client issues, and traffic spikes.

## Finish

Stop when impact, measurement boundary, likely cause, and confidence are established, or further progress requires another owner or system.

Add a short, skimmable section. Keep evidence links in separate bullets and embed screenshots directly in the summary:

```markdown
## Summary
- **Impact:**
- **Cause:**
- **Confidence:**
- **Evidence:**
  - [Fixed-range dashboard](...)
  - [Fixed-range logs](...)
  - [Relevant owner/entity page](...)
  - [Source instrumentation](...)
- **Follow-ups:**

### Screenshots
![Dashboard evidence](./dashboard.png)
![Log evidence](./logs.png)
```

Use exact UTC timestamps and fixed-range links. Save screenshots beside the log; include ownership/entity links when they establish blast radius.
