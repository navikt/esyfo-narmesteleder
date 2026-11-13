# Dialogporten dispatch: cutover and recovery

Dispatch names the delivery component; `reconcile(claim)` converges a claimed
behov's latest state with Dialogporten. There is one control row per behov,
not an immutable command per outgoing request.

The worker is disabled in dev/prod until a controlled cutover.
`DIALOGPORTEN_TASK_ENABLED` no longer starts dialog delivery; it retains its
existing person-enrichment gating. `DIALOG_DISPATCH_ENABLED` starts the
claim worker on every pod. Do not enable it while an old binary can deliver.

## Backfill (V35)

Flyway migration `V35__backfill_dialog_dispatch.sql` runs once, in the
migration transaction, when the first binary that contains it starts:

- Cleanup fulfils open behov (`BEHOV_CREATED` or
  `DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION`) that already have an active,
  non-revoked relation in the same organization, and schedules work for every
  row it fulfils. Fulfillment is not confirmed dialog completion.
- Seeding adds work for legacy rows that have none: open behov, fulfilled
  behov with a dialog, and expired or failed behov whose dialog has not been
  expired. Closed legacy rows without dialog IDs are deliberately excluded.
- A row without a dialog ID that is open when V35 runs is marked
  `create_started`, whether cleanup fulfils it or seeding finds it still open,
  because the legacy task may have attempted its CREATE. If the behov closes
  before the worker creates the dialog, the idempotent key returns an existing
  dialog; otherwise subscribers see a dialog created and then closed. The
  result does not depend on the statement order.

A migration is safe here because every behov writer has scheduled its own
changes in the same transaction since V33, so the backfill covers only rows
last changed before that, and the worker stays disabled until the cutover.
Before the cutover binary, the legacy tasks keep completing fulfilled behov
that have a dialog; other scheduled work waits for the worker. A relation
that the projection has not consumed at deploy time leaves its behov open for
normal fulfillment.
If V35 fails, for example as a deadlock victim, Flyway rolls it back in full
and the pod fails startup; old pods keep serving and the next start retries.
V35 is not a routine producer or replay command.

After the deploy, confirm that V35 succeeded in `flyway_schema_history` and
that this read-only count returns zero:

```sql
SELECT count(*) AS unscheduled
FROM nl_behov b
WHERE NOT EXISTS (SELECT 1 FROM dialog_dispatch q WHERE q.behov_id = b.id)
  AND (b.behov_status = 'BEHOV_CREATED'
       OR (b.behov_status = 'BEHOV_FULFILLED' AND b.dialog_id IS NOT NULL)
       OR (b.behov_status IN ('BEHOV_EXPIRED', 'ERROR')
           AND b.dialog_id IS NOT NULL AND b.expired_in_dialogporten IS NULL));
```

## Cutover

1. Deploy the new binary with dispatch disabled. All behov changes
   that affect dialog state explicitly call `scheduleDialogDispatch` in the
   repository writer's existing transaction; V33 contains no scheduling trigger.
   Wait until every pod uses the new binary and all old requests/jobs have
   drained. The old flag alone does not stop old inline delivery.
2. Confirm that V35 has been applied and that the unscheduled count in
   [Backfill (V35)](#backfill-v35) still returns zero. Ensure no older
   binary can resume processing.
3. Inspect the remaining backlog and known ambiguous historical rows using
   approved database access. Do not export identifiers, payloads or names to
   monitoring. There is no automatic CREATE replay for unidentified closed
   historical dialogs.
4. Verify `team-esyfo`'s Slack destination in Nais Console. Enable repository
   variable `DIALOG_DISPATCH_ALERTS_ENABLED=true` to deploy the checked-in
   rules through CI, in both environments. Rule tests must pass before this.
   Confirm the rules are loaded in Nais Console/Alertmanager before enabling
   delivery. Sending a test notification requires separate approval.
5. In dev, enable `DIALOG_DISPATCH_ENABLED`, verify outcomes and lease
   recovery, and measure latency/downstream load. Enable prod only after
   those checks, including service-owner GET read-back after an applied expiry
   has passed. A 404/410 must remain retryable, not inferred as success.
   Initially there is one active reconciliation per pod:
   10 attempts or 20 seconds per run, a 20-second per-attempt limit, a
   60-second lease and five-second safety margin. Waiting/retries consume
   these budgets. Polling waits five seconds between completed runs.

## Alert response and replay

Run `sh scripts/check-dialog-alerts.sh` (Docker and Ruby required) to validate
the rule syntax, firing delays, resolution and missing-telemetry behavior.
CI runs the same check before alert deployment.

The warning for parked work fires after five minutes. Pending work older than
five minutes must remain so for another five minutes to alert. Age is measured
from the first unresolved change, not the most recent retry; backoff cannot
conceal a stuck behov. Queue gauges are database snapshots repeated per pod:
use `max`, not `sum`, for counts. The oldest-pending timestamp uses `min`
over nonzero samples. A stale worker snapshot has a separate warning.

Check worker enabled/heartbeat, database access and downstream status first.
The attempt counters distinguish done, retry and parked. Completion means
the claimed attempt was handled, not exactly-once remote delivery or an
acknowledgement of newer revisions.
Stored failure reasons are bounded technical categories without raw errors.

Validation/domain responses from Dialogporten (400/422) are parked for operator
correction. Authentication/configuration failures (401/403), not-found/removed
dialogs (404/410), conflicts (409/412), 429, server errors, decoding failures and
unknown outcomes remain retryable and visible through queue age. A 404 alone
does not prove an expiry was applied. Correct the underlying problem before
replay; do not blindly reset a queue during an upstream incident.

Replay selected parked rows using approved database access in a transaction.
Replace the bound UUID through a prepared statement; inspect the target first.
No new HTTP admin endpoint is introduced.

```sql
UPDATE dialog_dispatch
SET status = 'READY', revision = revision + 1,
    available_at = clock_timestamp(), attempts = 0, last_failure = NULL
WHERE behov_id = ? AND status = 'PARKED' AND claim_token IS NULL;
```

Expect exactly one affected row. Preserve `create_started`, intended expiry,
the original pending timestamp and the stable behov/idempotency identity.
Observe reconciliation before replaying another group. A concurrent new
domain revision independently wakes parked work through the repository's
transactional scheduling call.

## Rollback

Disable new workers on every pod and wait for them to stop, outstanding calls
to drain and the last 60-second lease plus safety margin to expire. Restore
old processing only after no new worker can resume. Retain the additive table
and the V33 and V35 migrations. Binaries since V33 schedule their own changes,
so returning to one needs no backfill. After running a binary older than V33,
rerun both V35 statements in one transaction with approved database access
before enabling the new workers again; they are idempotent.
Do not roll schema history back or delete claims to force concurrent work.
Repeat the non-overlapping cutover when returning to the new binary.

## External contract and limitations

The public [Dialogporten OpenAPI](https://platform.altinn.no/dialogporten/swagger/v1/swagger.json)
documents matching-key conflict `DP.CFL-00002` with `dialogId` and
`idempotentKey`, 400 validation errors, 422 domain errors and revision-conflict
412. GET exposes `expiresAt`; retries read it before changing the persisted
expiry. If GET cannot establish an outcome, the worker retries rather than
inventing success.
An observed, unapplied intent too close to expiry is replaced before the
attempt budget and safety margin can make the request invalid.

Claims prevent stale database writes, not stale remote calls. External effects
are at-least-once. A tracked uncertain CREATE may create a now-obsolete dialog
and then close it. Historical closed rows without IDs remain a separately
tracked investigation; local leader cleanup cannot prove upstream closure.
V35 marks open rows without a dialog ID only when it runs. A later legacy
CREATE whose behov closes before the worker creates the dialog is not tracked,
like other closed rows without dialog IDs.
The sub-30-second target applies to healthy normal load, not backlog recovery.

Local plan checks used PostgreSQL 18.4, one million synthetic behov/control
rows and 50,000 synthetic relations. Sparse due work, a 10,000-row due backlog
and a locked first row used the partial due index (approximately 0.03–0.07 ms).
The active-only queue aggregate used an index-only scan (approximately 0.9 ms).
Earlier 500-row cleanup and seeding batches took approximately 60 ms and
116 ms with the trigger-based scheduler. V35 runs one unbatched pass with
explicit scheduling, covered by database tests; those timings do not
establish its duration.
Fixed queue-status literals preserve the partial-index plan with generic
prepared statements; parameterizing these constants had caused a full scan.
These local timings are query-plan evidence, not production capacity or
end-to-end latency proof.
