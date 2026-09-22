# Narmestelederbehov fulfillment migration

[PR #531](https://github.com/navikt/esyfo-narmesteleder/pull/531) establishes a
foundation for [#525](https://github.com/navikt/esyfo-narmesteleder/issues/525).
It does not complete that issue: the production PUT route still uses the
existing implementation. ADR-0002 and `architecture.md` describe the target
architecture, while this document records the intermediate state.

## Acceptance criteria

| Requirement from #525 | Foundation status |
| --- | --- |
| Concrete, constructor-injected fulfillment use case | Implemented and tested without Ktor or Koin |
| Typed command/results and narrow application ports | Implemented; production adapters remain pending |
| Validation, lookup, authorization and side-effect order | Characterized on the existing handler and tested in the extracted use case |
| HTTP route delegates to the use case | Deferred to activation |
| Result-to-HTTP mapping and unchanged `ApiError` | Deferred to activation; existing HTTP handling remains active |
| Kafka, persistence and Dialogporten adapters | Deferred to activation; contract data includes middle names and resolved employee identity |
| Capability-owned Koin registration/settings | Deferred to activation |
| Architecture checks | Not included in this change |
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
  fulfillment stays successful and pending synchronization remains retryable.
  The legacy service catches cancellation inside completion as well. Its
  cancellation handling must be explicitly reconciled with the documented
  propagation rule when implementing the adapter, rather than hidden in an
  otherwise structural activation change.

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
