# ADR-0004: Feil fra eksterne systemer er resultater, ikke exceptions

**Dato:** 2026-10-07  
**Status:** Akseptert  
**Beslutningstakere:** Deltakende utviklere i team-esyfo

## Beslutning

Klienter mot eksterne systemer i `esyfo-narmesteleder` returnerer feil som
verdier. Feilen følger porter og use cases som et eget resultat helt ut til
kanten: HTTP-ruten, Kafka-konsumenten eller den planlagte jobben. Kanten
avgjør utfallet og logger én gang.

Beslutningen gjelder Aareg, Dinesykmeldte, Ereg, PDL, Altinn Tilganger,
Altinn Authorization, Dialogporten, token-vekslingen hos Altinn
(`AltinnTokenProvider`) og token-steget mot Texas. Feil i databasen, ved
Kafka-publisering og programmeringsfeil er fortsatt exceptions.

Mønsteret har seks deler:

- **Klienten.** Returnerer `UpstreamResult<T>` fra `platform/upstream`:

  ```kotlin
  sealed interface UpstreamResult<out T> {
      data class Success<out T>(val value: T) : UpstreamResult<T>
      data class Failure(val failure: UpstreamFailure) : UpstreamResult<Nothing>
  }

  data class UpstreamFailure(
      val upstream: UpstreamName,      // loggnavn, for eksempel "aareg" eller "pdl"
      val stage: UpstreamFailureStage, // TOKEN_EXCHANGE, REQUEST eller RESPONSE
      val status: Int?,
      val cause: Throwable? = null,
      val errors: List<UpstreamError> = emptyList(),
  )

  data class UpstreamError(val code: String, val detail: String? = null, val path: String? = null)
  ```

  `UpstreamName` er en value class som krever små bokstaver
  (`[a-z][a-z0-9_-]*`). `platform` kjenner ikke de konkrete systemene, i tråd
  med ADR-0002. Hver klient definerer sin egen konstant, for eksempel
  `AAREG` i `integration/aareg`. Derfor kan ingen bruke `when` uttømmende på
  `upstream`, og det trengs heller ikke: use casene forgrener på sine egne
  resultattyper, ikke på hvilket system som feilet.

  Klienten fanger exceptions fra Ktor og Texas én gang, ved grensen, og gjør
  dem om til `Failure`. `CancellationException` sendes alltid videre og blir
  aldri en `Failure`. Minst ett av `cause` og `errors` må ha innhold.
  `cause` brukes til teknisk diagnostikk uten exception-meldinger, men kastes aldri igjen.

  Forventede svar er en del av verdien, ikke av feilen. Ereg 404 og PDL
  `not_found` og `bad_request` med `extensions.id = ugyldig_ident` gir
  `Success(null)`. Andre feilkoder fra PDL, eller et svar uten
  data, gir `Failure`. Aareg 404 gir også `Failure`. Aareg svarer 404 med
  «Ukjent ident» når personen ikke finnes i PDL. En kjent person uten
  arbeidsforhold får 200 med tom liste. Hvis 404 ble gjort om til en tom liste,
  kunne jobben i #473 bryte en relasjon for en person Aareg ikke kjenner.

  Klienten tolker protokollen, som HTTP-status og PDLs
  `errors[].extensions.code`, men har ingen forretningsregler. Det er i tråd
  med ADR-0002.

  Enkeltoppslaget `getPerson` krever både persondata og identer uten feil for
  å gi `Success(PdlPerson)`. Hvis alle feil gjelder ikke funnet eller ugyldig
  ident, blir resultatet `Success(null)`. Enhver annen feil vinner, også når
  svaret inneholder data. Alle feil følger med i `errors`: `code` fra
  `extensions.code` (eller `unknown`), `detail` fra `extensions.id` og `path`
  som tekst med punktum mellom leddene. `message` tas med, avkortet til 200
  tegn, bare for PDL-spesifikke feil (med `extensions.code`). PDL dokumenterer
  at disse meldingene ikke inneholder personopplysninger og anbefaler å logge
  dem. Generelle GraphQL-feil uten `extensions` får ikke `message`. Klienten
  styrer aldri på `message`, fordi innholdet kan endre seg uten varsel.
  Et svar uten feil, men med manglende data, person eller identer, gir
  `Failure(RESPONSE, 200)` med `UpstreamError(code = "incomplete_response")`
  uten `cause`.

  Altinn Tilganger med `isError = true` gir `Failure(RESPONSE, 200)` med
  `errors = listOf(UpstreamError("is_error"))` uten `cause`, også når hierarkiet
  inneholder data. Bare `true` er feil; `false` eller manglende `isError`
  gir et vellykket svar. Feilen gir HTTP 500 `UPSTREAM_SERVICE_UNAVAILABLE`
  både i tilgangssjekken for personalledere og i lista over organisasjoner.

  `TexasHttpClient.exchangeTokenForIsAltinnTilganger` og
  `maskinportenSystemToken(target)` returnerer
  `UpstreamResult<String>`. Feil gir `upstream = texas` og
  `stage = TOKEN_EXCHANGE`, med status når Texas svarte. Ved et HTTP-feilsvar
  følger OAuth-feltet `error` med som `UpstreamError(code = ...)`, begrenset
  til små bokstaver av enumverdiene i `texas/client/OAuthFailureCode`.
  Ukjente, manglende eller uleselige verdier blir `unknown`.
  Verken `error_description` eller svartekst følger med i `errors`.
  OAuth-koden logges i `upstream_errors`; målsystemet logges ikke (#707).

  `AltinnTokenProvider.token(target)` sender Texas-feil videre uendret.
  Feil i vekslingen, inkludert ugyldig JWT eller manglende `exp`, gir
  `upstream = altinn-authentication`, `stage = TOKEN_EXCHANGE` og status når
  Altinn svarte. Cachen er per target og brukes bare med mer enn 300 sekunder
  igjen. Ellers hentes et nytt Maskinporten-token og veksles på nytt.
  Refresh og lagring av scope er fjernet.

  PDP-klienten returnerer `UpstreamResult<Decision>`. HTTP-feil og ugyldige,
  tomme eller flertydige svar gir `upstream = altinn-pdp`, `stage = RESPONSE`
  og status. Timeout og IO-feil gir `stage = REQUEST` uten status.
  Bare ett entydig `Permit` gir tilgang. `Indeterminate` gir
  `Failure(RESPONSE, 200)` med `UpstreamError("indeterminate", detail = ...)`
  uten `cause`. `detail` er `Status.StatusCode.Value`, begrenset til
  `urn:oasis:names:tc:xacml:1.0:status:syntax-error` og
  `urn:oasis:names:tc:xacml:1.0:status:processing-error`; andre eller manglende
  verdier blir `unknown`. `StatusMessage` og `StatusDetails` ignoreres.
  Altinn kan legge exception-meldinger i `StatusMessage`, se
  [AuthorizeExternal ved låst revisjon](https://github.com/Altinn/altinn-auth/blob/150759020cdabe4bd5db8a89ff1c36fcb060774f/src/apps/Altinn.Authorization/src/Altinn.Authorization/Controllers/DecisionController.cs).

- **Porten.** Hver port har sin egen sealed type med en
  `Unavailable(failure)`-variant. Adapteren i modulen gjør `UpstreamResult` om
  til portens type.

- **Use casen.** Resultattypen får en `UpstreamUnavailable(failure)`-variant.
  Use casen stopper med den gjennom `Step`, på samme måte som med andre
  forventede utfall.

- **Kanten.** HTTP-adapteren gjør `UpstreamUnavailable` om til
  `ApiErrorException` med `type = UPSTREAM_SERVICE_UNAVAILABLE` (HTTP 500) og
  legger ved `upstreamFailure`. `StatusPages` logger `api_request_failed` én
  gang. Kafka-konsumenter og planlagte jobber har egne regler, for eksempel nytt
  forsøk eller utsettelse, og egne hendelser.

- **Logging.** `logEvent` og `FailureDiagnostics` tar imot `UpstreamFailure`
  direkte og henter `upstream`, `failure_stage` og `upstream_status` fra den.
  `upstream_errors` inneholder `code`, `detail` og `path` fra `errors`.
  Stack og klassifisering hentes fra `cause` når den finnes. Uten `cause`
  blir `failure_kind = error_response`, og diagnostikken har ingen stack.

- **Legacy.** Legacy-tjenester som ennå ikke er flyttet (ADR-0002), kaller
  `getOrThrow()`. Den kaster `UpstreamRequestException` med `cause` bevart.
  `UpstreamRequestException` finnes bare som denne broen og slettes sammen med
  den siste legacy-kalleren.

  `getAltinnTilgangForOrgnr` har resultattypen `UpstreamResult<AltinnTilgang?>`.
  Ved feil returnerer personallederløpet i `AltinnOrganizationAccess`
  `OrganizationAccessResult.Unavailable(failure)`, og use casene returnerer
  `UpstreamUnavailable(failure)`. Kantene bruker `toUpstreamUnavailableException()`
  og svarer 500 `UPSTREAM_SERVICE_UNAVAILABLE`. Lista returnerer
  `ListAccessibleOrganizationsResult.Unavailable(failure)`. Klient og tjeneste
  logger ikke; `StatusPages` logger `api_request_failed` én gang.
  Systembrukerløpet gjør det samme ved første feil fra Texas, Altinn-vekslingen,
  PDP eller Ereg, uten fallback eller avvisningslogg etter feilen. Ereg-broen
  er fjernet. `Success(null)` fra Ereg gir fortsatt 400
  `ORGANIZATION_NOT_FOUND`. `DialogportenClient` bruker `getOrThrow()` fram
  til #688; token-endepunktet `/dialogporten/token` er slettet.

## Kontekst

#642 viste at de delte klientene håndterer feil ulikt. Noen pakker bare inn
4xx, andre bare `ResponseException`, og PDL pakker ikke inn tokenfeil. Fordi
feilen er en exception, er det `catch`-blokker langt unna som avgjør hva API-et
svarer. Ved 5xx, timeout eller IO-feil svarer API-et derfor 500
`INTERNAL_SERVER_ERROR` i stedet for `UPSTREAM_SERVICE_UNAVAILABLE`. Adapteren
for Dinesykmeldte fanger ingen feil. Ingenting i typesystemet viser at et kall
kan feile, så det er lett å glemme.

PDL svarer HTTP 200 også når oppslaget feiler, og `PdlClient` tolker i dag alle
tomme svar som at personen ikke finnes. Hvis PDL avviser kallet eller feiler
internt, tror kalleren at personen mangler.

Jobben i #473 kaller Aareg for hver relasjon og kan bryte relasjoner i
produksjon (ADR-0003). Der er forskjellen mellom «ingen arbeidsforhold» og
«Aareg svarer ikke» avgjørende. En feil som bare er en exception, kan havne i
en generell `catch`, og ingenting tvinger jobben til å håndtere den.

Kodebasen bruker allerede sealed resultater for forventede utfall i use cases og
porter, og `Step` for å stoppe tidlig. Feil fra eksterne systemer er det eneste
forventede utfallet som fortsatt er en exception.

## Alternativer vurdert

### Exceptions med felles innpakning

Alle klienter kaster `UpstreamRequestException`, og `StatusPages` gjør den om
til `UPSTREAM_SERVICE_UNAVAILABLE` på ett sted. Det løser #642 med minst kode.
Men flyten styres fortsatt av exceptions. Kompilatoren sier ikke fra når en
kaller glemmer feilen, og jobben i #473 må huske å fange riktig type.

### Hybrid

Forventede svar som «ikke funnet» blir verdier. Feil fra eksterne systemer
forblir exceptions og får egen type bare der en kaller trenger det. Det følger
vanlige råd for Kotlin, men gir to mønstre side om side. For hver port må noen
da vurdere om kalleren trenger feilen som verdi.

### Arrow (`Either` og `Raise`)

Gir kort kjeding med `bind()` og er mye brukt i Nav. Men det er en ny
avhengighet og et nytt idiom for teamet. Det bryter også med konvensjonen om at
porter og use cases returnerer egne sealed typer. `Step` dekker allerede
behovet for å stoppe tidlig i use cases.

### Kotlins `Result<T>`

Feiltypen er en utypet `Throwable`, og `runCatching` fanger også
`CancellationException`. Ikke aktuelt.

### Også database og Kafka som verdier

Gir ett mønster for all infrastruktur. Men `suspendTransaction` i Exposed ruller
tilbake fordi en exception blir kastet, og en databasefeil gir sjelden appen
noe meningsfylt å gjøre. Tilbakerulling måtte da håndteres eksplisitt.

### `UpstreamRequestException` som varig loggbærer

Kanten pakker `cause` inn i `UpstreamRequestException` før logging, slik at
`FailureDiagnostics` kan være uendret. Det gir mindre kode nå, men da blir
exceptions en varig del av mønsteret bare for å bære loggfelter.

## Konsekvenser

- Alle porter mot et eksternt system, og alle use cases som bruker slike
  porter, får en `Unavailable`-variant. Kompilatoren tvinger hver kant til å ta
  stilling til den. Adaptere og ruter får noe mer kode.
- Feil fra eksterne systemer gir alltid `UPSTREAM_SERVICE_UNAVAILABLE` med HTTP
  500, uansett klient. HTTP-statusen og OpenAPI-kontrakten endres ikke.
- Token for AzureAD hentes på ett sted i `TexasHttpClient` og returnerer
  `UpstreamResult` med `stage = TOKEN_EXCHANGE` ved feil.
- Hver klient har tester for 4xx, 5xx, timeout og tokenfeil.
- `UpstreamFailure` og exception-meldinger inneholder ikke fødselsnummer eller
  andre personopplysninger.
- `AaregClientException`, `DinesykmeldteClientException`, `PdlRequestException`
  og `PdlResourceNotFoundException` slettes.
- Mønsteret innføres klient for klient. De fire delte klientene i `integration`
  og token-steget kommer først, deretter Altinn-klientene i
  `organisasjonstilgang`, Dialogporten og token-vekslingen hos Altinn (#681).
- `getOrThrow()` brukes bare i legacy-kode. Når den siste legacy-kalleren er
  slettet, slettes også `getOrThrow()` og `UpstreamRequestException`.
