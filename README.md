# Syfo-esyfo-narmesteleder

[![Build Status](https://github.com/navikt/esyfo-narmesteleder/actions/workflows/build-and-deploy.yaml/badge.svg)](https://github.com/navikt/esyfo-narmesteleder/actions/workflows/build-and-deploy.yaml)

[![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?style=for-the-badge&logo=Kotlin&logoColor=white)](https://kotlinlang.org/)
[![Ktor](https://img.shields.io/badge/Ktor-%23087CFA.svg?style=for-the-badge&logo=Ktor&logoColor=white)](https://ktor.io/)
[![Postgresql](https://img.shields.io/badge/PostgreSQL-316192?style=for-the-badge&logo=postgresql&logoColor=white)](https://www.postgresql.org/)
[![Kafka](https://img.shields.io/badge/Apache_Kafka-231F20?style=for-the-badge&logo=apache-kafka&logoColor=white)](https://kafka.apache.org/g/)


## Environments

[🚀 Productions internal](https://narmesteleder-api.intern.nav.no)   

[🚀 Productions external](https://narmesteleder-api.nav.no)

[🛠️ Development internal](https://narmesteleder-api.intern.dev.nav.no)

[🛠️ Development external](https://narmesteleder-api.ekstern.dev.nav.no)


## OpenAPI
The OpenAPI specification for the API is available at https://narmesteleder-api.nav.no/swagger

## Overview
This is the repository for narmesteleder-api, a service that provides an API for managing narmesteleder connection between
employees on sick leave and their narmeste ledere.

It identifies when an employee has a need to be assigned a narmeste leder from their employer.
It will communicate this need to the employer by creating a dialog in Altinn Dialogporten,
that will contain an url endpoints that can be used with GET to retrieve details about person on sick leave, and PUT to assign narmesteleder to the employee.


## Diagrams
An always up-to-date diagram can be views in the excellent service from [Flex Arkitektur](https://flex-arkitektur.nav.no/?apper=prod-gcp.team-esyfo.esyfo-narmesteleder).
### C4 Container diagram
```mermaid
    C4Container
    title Container diagram esyfo-narmesteleder
    Person(person, Person, "A person using inbox in Altinn3 to find linemanager requirements sent from jNAV")
    Container_Ext(lps, "LPS", "And external system used by organizations")

    Container_Boundary(c3, "Digdir") {
        Container_Ext(dialogporten, "Dialogporten", "", "System for creating and responding with dialogs and transmissions")
    }

    Container_Boundary(c1, "Esyfo-Narmesteleder") {
        Container(narmesteleder-frontend, "narmesteleder-frontend", "Typescript, Docker Container", "Provides frontend for used to assign narmesteleder to employees on sick leave")
        Container(esyfo-narmesteleder, "narmesteleder-api", "Kotlin, Docker Container", "Provides api for managing narmesteleder connection between employees on sick leave and their narmeste ledere")
        ContainerDb(database, "CloudSQL Database", "Postgresql Database", "Stores dialogs and documents")
        Rel(esyfo-narmesteleder, database, "Uses", "sync, JDBC")
        Rel(narmesteleder-frontend, esyfo-narmesteleder, "Uses", "HTTPS/JSON")
    }

    Container_Boundary(kafka, "Kafka Team Sykmelding") {
        Container_Ext(kafkaTopic-sendt-sykmelding, "Kafka Topic: teamsykmelding.syfo-sendt-sykmelding", "Kafka Topic")
        Rel(kafkaTopic-sendt-sykmelding, esyfo-narmesteleder, "Consumes from")

        Container_Ext(kafkaTopic-narmesteleder-leesah, "Kafka Topic: teamsykmelding.syfo-narmesteleder-leesah", "Kafka Topic")
        Rel(kafkaTopic-narmesteleder-leesah, esyfo-narmesteleder, "Consumes from")

        Container_Ext(kafkaTopic-syfo-narmesteleder, "Kafka Topic: teamsykmelding.syfo-narmesteleder", "Kafka Topic")
        Rel(esyfo-narmesteleder, kafkaTopic-syfo-narmesteleder, "Publishes to")
    }
    
    Container_Boundary(c2, "Other Nais applications") {
        Container_Ext(tilganger, "Arbeidsgiver-altinn-tilganger", "Kotlin, Docker Container", "Provides Altinn access information for provided token")
        Container_Ext(aareg, "Aareg", "", "Provides information about employment status for a person")
        Container_Ext(ereg, "Ereg", "", "Provides information about organization structure")
        Container_Ext(pdl, "PDL", "", "Fetches information about organization structure")
    }

    Rel(esyfo-narmesteleder, tilganger, "Uses", "HTTPS/JSON")
    Rel(esyfo-narmesteleder, dialogporten, "Uses", "HTTPS/JSON")
    Rel( esyfo-narmesteleder, aareg, "Uses", "HTTPS/JSON")
    Rel( esyfo-narmesteleder, ereg, "Uses", "HTTPS/JSON")
    Rel(esyfo-narmesteleder, pdl, "Uses", "HTTPS/JSON")
   
    Rel(lps, dialogporten, "Uses", "HTTPS/JSON")
    Rel(lps, esyfo-narmesteleder, "Uses", "HTTPS/PDF")
    Rel(person, dialogporten, "Uses", "HTTPS/HTML")
    Rel(person, narmesteleder-frontend, "Uses", "HTTPS/HTML")
```

## Running tasks with mise
We use [mise](https://mise.jdx.dev/) to simplify running common tasks.
To run a task, use the command
```bash
mise <task-name>
````

To get a list of available tasks, run
```bash
mise tasks
```

## Linting and formatting is done using [ktlint](https://pinterest.github.io/ktlint/latest/)
Please make sure to run the lint check before pushing code. Best way to ensure this is to add a pre-commit git hook.
You can do this with the mise task
```bash
mise add-lint-check-as-pre-commit-hook 
```
Or manually by running
u
```bash
mise lint
```
If there are any linting errors, you can try to fix them automatically with
```bash
mise format
```

## Docker compose
### Size of container platform
In order to run kafka++ you will probably need to extend the default size of your container platform. (Rancher Desktop, Colima etc.)

Suggestion for Colima
```bash
colima start --arch aarch64 --memory 8 --cpu 4 
```

We have a docker-compose.yml file to run a postgresql database, texas and a fake authserver.
In addition, we have a docker-compose.kafka.yml that will run a kafka broker, schema registry and kafka-io

There are mise tasks to start and stop these environments.
Start them both using
```bash
mise docker-up
```
Stop them all again
```bash
mise docker-down
```
### Kafka-ui
You can use [kafka-ui](http://localhost:9000) to inspect your consumers and topics. You can also publish or read messages on the topics

## Authentication for dev
In order to get a token for annsatt that has access to update narmesteleder relasjon, you can use the following url:
https://tokenx-token-generator.intern.dev.nav.no/api/obo?aud=dev-gcp:team-esyfo:esyfo-narmesteleder

Select "på høyt nivå" and give the ident of a Daglig leder for the organisasjonsnummer you want to test with.

## Running requests locally
There is a [Bruno](https://www.usebruno.com/) collection in the folder [.bruno](./.bruno) that you can open and find request to run against your locally running instance.
Look in the Docs tab of requests for further instructions, when needed.

There is a folder with json files for kafka messages in [local-dev-resources](./local-dev-resources). These can be used from kafka-ui to publish messages to the topics that syfo-esyfo-narmesteleder is consuming.
