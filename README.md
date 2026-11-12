# esyfo-narmesteleder

[![Build Status](https://github.com/navikt/esyfo-narmesteleder/actions/workflows/build-and-deploy.yaml/badge.svg)](https://github.com/navikt/esyfo-narmesteleder/actions/workflows/build-and-deploy.yaml)

[![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Ktor](https://img.shields.io/badge/Ktor-087CFA?logo=ktor&logoColor=white)](https://ktor.io/)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-4169E1?logo=postgresql&logoColor=white)](https://www.postgresql.org/)
[![Apache Kafka](https://img.shields.io/badge/Apache_Kafka-231F20?logo=apachekafka&logoColor=white)](https://kafka.apache.org/)

## Environments

🚀 [Production, internal](https://narmesteleder-api.intern.nav.no)

🚀 [Production, external](https://narmesteleder-api.nav.no/swagger)

🛠️ [Development, internal](https://narmesteleder-api.intern.dev.nav.no)

🛠️ [Development, external](https://narmesteleder-api.ekstern.dev.nav.no/swagger)

## OpenAPI

- External API: https://narmesteleder-api.nav.no/swagger
- Internal API: https://narmesteleder-api.intern.nav.no/internal/swagger

## Overview

narmesteleder-api manages the narmesteleder relation between employees on sick leave and their narmeste ledere.

It identifies when an employee on sick leave needs a narmeste leder from their employer, and tells the employer by
creating a dialog in Altinn Dialogporten. The dialog contains URLs that can be used with GET to retrieve details about
the person on sick leave, and with PUT to assign a narmeste leder.

## Documentation

- [Data flow](docs/data-flow.md): HTTP endpoints, Kafka topics, external systems and background work
- [Application architecture](docs/architecture.md)
- [Development](docs/development.md): local setup, linting, Docker Compose and tokens in dev
- [Application logging](docs/logging.md)
- [Kafka topic `team-esyfo.syfo-narmesteleder-leesah`](docs/kafka/syfo-narmesteleder-leesah.md) and its
  [JSON Schema](docs/kafka/syfo-narmesteleder-leesah.schema.json)
- [Architecture decision records](docs/adr)
- [Flex Arkitektur](https://flex-arkitektur.nav.no/?apper=prod-gcp.team-esyfo.esyfo-narmesteleder): always up-to-date
  runtime diagram

## For Nav-ansatte

Interne henvendelser kan sendes via Slack i kanalen [#esyfo](https://nav-it.slack.com/archives/C012X796B4L).
