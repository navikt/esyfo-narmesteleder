# GET /api/v1/access/organizations

Dette er et internt endepunkt. Det er ikke eksponert i OpenAPI eller Swagger.

Endepunkt som returnerer organisasjoner den innloggede arbeidsgiveren har nærmeste leder-tilgang til. Responsen er kompatibel med [`@navikt/virksomhetsvelger`](https://github.com/navikt/virksomhetsvelger).

## Sekvensdiagram

```mermaid
sequenceDiagram
    participant FE as narmesteleder-frontend
    participant API as esyfo-narmesteleder
    participant TX as Texas (NAIS TokenX)
    participant AT as arbeidsgiver-altinn-tilganger

    FE->>API: GET /api/v1/access/organizations<br/>Authorization: Bearer <idporten-token>
    API->>TX: introspect token (tokenx)
    TX-->>API: { active: true, pid: fnr, acr: Level4 }

    API->>TX: exchange token<br/>target: cluster:fager:arbeidsgiver-altinn-tilganger
    TX-->>API: { access_token: <obo-token> }

    API->>AT: POST /altinn-tilganger<br/>Authorization: Bearer <obo-token>
    AT-->>API: AltinnTilgangerResponse<br/>{ hierarki, orgNrTilTilganger, tilgangTilOrgNr }

    Note over API: Filtrerer hierarkiet:<br/>kun orger med nav_syfo_oppgi-narmesteleder<br/>eller 4596:1

    API-->>FE: 200 OK<br/>{ organizations: [{ orgNumber, name, subOrganizations[] }] }
```

## Respons

```json
{
  "organizations": [
    {
      "orgNumber": "123456789",
      "name": "Bedrift AS",
      "subOrganizations": [
        {
          "orgNumber": "987654321",
          "name": "Avdeling Oslo",
          "subOrganizations": []
        }
      ]
    }
  ]
}
```

## Autentisering

- **TokenX** (idporten) — kun `UserPrincipal` med `acr: Level4`
- Token exchanged via Texas-sidecar mot `arbeidsgiver-altinn-tilganger`
- Kalles internt fra `narmesteleder-frontend`

## Filtreringslogikk

En organisasjon inkluderes i responsen dersom:
- Den har Altinn 3-ressursen `nav_syfo_oppgi-narmesteleder`, **eller**
- Den har Altinn 2-tjenesten `4596:1`

Hovedenheter inkluderes også hvis en underenhet har tilgang. Underenheter uten tilgang filtreres bort.
