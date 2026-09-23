# Narmestelederbehov fulfillment migration

[PR #531](https://github.com/navikt/esyfo-narmesteleder/pull/531) establishes a
foundation for [#525](https://github.com/navikt/esyfo-narmesteleder/issues/525).
The production PUT route now delegates to the fulfillment use case through
capability-owned adapters and Koin modules. ADR-0002 and `architecture.md`
describe the target architecture; the architecture test for this activation
is tracked separately in #544.

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
| Focused tests plus endpoint/integration coverage | Retained and expanded; activation must run the same contract tests against the new route |
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
- Translate ordinary Dialogporten completion failures to a failed attempt so
  the Dialogporten step never fails the request after persistence: lookup
  failures return `Failed` and leave the behov retryable, while a missing behov
  or dialog ID returns `NotApplicable`. Ordinary completion failures also
  return `Failed`. Cancellation propagates from the new adapter; the existing
  scheduled job retains its legacy handling.
  Persistence re-reads the behov after publishing, as in the legacy flow;
  disappearance at that point returns the existing NotFound error.

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
