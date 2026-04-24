# validateLinemanager — Validation Flow

This diagram shows the validation pipeline executed by `ValidationService.validateLinemanager()`,
called from two routes: `POST /linemanager` and `PUT /linemanager/requirement/{id}`.

```mermaid
flowchart TD
    subgraph Entry Points
        A["POST /linemanager"]
        B["PUT /linemanager/requirement/{id}"]
    end

    A -- "Linemanager from request body" --> V
    B -- "Linemanager built from\nrequirement + Manager body\n(validateEmployeeLastName = false)" --> V

    V["ValidationService.validateLinemanager()"]

    V --> S1

    %% ── Step 1: Principal Access ──
    subgraph S1 ["1 · PrincipalAccessValidator"]
        direction TB
        PA{"Principal type?"}
        PA -- SystemPrincipal --> SP
        PA -- UserPrincipal --> UP

        SP["Resolve org numbers:\n1. Token orgNr == request orgNr?\n2. Arbeidsforhold opplysningspliktig?\n3. Ereg org hierarchy lookup"]
        SP --> SP_ORG{"orgNr match\ntoken orgNr?"}
        SP_ORG -- No --> E1_ORG["❌ 403 MISSING_ORG_ACCESS"]
        SP_ORG -- Yes --> SP_PDP["PDP: hasAccessToResource?\n(OPPGI_NARMESTELEDER)"]
        SP_PDP -- No --> E1_PDP["❌ 403 MISSING_ALTINN_RESOURCE_ACCESS"]
        SP_PDP -- Yes --> S1_OK["✅ Access granted\n(returns org name or null)"]

        UP["AltinnTilgangerService\n.validateTilgangToOrganization()"]
        UP --> UP_OK["✅ Access granted\n(returns org name)"]
    end

    S1_OK --> S2
    UP_OK --> S2

    %% ── Step 2: Active Sick Leave ──
    subgraph S2 ["2 · SickLeaveValidator"]
        SL["DinesykmeldteService\n.getIsActiveSykmelding(\n  employeeFnr, orgNumber)"]
        SL -- "false" --> E2["❌ 400 NO_ACTIVE_SICK_LEAVE"]
        SL -- "true" --> S2_OK["✅ Active sick leave confirmed"]
    end

    S2_OK --> S3

    %% ── Step 3: Arbeidsforhold (Employment) ──
    subgraph S3 ["3 · ArbeidsforholdValidator"]
        direction TB
        AF1["Fetch Aareg:\nemployee arbeidsforhold\n(already fetched in step 1)\n+ manager arbeidsforhold"]
        AF1 --> AF2{"Employee has\nany arbeidsforhold?"}
        AF2 -- "empty list" --> E3A["❌ 400 EMPLOYEE_MISSING_EMPLOYMENT_IN_ORG"]
        AF2 -- "non-empty" --> AF3{"Employee has\narbeidsforhold matching\nrequest orgNumber?"}
        AF3 -- No --> E3B["❌ 400 EMPLOYEE_MISSING_EMPLOYMENT_IN_ORG"]
        AF3 -- Yes --> AF4{"Manager employed\nunder same parent org\nas employee?"}
        AF4 -- No --> E3C["❌ 400 LINEMANAGER_MISSING_EMPLOYMENT_IN_ORG"]
        AF4 -- Yes --> S3_OK["✅ Employment validated"]
    end

    S3_OK --> S4

    %% ── Step 4: Name Validation (PDL) ──
    subgraph S4 ["4 · NameValidator"]
        direction TB
        PDL["Fetch PDL:\nperson data for employee + manager"]
        PDL --> NV1{"Manager last name\nmatches PDL?"}
        NV1 -- No --> E4A["❌ 400 LINEMANAGER_NAME_\nNATIONAL_IDENTIFICATION_NUMBER_MISMATCH"]
        NV1 -- Yes --> NV2{"validateEmployeeLastName\nenabled?"}
        NV2 -- "false\n(requirement flow)" --> S4_OK
        NV2 -- "true\n(direct POST)" --> NV3{"Employee last name\nmatches PDL?"}
        NV3 -- No --> E4B["❌ 400 EMPLOYEE_NAME_\nNATIONAL_IDENTIFICATION_NUMBER_MISMATCH"]
        NV3 -- Yes --> S4_OK["✅ Names validated"]
    end

    S4_OK --> DONE["Return LinemanagerActors\n(employee: Person, manager: Person)"]

    %% Styling
    classDef error fill:#f96,stroke:#c00,color:#000
    classDef ok fill:#9f9,stroke:#090,color:#000
    classDef entry fill:#69f,stroke:#03c,color:#fff

    class E1_ORG,E1_PDP,E2,E3A,E3B,E3C,E4A,E4B error
    class S1_OK,UP_OK,S2_OK,S3_OK,S4_OK,DONE ok
    class A,B entry
```

## Validators summary

| # | Validator | External service | Error types |
|---|-----------|-----------------|-------------|
| 1 | `PrincipalAccessValidator` | Altinn Tilganger, PDP, Ereg | `MISSING_ORG_ACCESS`, `MISSING_ALTINN_RESOURCE_ACCESS` |
| 2 | `SickLeaveValidator` | Dine Sykmeldte | `NO_ACTIVE_SICK_LEAVE` |
| 3 | `ArbeidsforholdValidator` | Aareg | `EMPLOYEE_MISSING_EMPLOYMENT_IN_ORG`, `LINEMANAGER_MISSING_EMPLOYMENT_IN_ORG` |
| 4 | `NameValidator` | PDL | `LINEMANAGER_NAME_NATIONAL_IDENTIFICATION_NUMBER_MISMATCH`, `EMPLOYEE_NAME_NATIONAL_IDENTIFICATION_NUMBER_MISMATCH` |

## Key differences between entry points

| | `POST /linemanager` | `PUT /linemanager/requirement/{id}` |
|---|---|---|
| Source of `Linemanager` | Request body | Built from stored requirement + `Manager` body |
| `validateEmployeeLastName` | `true` | `false` (employee already identified by requirement) |
