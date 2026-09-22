# Local Bruno testing

This collection contains requests for a locally running `narmesteleder-api`.
Use the following setup to test `update behov and nl`.

## Prerequisites and setup

1. Start the local dependencies:

   ```bash
   mise docker-up
   ```

2. Start the application:

   ```bash
   mise start
   ```

3. Open the `.bruno` directory as a collection in Bruno and select the
   `nl_vars_local` environment.
4. Create `.bruno/.env` from `.bruno/.env.default` and set these values:

   ```dotenv
   BEHOV_ID=<replace-after-producing-kafka-message>
   SYSTEM_KUNDE_ORGNR=215649202
   SYSTEM_EIER_ORGNR=215649202
   NARMESTE_LEDER_ID=<REPLACE_ME>
   ORG_NUMMER=<REPLACE_ME>
   ```

## Test `update behov and nl`

Run the following steps in order:

1. In Bruno, run `mock-auth/maskinporten/create maskinporten`. Its
   after-response script stores the `access_token` in the secret Bruno
   environment variable `token`. The collection bearer authentication inherits
   `{{token}}`.
2. Open Kafka UI at <http://localhost:9080>. Publish
   `local-dev-resources/kafka/narmesteleder-leesah/create-nl_behov.json` to
   `teamsykmelding.syfo-narmesteleder-leesah` with Kafka key
   `0199b8f1-0cfe-7787-bab8-2fb1cf1b4767`.
3. In the application logs, find `Inserted NarmestelederBehovEntity with id:`
   and copy the UUID after the colon to `BEHOV_ID` in `.bruno/.env`. Reload
   Bruno or the environment if necessary.
4. Run `get behov`.
5. Run `update behov and nl`. A successful request returns `202 Accepted`.

The PUT body uses `lastName: "Nienow"` because the local `FakePdlClient`
deterministically generates that surname for manager ident `13468329780`.
