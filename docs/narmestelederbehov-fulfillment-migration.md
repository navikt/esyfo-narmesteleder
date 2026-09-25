# Narmestelederbehov fulfillment migration

[PR #531](https://github.com/navikt/esyfo-narmesteleder/pull/531) establishes a
foundation for [#525](https://github.com/navikt/esyfo-narmesteleder/issues/525).
The production PUT route now delegates to the fulfillment use case through
capability-owned adapters and Koin modules. ADR-0002 and `architecture.md`
describe the target architecture; the architecture test for this activation
is tracked separately in #544.

The legacy PUT handler and its dedicated service methods have been removed.
PUT HTTP contract tests now live in
`src/test/kotlin/no/nav/syfo/narmestelederbehov/api/FulfillNarmestelederbehovRouteTest.kt`;
the legacy requirement API suite covers GET only.

## Acceptance criteria

| Requirement from #525 | Foundation status |
| --- | --- |
| Concrete, constructor-injected fulfillment use case | Implemented and tested without Ktor or Koin |
| Typed command/results and narrow application ports | Implemented with production adapters |
| Validation, lookup, authorization and side-effect order | Characterized on the existing handler and tested in the extracted use case |
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
  resource lookup before access checks, and publish before persistence before
  Dialogporten completion. Unexpected failures must stop later effects.
- Preserve Kafka topic, key, source and value construction. Use the employee's
  resolved PDL ident and the manager's submitted ident. Keep first, middle and
  last names separate until the relation adapter constructs the existing
  `Sykmeldt.navn` and `Leder.fornavn` values.
- Record name-validation metrics immediately after matching, before publishing
  or other side effects can fail. Recording only from the final fulfillment
  result would lose observations on those failure paths. Preserve existing
  metric names/tags, exact/orthographic/fuzzy/none classification, parallel-name
  attempts/outcomes, and accepted **and rejected** best fuzzy scores. The typed
  `ManagerLastNameMatch` retains all of these inputs; the foundation does not
  replace the active metrics implementation.
- After publication, `markFulfilled` uses one status-only `UPDATE ... RETURNING`
  to obtain the dialog ID. A missing row returns
  `BehovMissingAfterPublication` (the existing 404 response), without a re-read.
  A null dialog ID skips completion (`NotApplicable`).
- The dialog adapter uses only `DialogportenClient` to fetch the revision and
  patch the dialog. Its `complete` port returns normally on success and throws
  on failure; the fulfillment use case handles ordinary client failures, logs
  the completion failure with `behov_id`, and returns `Failed` without failing
  the request. On success the repository updates only the behov status to
  `DIALOGPORTEN_STATUS_SET_COMPLETED`. Status-persistence failures likewise
  return `Failed`; cancellation propagates from either step. The existing
  scheduled job retains its legacy handling.

## Removing transitional duplication

After activation, migrate every remaining caller and delete the legacy
`NameValidator`, `PhoneNumber`, `EmailAddress`, `OrganizationNumber` and
`PersonalIdentificationNumber` definitions. Do not keep a second rule set for
other flows. Identifiers converge on `ident`; shared validation must have one
owner exposed through the appropriate application boundary, without one
capability importing another capability's domain internals. Parity tests guard
the temporary copies until that removal; replace them with tests of the single
owner as the copies disappear.

`FakeAaregClient` remains seeded local-development infrastructure and supports
existing tests. `TestAaregClient` is an empty, explicitly seeded test-only fake
for focused characterization tests, with reset between cases. It avoids
coupling new tests to local-development data while `FakesWrapper` and its
dependent fixtures are phased out.
