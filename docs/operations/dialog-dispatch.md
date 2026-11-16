# Dialogporten dispatch: alerts and replay

The `dialog-dispatch` worker runs on every pod and reconciles the latest state
of each claimed behov with Dialogporten. There is no runtime switch: stopping
delivery takes a code change and a deploy.

## Alerts

CI validates the rules in `nais/alerts.yaml` with
`scripts/check-dialog-alerts.sh` and deploys them to prod from `main`. Dev has
no rules; evaluate the expressions against dev metrics in Prometheus or
Grafana.

| Alert | Fires when |
| --- | --- |
| `DialogDispatchParked` | At least one row has been `PARKED` for five minutes. See [Failures](#failures) and [Replay](#replay). |
| `DialogDispatchOverdue` | The oldest pending change has been older than five minutes for another five minutes. Age counts from the first unresolved change, so retries cannot hide a stuck behov. |
| `DialogDispatchWorkerStalled` | No pod has refreshed its queue snapshot for 90 seconds, for five minutes. |
| `DialogDispatchTelemetryMissing` | No pod has published the worker heartbeat for five minutes: pods are down, the worker never started, or Prometheus is not scraping. |

Queue gauges are database snapshots repeated per pod: use `max`, not `sum`,
for counts. Check the worker log, database access and Dialogporten status
first.

## Failures

Validation and domain errors from Dialogporten (400/422) park the row until an
operator corrects the cause. Everything else retries and shows up as queue
age: 401/403, 404/410, 409/412, 429, server errors, decoding failures and
unknown outcomes. A 404 alone does not prove an expiry was applied.
`last_failure` stores a bounded technical category, not the raw error.

Correct the underlying problem before replay. Do not reset the queue during an
upstream incident.

## Replay

Replay selected parked rows using approved database access in a transaction.
Inspect the target first, and bind the behov UUID through a prepared
statement.

```sql
UPDATE dialog_dispatch
SET status = 'READY', revision = revision + 1,
    available_at = clock_timestamp(), attempts = 0, last_failure = NULL
WHERE behov_id = ? AND status = 'PARKED' AND claim_token IS NULL;
```

Expect exactly one affected row. Do not change `create_started`, the intended
expiry, the pending timestamp or the behov ID, which is the idempotency key.
Watch the row reconcile before replaying the next group. A new change to the
behov also wakes parked work.
