# GET /api/v1/access/organizations

Internt endepunkt for `narmesteleder-frontend`. Responsen er kompatibel med
[`@navikt/virksomhetsvelger`](https://github.com/navikt/virksomhetsvelger).
Kontrakten står i `src/main/resources/openapi/internal-documentation.yaml`.

## Autentisering

- Bare TokenX `UserPrincipal` med `acr: Level4`.
- Texas veksler tokenet mot `arbeidsgiver-altinn-tilganger`.

## Filtrering

- En organisasjon er med hvis den har Altinn 3-ressursen
  `nav_syfo_oppgi-narmesteleder`.
- En hovedenhet er med hvis en av underenhetene har tilgang. Underenheter uten
  tilgang er ikke med.

## Feil

- Feil fra Altinn Tilganger eller Texas gir HTTP 500
  `UPSTREAM_SERVICE_UNAVAILABLE`, aldri en tom liste.
- HTTP 200 med `isError = true` er en feil, selv om hierarkiet har data.
  `isError = false` eller manglende felt er vellykket.
- `StatusPages` logger feilen én gang.
