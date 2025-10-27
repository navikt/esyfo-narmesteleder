# esyfo-oppfolgingsansvarlig
WIP 
This project was created using the [Ktor Project Generator](https://start.ktor.io).

Here are some useful links to get you started:

- [Ktor Documentation](https://ktor.io/docs/home.html)
- [Ktor GitHub page](https://github.com/ktorio/ktor)
- The [Ktor Slack chat](https://app.slack.com/client/T09229ZC6/C0A974TJ9). You'll need to [request an invite](https://surveys.jetbrains.com/s3/kotlin-slack-sign-up) to join.

## Features

Here's a list of features included in this project:

| Name                                               | Description                                                 |
| ----------------------------------------------------|------------------------------------------------------------- |
| [Routing](https://start.ktor.io/p/routing-default) | Allows to define structured routes and associated handlers. |

## Building & Running

To build or run the project, use one of the following tasks:

| Task                          | Description                                                          |
| -------------------------------|---------------------------------------------------------------------- |
| `./gradlew test`              | Run the tests                                                        |
| `./gradlew build`             | Build everything                                                     |
| `buildFatJar`                 | Build an executable JAR of the server with all dependencies included |
| `buildImage`                  | Build the docker image to use with the fat JAR                       |
| `publishImageToLocalRegistry` | Publish the docker image locally                                     |
| `run`                         | Run the server                                                       |
| `runDocker`                   | Run using the local docker image                                     |

If the server starts successfully, you'll see the following output:

```
2024-12-04 14:32:45.584 [main] INFO  Application - Application started in 0.303 seconds.
2024-12-04 14:32:45.682 [main] INFO  Application - Responding at http://0.0.0.0:8080
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

Start them both using
```bash
docker-compose \
  -f docker-compose.yml \
  -f docker-compose.kafka.yml \
  up \
  db authserver texas broker kafka-ui \
  -d
```
Stop them all again
```bash
docker-compose \
  -f docker-compose.yml \
  -f docker-compose.kafka.yml \
  down
```
### Kafka-ui
You can use [kafka-ui](http://localhost:9000) to inspect your consumers and topics. You can also publish or read messages on the topics

## Authentication for dev
In order to get a token for annsatt that has access to update narmesteleder relasjon, you can use the following url:
https://tokenx-token-generator.intern.dev.nav.no/api/obo?aud=dev-gcp:team-esyfo:esyfo-narmesteleder

Select "på høyt nivå" and give the ident of a Daglig leder for the organisasjonsnummer you want to test with.

## Runnig requests localy
There is a [Bruno](https://www.usebruno.com/) collection in the folder [.bruno](./.bruno) that you can open and find request to run against your localy running instance.
Look in the Docs tab of requests for further instructions, when needed.
EG. How to create the row in db for value of the ```:id``` path parameter, is explained in the ```get behov``` request for narmesteleder.
