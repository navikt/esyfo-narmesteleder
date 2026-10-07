# ADR-0004: Feil fra eksterne systemer er resultater, ikke exceptions

**Dato:** 2026-10-07  
**Status:** Foreslått  
**Beslutningstakere:** Deltakende utviklere i team-esyfo

## Beslutning

Klienter mot eksterne systemer i `esyfo-narmesteleder` returnerer feil som
verdier. Feilen følger porter og use cases som et eget resultat helt ut til
kanten: HTTP-ruten, Kafka-konsumenten eller den planlagte jobben. Kanten
avgjør utfallet og logger én gang.

Beslutningen gjelder Aareg, Dinesykmeldte, Ereg, PDL, Altinn Tilganger,
Altinn Authorization og token-steget mot Texas. Feil i databasen, ved
Kafka-publisering og programmeringsfeil er fortsatt exceptions.

Mønsteret har seks deler:

- **Klienten.** Returnerer `UpstreamResult<T>` fra `platform/upstream`:

  ```kotlin
  sealed interface UpstreamResult<out T> {
      data class Success<out T>(val value: T) : UpstreamResult<T>
      data class Failure(val failure: UpstreamFailure) : UpstreamResult<Nothing>
  }

  data class UpstreamFailure(
      val upstream: Upstream,          // avgrenset enum, for eksempel AAREG eller PDL
      val stage: UpstreamFailureStage, // TOKEN_EXCHANGE, REQUEST eller RESPONSE
      val status: Int?,
      val cause: Throwable,
  )
  ```

  Klienten fanger exceptions fra Ktor og Texas én gang, ved grensen, og gjør
  dem om til `Failure`. `CancellationException` sendes alltid videre og blir
  aldri en `Failure`. `cause` logges, men kastes aldri igjen.

  Forventede svar er en del av verdien, ikke av feilen. Ereg 404 og PDL
  `not_found` gir `Success(null)`. Andre feilkoder fra PDL, eller et svar uten
  data, gir `Failure`. Aareg 404 gir også `Failure`. Aareg svarer 404 med
  «Ukjent ident» når personen ikke finnes i PDL. En kjent person uten
  arbeidsforhold får 200 med tom liste. Hvis 404 ble gjort om til en tom liste,
  kunne jobben i #473 bryte en relasjon for en person Aareg ikke kjenner.

  Klienten tolker protokollen, som HTTP-status og PDLs
  `errors[].extensions.code`, men har ingen forretningsregler. Det er i tråd
  med ADR-0002.

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
  Stack og klassifisering hentes fra `cause`, som i dag.

- **Legacy.** Legacy-tjenester som ennå ikke er flyttet (ADR-0002), kaller
  `getOrThrow()`. Den kaster `UpstreamRequestException` med `cause` bevart.
  `UpstreamRequestException` finnes bare som denne broen og slettes sammen med
  den siste legacy-kalleren.

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
  og token-steget kommer først (#642), deretter Altinn-klientene i
  `organisasjonstilgang`.
- `getOrThrow()` brukes bare i legacy-kode. Når den siste legacy-kalleren er
  slettet, slettes også `getOrThrow()` og `UpstreamRequestException`.
