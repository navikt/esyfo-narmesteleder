# ADR-0002: Organiser backend som en kapabilitetsbasert modulær monolitt

**Dato:** 2026-09-17  
**Status:** Godkjent  
**Beslutningstakere:** Deltakende utviklere i team-esyfo  
**Utvidet:** 2026-10-06 med `narmestelederstatistikk` og `integration` (#615)

## Beslutning

`esyfo-narmesteleder` skal fortsatt være én deploybar applikasjon og ett
Gradle-modul. Koden skal organiseres som dype moduler etter
forretningskapabilitet:

- `narmestelederbehov`
- `narmestelederrelasjon`
- `sykmelding`
- `organisasjonstilgang`
- `narmestelederstatistikk`

Følgende støttestrukturer skal ha avgrensede roller:

- `platform` inneholder delte tekniske mekanismer uten domeneregler, som
  autentisering, databaseoppsett, Kafka-livssyklus og observerbarhet.
- `integration` inneholder klienter mot eksterne systemer som brukes av flere
  moduler, som Ereg, PDL, Aareg og Dinesykmeldte. En klient er bare transport:
  HTTP, token, cache og respons-DTO-er, uten forretningsregler.
- `bootstrap` leser konfigurasjon, velger adaptere og setter sammen
  applikasjonen.

`ident` er ikke en modul. Det er en liten delt pakke med offisielle
identifikatorer som har lik betydning og validering i alle modulene, som
`PersonIdent` og `OrganizationNumber`. Pakken skal ikke inneholde tjenester,
repositories, konfigurasjon eller forretningsregler.

Hver forretningsmodul organiseres i `api`, `application`, `domain` og
`infrastructure`. Modulene eksponerer små application-kontrakter for konkrete
handlinger. De deler ikke repositories, persistensmodeller, transportmodeller
eller interne domeneobjekter.

Avhengigheter mellom moduler går én vei og danner ingen sykler, heller ikke
indirekte gjennom andre pakker. En modul bruker bare en annen moduls publiserte
kontrakter og domenetypene disse kontraktene navngir.

Vi innfører strukturen gradvis i eksisterende Gradle-modul. Architecture tests
skal håndheve avhengighetsreglene for migrert kode. Vi vurderer separate
Gradle-moduler først når grensene er stabile og en fysisk oppdeling gir tydelig
verdi.

## Kontekst

Dagens kode er delvis gruppert etter domene, men viktige flyter er fordelt på
brede `service`, `application`, `plugins`, `db` og `exposed`-pakker.
`NarmestelederService`, `ValidationService` og den globale Koin-konfigurasjonen
samler ansvar som endres av ulike grunner. Det gjør autorisasjon,
sideeffektrekkefølge, transaksjoner og eksterne avhengigheter vanskeligere å
forstå og teste.

Et narmestelederbehov og en narmestelederrelasjon har ulike livsløp:

- Et **narmestelederbehov** er en midlertidig oppgave som opprettes, valideres,
  besvares, fullføres eller utløper. Modulen eier `nl_behov` og samhandlingen
  med Dialogporten.
- En **narmestelederrelasjon** er koblingen mellom en arbeidstaker, en
  narmesteleder og en organisasjon. Modulen eier relasjonsregisteret,
  etablering, brudd, publisering, oppslag og søk.

Når et behov fullføres, kaller `narmestelederbehov` en smal application-kontrakt
som eies av `narmestelederrelasjon`. Vi beholder dagens synkrone
publish-first-rekkefølge. Outbox eller endrede leveringsgarantier er ikke del av
denne beslutningen.

`sykmelding` eier konsum og lokal lagring av sendt sykmelding, inkludert
deduplisering og retention. I første struktur kaller `sykmelding` smale
kontrakter i `narmestelederbehov` og `narmestelederrelasjon`. Behovsmodulen
bruker fortsatt Dinesykmeldte gjennom sin egen `ActiveSykmeldingLookup`-port.
Overgang til lokal sykmelding som kilde håndteres senere gjennom #508 og
tilknyttede oppgaver.

`organisasjonstilgang` eier den gjenbrukbare forretningsregelen for tilgang til
en organisasjon via Altinn Tilganger, PDP og Ereg. Ktor/Texas-autentisering
forblir i `platform.auth`. Hver use case eier selv ressursoppslag,
rekkefølge og eventuell skjerming av om en ressurs finnes.

`narmestelederstatistikk` er en lesemodell for statistikk per organisasjon.
Statistikken teller på tvers av tabeller som eies av andre moduler
(`nl_behov`, `narmeste_leder` og `sendt_sykmelding`). Modulen har derfor egne,
skrivebeskyttede tabelldefinisjoner over disse tabellene i stedet for å
importere andre modulers tabeller eller repositories. Den avhenger bare av
`organisasjonstilgang`, og ingen annen modul avhenger av den. Modulen skriver
aldri, og eierskapet til tabellene og skjemaet ligger fortsatt hos de eiende
modulene.

Flere moduler trenger de samme eksterne systemene. For eksempel slår både
`narmestelederrelasjon` og `narmestelederbehov` opp personer i PDL og
arbeidsforhold i Aareg. Klienten mot et slikt system ligger i
`integration/<system>`. Hver modul har sin egen port i `application` og sin egen
adapter i `infrastructure`, som bruker klienten direkte og mapper responsen til
modulens egne typer. Moduler deler ikke porter eller adaptere for eksterne
systemer. Bare `*.infrastructure` og `bootstrap` får importere `integration`, og
`integration` importerer aldri forretningsmoduler. En klient med bare én bruker
ligger i den modulens `infrastructure` og flyttes til `integration` først når en
modul til trenger den.

## Alternativer vurdert

### Ett stort `narmesteleder`-modul

Dette gir færre grenser i starten, men blander det midlertidige behovslivsløpet
med den varige relasjonen. Modulen ville lett beholdt brede tjenester og
uklart eierskap.

### Teknisk lagdeling

Globale pakker for controllers, services, repositories og clients er kjent,
men sprer én forretningsflyt over hele kodebasen og gjør tekniske mekanismer til
den primære strukturen.

### Delte eksterne klienter i `platform` eller i én eiermodul

Å legge klientene i `platform` ville gjort `platform` til mer enn tekniske
mekanismer, fordi klientene kjenner konkrete eksterne systemer og deres
datamodeller. Å la én forretningsmodul eie klienten og andre moduler importere
den, ville skapt avhengigheter mellom moduler som ikke handler om
forretningskapabiliteter.

### Separate Gradle-moduler nå

Dette ville gitt sterkere kompilatorgrenser, men også gjort den første
migreringen vesentlig større. Vi vil først bevise grensene gjennom konkrete
vertikale flyter og architecture tests.

### Dele løsningen i flere tjenester

Det finnes ikke dokumentert behov for egne deployløp, skalering eller
teameierskap som forsvarer nye nettverksgrenser, kontrakter og driftsansvar.

## Konsekvenser

- En use case er en liten klasse for én forretningshandling. En query er en
  liten klasse for ett leseformål.
- Vi lager interfaces for faktiske grenser, særlig mellom moduler og mot
  eksterne systemer. Vi lager ikke et interface for hver klasse.
- Ports ligger i `application`. PostgreSQL-, HTTP-, Kafka- og andre adaptere
  ligger i modulens `infrastructure`. Adaptere mot eksterne systemer som flere
  moduler bruker, kaller en klient i `integration`.
- Repository-adaptere eier transaksjoner. Use cases skal ikke bruke Exposed,
  JDBC eller database-transaksjoner direkte.
- Forventede feil returneres som typede resultater. HTTP-adaptere oversetter
  dem til `ApiErrorException`, og sentral Ktor `StatusPages` lager responsen.
- Bootstrap oppretter små typede konfigurasjonsobjekter. Forretningsmoduler
  mottar ikke et globalt `Environment`.
- Domenebegreper er norske. Tekniske roller og handlinger er engelske.
- Kafka-topologi, leader gating, leveringssemantikk, databaseskjema og
  persistensmekanisme endres ikke som en del av struktureringen.
- `ShadowActiveSykmeldingService` beholdes som en sovende migreringsmekanisme
  til #508 og oppfølgingen av lokal aktiv-sykmelding-validering er avklart.
- Migreringen skjer i komplette vertikale flyter. Første flyt er fullføring av
  narmestelederbehov.
