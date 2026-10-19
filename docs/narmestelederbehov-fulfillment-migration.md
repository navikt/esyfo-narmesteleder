# Narmestelederbehov fulfillment migration

[PR #531](https://github.com/navikt/esyfo-narmesteleder/pull/531) establishes a
foundation for [#525](https://github.com/navikt/esyfo-narmesteleder/issues/525).
The production PUT route now delegates to the fulfillment use case through
capability-owned adapters and Koin modules. ADR-0002 and `architecture.md`
describe the target architecture; the architecture test for this activation
is tracked separately in #544.

The legacy PUT handler and its dedicated service methods have been removed.
PUT HTTP contract tests now live in
`src/test/kotlin/no/nav/syfo/narmestelederbehov/api/FulfillNarmestelederbehovRouteTest.kt`.

## Acceptance criteria

| Requirement from #525 | Foundation status |
| --- | --- |
| Concrete, constructor-injected fulfillment use case | Implemented and tested without Ktor or Koin |
| Typed command/results and narrow application ports | Implemented with production adapters |
| Validation, lookup, authorization and side-effect order | Characterized on the existing handler; fulfillment owns contact normalization, need lookup and access, while `EstablishNarmestelederrelasjonUseCase` owns relation validation and publication |
| HTTP route delegates to the use case | Activated in #541 |
| Result-to-HTTP mapping and unchanged `ApiError` | Activated in #541 |
| Kafka, persistence and Dialogporten adapters | Activated in #541; contract data includes middle names and resolved employee identity |
| Capability-owned Koin registration/settings | Activated in #541 |
| Architecture checks | Moved to #544 |
| Focused tests plus endpoint/integration coverage | PUT HTTP contract tests moved to `narmestelederbehov/api` and run against the use-case route |
| Successful build | Required for each change, including activation |

The separate GET-by-id migration in
[#535](https://github.com/navikt/esyfo-narmesteleder/issues/535) does not activate
PUT fulfillment or complete #525.

## Activation requirements

- Preserve contact normalization and the first failing email-entry reason,
  with invalid contact rejected before need lookup and access checks, and need
  lookup before access checks.
  Fulfillment calls the `EstablishNarmestelederrelasjon` contract after access;
  that use case checks active sykmelding, employment, PDL persons and manager
  name before publishing. Preserve publish before persistence before
  Dialogporten completion. Unexpected failures must stop later effects.
- Preserve Kafka topic, key, source and value construction. Use the employee's
  resolved PDL ident and the manager's submitted ident. Keep first, middle and
  last names separate until the relation adapter constructs the existing
  `Sykmeldt.navn` and `Leder.fornavn` values.
- `EstablishNarmestelederrelasjonUseCase` records name-validation metrics immediately after matching, before publishing
  or other side effects can fail. Recording only from the final fulfillment
  result would lose observations on those failure paths. Preserve existing
  metric names/tags, exact/orthographic/fuzzy/none classification, parallel-name
  attempts/outcomes, and accepted **and rejected** best fuzzy scores. The
  internal domain type `LastNameMatch` retains all of these inputs for metric
  recording; it is not carried in establish or fulfillment results. The
  foundation does not replace the active metrics implementation.
- After publication, `markFulfilled` uses one status-only `UPDATE ... RETURNING`
  to obtain the dialog ID. A missing row returns
  `BehovMissingAfterPublication` (the existing 404 response), without a re-read.
  A null dialog ID skips completion (`NotApplicable`).
- The dialog adapter uses only `DialogportenClient` to fetch the revision and
  patch the dialog. Its `complete` port returns normally on success and throws
  on failure; the fulfillment use case handles ordinary client failures, logs
  the completion failure with `behov_id`, and returns `Failed` without failing
  the request. On success the repository updates only the behov status to
  `DIALOGPORTEN_STATUS_SET_COMPLETED`, and only while it is still
  `BEHOV_FULFILLED`; a status changed by another writer is left untouched and
  the attempt is still reported as `Completed`. Status-persistence failures likewise
  return `Failed`; cancellation propagates from either step. The existing
  scheduled job retains its legacy handling.

## Removing transitional duplication

The legacy `PhoneNumber`, `EmailAddress`, `OrganizationNumber` and
`PersonalIdentificationNumber` definitions in `narmesteleder.domain` are
removed. Identifiers use `PersonIdent` and `OrganizationNumber` from `ident`,
also in the HTTP request and response models. Contact validation has one owner,
`ManagerContactInput` in `narmestelederrelasjon`. `PersonIdent` keeps the
message `PersonalIdentificationNumber must be exactly 11 digits`, because
Jackson returns it in the 400 response for an invalid identifier in a request
body. The legacy `NameValidator` is also removed;
`MicrometerNameValidationMetrics` in `narmestelederrelasjon` owns the name
validation metrics. Do not add a second rule set for other flows. Shared
validation must have one owner exposed through the appropriate application
boundary, without one capability importing another capability's domain
internals. The parity tests are replaced with tests of the single owner.

`FakeAaregClient` remains seeded local-development infrastructure and supports
existing tests. `TestAaregClient` is an empty, explicitly seeded test-only fake
for focused characterization tests, with reset between cases. It avoids
coupling new tests to local-development data while `FakesWrapper` and its
dependent fixtures are phased out.
