# Development

## Running tasks with mise

We use [mise](https://mise.jdx.dev/) to run common tasks.

```bash
mise <task-name>
```

List the available tasks with

```bash
mise tasks
```

## Linting and formatting

Linting and formatting use [ktlint](https://pinterest.github.io/ktlint/latest/). Run the lint check before pushing
code. The easiest way is to add it as a pre-commit git hook:

```bash
mise add-lint-check-as-pre-commit-hook
```

Or run it manually:

```bash
mise lint
```

Fix linting errors automatically, where possible, with

```bash
mise format
```

## Docker Compose

### Size of container platform

To run Kafka and the other services you will probably need to extend the default size of your container platform
(Rancher Desktop, Colima etc.).

Suggestion for Colima:

```bash
colima start --arch aarch64 --memory 8 --cpu 4
```

### Services

- `docker-compose.yml` runs PostgreSQL, Valkey, Texas and a fake auth server (mock-oauth2-server).
- `docker-compose.kafka.yml` runs a Kafka broker, Schema Registry, Kafka REST Proxy and kafka-ui.

Start the services the application needs locally (PostgreSQL, Valkey, the fake auth server, Texas, the Kafka broker and
kafka-ui) with

```bash
mise docker-up
```

Stop all of them again with

```bash
mise docker-down
```

### Running the application

Start the application locally with `mise start` (or `./gradlew runLocal`). Local runs use fake clients for external
systems and settings from `src/test/resources/application-local.conf`; the fakes, fixtures and local settings live in
the test sources and are not part of the production artifact.

### kafka-ui

Use [kafka-ui](http://localhost:9080) to inspect consumers and topics, and to publish or read messages.

There are JSON files with Kafka messages in [local-dev-resources](../local-dev-resources). Publish them from kafka-ui to
the topics that esyfo-narmesteleder consumes.

## Running requests locally

There is a [Bruno](https://www.usebruno.com/) collection in [.bruno](../.bruno) for requests against a locally running
instance. See the [local Bruno testing guide](../.bruno/README.md) for setup and the `update behov and nl` workflow.

## Authentication in dev

Get a token that gives access to the service as a person from
https://tokenx-token-generator.intern.dev.nav.no/api/obo?aud=dev-gcp:team-esyfo:esyfo-narmesteleder

Select "på høyt nivå".

- To assign a line manager (nærmesteleder) or break an existing relation, use the ident of a general manager
  (daglig leder) for the organization number (organisasjonsnummer) you want to test with.
- To act as a person on sick leave, use the ident of a person registered as an employee in the organization.
- To act as the line manager, use the ident of a person registered as line manager in the organization.

## PDL Leesah Avro schema

The `pdl.leesah-v1` consumer uses Avro and Schema Registry. `Personhendelse.avsc` is copied from
`navikt/narmesteleder` (`src/main/avro/no/nav/person/pdl/leesah/Personhendelse.avsc`, SHA
`24c9a2cd2921e9cc423df8c343542ff0967564ae`).
