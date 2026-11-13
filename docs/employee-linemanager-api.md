# GET /internal/api/v1/employee/linemanager

Lar en sykmeldt hente sine aktive nærmeste ledere på tvers av virksomheter.
Erstatter team sykmeldings `GET /user/v2/sykmeldt/narmesteledere` for
`ditt-sykefravaer`. Kontrakten står i
`src/main/resources/openapi/internal-documentation.yaml`.

## Autentisering

- Bare TokenX `UserPrincipal` med `acr: Level4`. Fødselsnummeret hentes fra
  `pid`.
- Andre issuere, også Maskinporten, gir `401`.
- Ingen Altinn-sjekk: brukeren ser bare sine egne koblinger.

## Filtrering

- Bare aktive koblinger: `aktiv_tom` er tom og `aktiv_fom` er ikke i fremtiden.
- Valgfri `orgNumber` (ni sifre) begrenser til én virksomhet. Ingen treff gir
  `200` med tom liste.
- Ugyldige e-postadresser utelates, se [email-address-rule.md](email-address-rule.md).
- Ingen paginering eller øvre grense.

## Avvik fra team sykmeldings endepunkt

Bare aktive koblinger, ingen fødselsnummer, og ingen `organisasjonsnavn` eller
`arbeidsgiverForskutterer`. Modellen mangler `organisasjonsnavn`, og
`arbeidsgiverForskutterer` skal ut av narmesteleder.
