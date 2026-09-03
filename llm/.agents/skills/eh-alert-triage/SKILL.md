---
name: eh-alert-triage
description: Triage production alerts/incidents by correlating incident management, documentation, chat, observability, logs, and local repositories. Use for alert investigation, root-cause discovery, latency/error spikes, and dashboard discrepancies.
user-invocable: true
allowed-tools: all
---

# Alert Triage

Goal: identify what happened, establish blast radius, and maintain a durable evidence log. Prefer evidence over hypotheses; separate confirmed facts from possible causes.

## Start

1. Create a running log at `${PLANNING_MARKDOWN_DIR}/alert-triage/{descriptive-name}.md` and keep updating it. Choose `{descriptive-name}` as a short kebab-case alert or incident name, such as `checkout-api-latency-2025-04-10`. Create the `alert-triage` directory if needed.
2. Fix the exact UTC window and alert metric. Convert epochs with `date -u -r <seconds>` or Python; Grafana/Chronosphere links use epoch **milliseconds**.
3. Identify the measurement boundary: caller, gateway, service handler, queue, database, or external provider. Do not compare unlike metrics.

## Where to look

${ALERT_TRIAGE_WHERE_TO_LOOK}

## Correlate evidence

1. Plot alert metric plus caller/internal-client errors, downstream handler latency/errors, traffic volume, replica counts, CPU, connection metrics, pod restarts/readiness, and deployments/change events.
2. Look for exact timing fingerprints: scheduled waves, retries, timeout constants, batch intervals, cron boundaries, deploys, ASG/HPA transitions, and load-test start/stop.
3. Use logs to classify exceptions (`ReadTimeout`, `ConnectTimeout`, `PoolTimeout`) and determine whether errors are synthesized by a client or returned by a server.
4. Check caller retries: successful retries can inflate caller endpoint p99 while downstream dashboards stay healthy.
5. Validate alternatives explicitly: pod restarts, network allowance exhaustion, downstream slowness, single-host/client issues, and traffic spikes.

## Finish

Update the log with a shareable conclusion, evidence links, confidence, remaining uncertainty, and concrete follow-ups. Use exact UTC timestamps and verified fixed links.
