# Kafka topic: `team-esyfo.syfo-narmesteleder-leesah`

Event stream of narmesteleder relations (the connection between an employee
and their narmeste leder in an organization). Each record holds the full,
current state of one relation, so the latest record per key is the current
state of that relation.

team-esyfo owns this topic and its contract. The narmesteleder domain is being
taken over from team-sykmelding, and this topic replaces
`teamsykmelding.syfo-narmesteleder-leesah` as the source of truth for
narmesteleder relations. New consumers should read this topic.

For now the records are still produced by `navikt/narmesteleder` and
mirrored here; see [Transition](#transition-mirroring-from-team-sykmelding).

## Overview

| Property | Value |
|---|---|
| Full name | `team-esyfo.syfo-narmesteleder-leesah` |
| Owner | team-esyfo |
| Producer | `esyfo-narmesteleder` |
| Manifests | [`nais/topics/syfo-narmesteleder-leesah-dev.yaml`](../../nais/topics/syfo-narmesteleder-leesah-dev.yaml), [`nais/topics/syfo-narmesteleder-leesah-prod.yaml`](../../nais/topics/syfo-narmesteleder-leesah-prod.yaml) |
| Partitions | 3 |
| Cleanup policy | `compact`, with unlimited retention |
| Compaction lag | min ~31 days, max ~35 days |
| Key | `narmesteLederId` as a string (UUID) |
| Value | UTF-8 JSON, see [schema](syfo-narmesteleder-leesah.schema.json), or `null` (tombstone) |

## Access

Access is granted in the topic manifests. Ask team-esyfo to be added.

| Application | Access | dev | prod |
|---|---|---|---|
| `esyfo-narmesteleder` | readwrite | ✅ | ✅ |
| `dinesykmeldte-backend` | read | ✅ | ✅ |
| `esyfo-kafka-manager` | read | ✅ | — |

## Record

### Key

The `narmesteLederId` of the relation, as a UUID string. All records for a
relation have the same key and therefore land on the same partition.

### Value

A JSON object as described by
[`syfo-narmesteleder-leesah.schema.json`](syfo-narmesteleder-leesah.schema.json)
(JSON Schema 2020-12). All properties are always present; nullable properties
are written as `null`. Properties may be added without notice, so consumers
must ignore unknown properties.

| Field | Type | Description |
|---|---|---|
| `narmesteLederId` | UUID string | Id of the relation. Same as the key. |
| `fnr` | string, 11 digits | Identity number of the employee. |
| `orgnummer` | string, 9 digits | Organization number of the employer. |
| `narmesteLederFnr` | string, 11 digits | Identity number of the narmeste leder. |
| `narmesteLederTelefonnummer` | string, max 255 | Phone number of the narmeste leder. |
| `narmesteLederEpost` | string, max 255 | Email address of the narmeste leder. |
| `aktivFom` | date (`YYYY-MM-DD`) | First day the relation is active. |
| `aktivTom` | date or `null` | Last day the relation is active. `null` while it is active. |
| `arbeidsgiverForskutterer` | boolean or `null` | Whether the employer continues to pay salary during sick leave. |
| `timestamp` | ISO-8601 date-time with offset | When the record was published (UTC). |
| `status` | string or `null` | Where the latest change came from, see below. |

`status` is set from the source of the change. A relation that is
deactivated because another leder is registered gets the status of that
registration's source. Values produced today:

| Value | Set when |
|---|---|
| `NY_LEDER` | The relation was created or updated and is active. |
| `DEAKTIVERT_ARBEIDSTAKER` | The change came from the employee. |
| `DEAKTIVERT_ARBEIDSTAKER_INNSENDT_SYKMELDING` | The employee deactivated the relation when submitting a sykmelding. |
| `DEAKTIVERT_LEDER` | The change came from the narmeste leder. |
| `DEAKTIVERT_ARBEIDSFORHOLD` | The change came from the employment (arbeidsforhold) check. |
| `DEAKTIVERT_NY_LEDER` | A new leder was registered by a personalleder or an LPS (system vendor). |
| `IDENTENDRING` | The change came from an identity change in PDL. |
| `DEAKTIVERT_PERSONALLEDER` | A personalleder deactivated the relation. |
| `DEAKTIVERT_LPS` | An LPS deactivated the relation. |
| `null` | The source was a user without a specific status, or was unknown. |

New values may be added. Consumers should map unknown values and `null` to a
fallback instead of failing; `esyfo-narmesteleder` maps them to `UKJENT`.

Example (synthetic data):

```json
{
  "narmesteLederId": "0199b8f1-0cfe-7787-bab8-2fb1cf1b4767",
  "fnr": "12345678910",
  "orgnummer": "123456789",
  "narmesteLederFnr": "10987654321",
  "narmesteLederTelefonnummer": "12345678",
  "narmesteLederEpost": "leder@example.com",
  "aktivFom": "2026-01-15",
  "aktivTom": null,
  "arbeidsgiverForskutterer": true,
  "timestamp": "2026-01-15T09:30:00.123456Z",
  "status": "NY_LEDER"
}
```

### Tombstones

A record with a `null` value is a tombstone for its key. Compaction eventually
removes earlier records for the key. Consumers must accept a `null` value.

## Delivery guarantees

- **At least once.** Consumers must tolerate duplicates. A retry can briefly
  replay older states of a relation after newer ones, but the last record per
  key is always the latest state.
- **Ordered per key.** Records for one relation are published in order.
- **No ordering across keys.**
- **Only validated records.** During the transition, records that fail
  validation are not republished, see
  [what is validated](#what-is-validated). The topic then keeps the previous
  state for that key.

## Personal data

Values contain identity numbers and contact information. Do not log values,
and keep access limited to applications that need it.

## Changing the contract

The schema is checked against `NarmestelederLeesahKafkaMessage` by
`NarmestelederLeesahSchemaTest`. When the message model changes, update the
schema and this page in the same change. Breaking changes, such as removing or
renaming a field or changing a type, must be agreed with all consumers in the
access table first.

When `esyfo-narmesteleder` starts producing records itself, it must keep the
current format: the same key, every property present, ISO-8601 dates and
explicit `null`s.

## Transition: mirroring from team-sykmelding

Until `esyfo-narmesteleder` produces the records itself, they originate in
`navikt/narmesteleder` (team-sykmelding) on
`teamsykmelding.syfo-narmesteleder-leesah`. Replace this section when that
changes.

`PersistNarmestelederRegisterFromLeesahConsumer` consumes the team-sykmelding
topic (consumer group `esyfo-narmesteleder-leesah-persist-consumer`), stores
valid relations, and then republishes the original key and value of the
validated records unchanged with `NarmestelederLeesahProducer`. Publishing is
enabled by `PERSIST_NARMESTELEDER_REGISTER` (enabled in dev and prod).

Because values are republished byte for byte, format changes made by
team-sykmelding, such as new properties or `status` values, appear on this
topic without a change in this repository. Coordinate such changes with
team-sykmelding until the takeover is complete.

### What is validated

Only records that pass validation are republished. Records are **not**
republished when:

- the value is not valid JSON, or a non-nullable field is missing or has the
  wrong type;
- `fnr` or `narmesteLederFnr` is not exactly 11 digits;
- `orgnummer` is not exactly 9 digits; or
- `narmesteLederTelefonnummer` or `narmesteLederEpost` is longer than 255
  characters.

Unknown properties and unknown `status` values do not cause a record to be
skipped. Skipped records are logged, and their offsets are committed, so
they are not retried.

Tombstones from the team-sykmelding topic are forwarded unchanged.

### Retries and duplicates

Records are published after a batch is stored and before the consumed offset
is committed. If publishing fails partway, the whole batch is retried, which
is where duplicates and brief replays come from. Records are published one at
a time in the order they are consumed, so records for one relation keep their
original order.
