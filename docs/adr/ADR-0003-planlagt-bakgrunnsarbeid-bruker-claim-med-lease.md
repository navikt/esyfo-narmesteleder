# ADR-0003: Planlagt bakgrunnsarbeid bruker claim med lease, ikke leader election

**Dato:** 2026-10-07  
**Status:** Foreslått  
**Beslutningstakere:** Deltakende utviklere i team-esyfo

## Beslutning

Nytt planlagt bakgrunnsarbeid i `esyfo-narmesteleder` skal kjøre på alle
podder og fordele arbeidet gjennom databasen. Leader election skal ikke brukes.
Første bruker er kontrollen av nærmestelederrelasjoner mot avsluttede
arbeidsforhold (#473).

Mønsteret har to deler:

- **Loopen.** En generisk bakgrunnsloop i `platform/scheduling` kjører på
  alle podder og håndterer livssyklus og feilisolering, uten domeneregler eller
  kunnskap om databasen.
- **Claim med lease.** Arbeidet ligger som rader i en tabell som eies av
  modulen som utfører det. En pod claimer forfalte rader med
  `FOR UPDATE SKIP LOCKED`, setter en tidsbegrenset lease og en ny
  `claim_token`, og committer før eksterne kall. Alle senere overganger i
  databasen er compare-and-set på `claim_token`. Det hindrer en pod med
  foreldet `claim_token` i å overskrive en nyere behandling i databasen. Det
  beskytter ikke eksterne effekter: Kafka-publisering er fortsatt at-least-once.
  Utløpt lease gjør raden tilgjengelig igjen etter krasj.

Claim-logikken ligger i modulens repository-adapter, fordi forfall, prioritet
og tilstander er domenespesifikke. Den trekkes ut til en felles mekanisme først
når en bruker til viser hva som faktisk er felles.

Mønsteret følger
[ADR 0004 i syfo-budstikka](https://github.com/navikt/syfo-budstikka/blob/main/docs/adr/0004-konkurrerende-konsumenter-claim-med-lease.md).

## Kontekst

Planlagte jobber i applikasjonen arver i dag `ScheduledLeaderTask` og startes
bare på poden som er leder. Teamet har sett minneproblemer og uforutsigbar
last med dette mønsteret. #519 beskriver at all last havner på én pod, mens
Kubernetes skalerer opp nye podder som ikke hjelper. Leader election gir heller
ingen garanti for at bare én pod kjører: i et kort vindu kan to podder tro at
de er leder.

#507 flytter Kafka-konsum bort fra leader election, men sier at leader election
fortsatt kan brukes for planlagte bakgrunnsoppgaver. Denne ADR-en går lenger
for nytt planlagt arbeid. Kontrollen i #473 gjør et eksternt kall per relasjon
mot Aareg og kan bryte relasjoner i produksjon. Den må derfor tåle flere
samtidige podder, krasj og trege kall, uavhengig av hvem som er leder.

Prod kjører med 2–4 replikaer.

## Alternativer vurdert

### `ScheduledLeaderTask`

Gjenbruker et kjent mønster og er raskest å ta i bruk. Men det beholder
problemene med last og minne på lederen, gir ingen beskyttelse når to podder
tror de er leder, og bygger videre på det #519 vil bort fra.

### Claim med lease uten `claim_token`

Enklere, men en pod som stopper opp etter et oppslag, kan våkne etter at
leasen er utløpt og en annen pod har behandlet raden. Den kan da overskrive et
nyere resultat i databasen, for eksempel erstatte en ny kontroll med et
foreldet utfall eller flytte neste forfall feil. Uten token kan heller ikke
poden oppdage at den har mistet claimet før den publiserer. For en jobb som
bryter relasjoner er det ikke akseptabelt.

### PostgreSQL advisory lock

`pg_try_advisory_xact_lock` gir én aktiv pod uten leader election og passer
for korte vedlikeholdsjobber. Låsen holdes over hele transaksjonen. For arbeid
med eksterne kall per rad gir det lange transaksjoner og ingen fordeling av
arbeid mellom podder.

### Felles Gradle-bibliotek for claim, inbox og outbox

Teamet har flere apper med varianter av samme mønster, og et felles bibliotek
kan bli riktig på sikt. Nå ville det krevd et API på tvers av team, publisering
og versjonering, og at variantene ble samkjørt først. Det tas opp som et eget
team-initiativ. `esyfo-observability` er et logging- og observerbarhetsbibliotek
og er ikke riktig sted for denne mekanismen.

## Konsekvenser

- `platform/scheduling` inneholder en generisk bakgrunnsloop uten leader
  election. Loopen importerer ingen forretningsmoduler.
- Hver jobb eier sin egen tabell med claim-tilstand (forfall, lease og
  `claim_token`) i sin modul.
- Claims gir ingen rekkefølgegaranti mellom rader. Modulen må selv håndheve
  sekvensiell behandling der det trengs.
- Effekter utenfor databasen, som Kafka-publisering, er at-least-once. De må
  være idempotente eller ufarlige å gjenta.
- Behandlingen av en claimet rad har en tidsgrense som er kortere enn leasen,
  regnet fra claim-tidspunktet. Det reduserer, men fjerner ikke, vinduet der en
  pod kan publisere etter å ha mistet claimet.
- Batchstørrelse og intervall gjelder per pod. Samlet last mot eksterne
  systemer dimensjoneres for maksimalt antall replikaer.
- Eksisterende `ScheduledLeaderTask`-jobber endres ikke av denne beslutningen.
  Migrering av dem følges opp i #519.
