# Runtime error contract v1.0.0

This directory pins the shared Team eSyfo JSON Schema as a test resource.
The bytes in `schema.json` are checked against `schema.sha256` by
`SystemAccessLoggingContractTest`.

- Contract: <https://navikt.github.io/team-esyfo/contracts/runtime-error/v1.0.0/schema.json>
- Source revision: <https://github.com/navikt/team-esyfo/blob/9d85123e2b6cbab060f11cd91518ebaa7218e726/docs/public/contracts/runtime-error/v1.0.0/schema.json>
- Validator: NetworkNT 2.0.4, test-only Jackson 2 release line.

Run `./gradlew test --tests '*SystemAccessLoggingContractTest'`. The normal
Gradle build also runs this test in CI; no schema download, Node installation
or production logging dependency is required. The test validates output from
the production JSON encoder, not a hand-built replacement log object.

Do not edit the local schema to accommodate a log change. Follow the shared
contract, or upgrade to a reviewed contract version and update the schema,
checksum and source revision together. The local event catalog stays in
`../system-access-catalog.json`; it is app-owned and not part of the shared schema.
