# ADR-0005: Person-modulen eier lokale personopplysninger

**Dato:** 2026-11-02  
**Status:** Foreslått  
**Beslutningstakere:** Deltakende utviklere i team-esyfo

## Beslutning

Vi oppretter `person` som en egen modul i den modulære monolitten
([ADR-0002](ADR-0002-organiser-backend-som-en-kapabilitetsbasert-modulaer-monolitt.md)).
Modulen eier lokale personopplysninger for personer som er referert fra
`narmestelederrelasjon` eller `narmestelederbehov`. Den er ikke et generelt
personregister eller et speil av PDL. PDL er fortsatt kilden til opplysningene.

`person` eier:

- tabellen `person`, skjemaet og alle skriv til den;
- idempotent registrering av personer som mangler lokalt, med navn og
  fødselsdato når skrivestien allerede har dem;
- asynkron beriking av personer som er registrert uten opplysninger, med
  status, forsøk og backoff;
- behandling av PDLs `NAVN_V1`-hendelser for registrerte personer;
- sletting av personrader som ikke lenger er referert.

`narmestelederbehov` eier fortsatt behovets tilstand og Dialogporten-flyten.
`narmestelederrelasjon` eier fortsatt relasjoner og reglene for å etablere og
bryte dem. PDL-klienten blir i `integration/pdl`, og
`PersonalIdentificationNumber` blir i `ident`.

### Personen registreres atomisk, helst med opplysninger

Når en modul lagrer en ny personreferanse i en relasjon eller et behov, skal
den registrere personen dersom personen mangler. Referansen og
personregistreringen skal committes i samme PostgreSQL-transaksjon. Dette
gjelder begge personrollene når en ansatt eller nærmeste leder refereres.

Hovedregelen er å registrere personen med opplysninger. Når skrivestien
allerede har slått opp personen i PDL, slik etablering og oppfylling via API
gjør for å validere, lagres navn og fødselsdato som `ENRICHED` i samme
transaksjon som referansen. PDL-oppslaget skjer før transaksjonen, aldri inne
i den. Opplysningene som lagres, skal komme fra et ferskt PDL-oppslag i samme
forespørsel, ikke fra cache. `NAVN_V1` oppdaterer bare registrerte personer,
så en navneendring mellom et cachet oppslag og registreringen ville aldri blitt
rettet. Når tjenesten tar imot alle forespørsler om nærmeste leder selv, i
stedet for via team sykmeldings Kafka-flyt, er dette den vanlige veien for nye
relasjoner.

Bare når skrivestien mangler opplysningene, registreres personen som `PENDING`
og berikes asynkront. Etter overgangen gjelder dette i praksis behov fra en
sendt sykmelding der den sykmeldte ikke er registrert fra før. Fram til
overgangen gjelder det også relasjoner fra team sykmeldings Leesah-flyt, og
backfill av historiske personer bruker samme vei. Konsumenten av sendte
sykmeldinger slår ikke opp i PDL for å unngå `PENDING`. Den skal ikke være
avhengig av PDL, og asynkron beriking er eneste reservevei.

Registreringen går gjennom en smal application-kontrakt som eies av `person`.
Den lagrer opplysninger bare når personraden mangler. Eksisterende
opplysninger og berikingsstatus endres ikke, fordi beriking og `NAVN_V1` eier
endringer i lagrede opplysninger. Ved gjentatt behandling skal registreringen
sikres selv om behovet eller relasjonen allerede finnes.

En databasefeil ruller tilbake hele den lokale transaksjonen og sendes videre
til kalleren. Normal registrering skal ikke bygge på separate commits med
etterfølgende reparasjon. Felles commit og rollback må bevises i
implementeringen uten å eksponere Exposed/JDBC i application-kontrakten eller
la andre moduler skrive direkte til `person`.

Selve registreringen kaller ikke PDL eller Dialogporten. Asynkron beriking
skjer etter commit og kan forsøkes på nytt uavhengig av Dialogporten. Et behov
som venter på beriking, kan bli stående i `BEHOV_CREATED` til personen har et
brukbart navn. `person` eier berikingsutfallet; `narmestelederbehov` avgjør
hvordan utfallet påvirker behovet og om en dialog kan opprettes.

Atomisiteten gjelder de lokale databaseskrivene, ikke Kafka-publisering eller
andre eksterne effekter. Den synkrone PDL-valideringen ved etablering av
relasjoner beholdes, og registreringen gjenbruker opplysningene fra den.

### Konsumentene leser, men eier ikke persondata

Behovs- og relasjonsmodulen kan definere egne private, skrivebeskyttede
tabelldefinisjoner for å kombinere personopplysninger med egne data. De
importerer ikke `person` sine tabeller, repositories eller adaptere.
Skjemaendringer må fortsatt ta hensyn til disse leserne.

Lesestier for behov skal hente navn lokalt uten PDL-kall eller skriving.
Den claim-baserte Dialogporten-workeren er eneste sted som oppretter dialoger
for behov, og skal vente på et brukbart navn før den gjør det. Dette innføres
gradvis i #602; ADR-en alene endrer ikke dagens kjøretid.

### Sletting følger referansene

`person` skal slette en personrad bare når verken `narmeste_leder` eller
`nl_behov` refererer til personen som ansatt eller nærmeste leder. Inaktive
relasjoner og lukkede eller utløpte behov teller fortsatt som referanser.

Modulen kontrollerer referansene gjennom egne skrivebeskyttede
tabelldefinisjoner. Det gir ingen kodeavhengighet tilbake til behovs- eller
relasjonsmodulen. Sletting skal være avgrenset per kjøring og koordinert med
samtidig registrering og referanseskriving, slik at en ny referanse ikke
mister personraden. En ubeskyttet sjekk før sletting er ikke tilstrekkelig.

Slettingen endrer aldri behov eller relasjoner. Hvor lenge disse beholdes,
krever egne retention-beslutninger; denne ADR-en fastsetter ikke
oppbevaringstiden deres.

## Kontekst

#602 fjerner den uforvaltede navnekopien i `nl_behov`. I dag eier
`narmestelederrelasjon` tabellen `person`, berikingen og PDL-navneoppdateringene
som en lokal `RelationPerson`-projeksjon. Når behov også skal lese derfra,
trenger opplysningene en eier som ikke forutsetter at en relasjon finnes.

Leesah-lagringen skriver allerede relasjoner og manglende personrader i samme
transaksjon. Flytting av eierskapet skal bevare denne garantien, ikke skape et
nytt vindu med referanser uten personrader. Atomisk registrering gjelder
uansett inngang og avhenger ikke av når overgangen bort fra team sykmeldings
Kafka-flyt skjer.

Tjenesten skal snart ta imot alle forespørsler om nærmeste leder selv. Da har
skrivestien for nye relasjoner allerede validerte opplysninger fra PDL, og
asynkron beriking trengs bare der skrivestien mangler dem.

## Alternativer vurdert

### Beholde eierskapet i `narmestelederrelasjon`

Det gir færre moduler, men gjør behov uten en relasjon avhengige av
relasjonsmodulens personlivsløp. Beriking, PDL-oppdateringer og sletting gjelder
begge konsumentene og får derfor en egen eier.

### Registrere i en separat transaksjon og reparere senere

Det gjør det enklere å isolere repository-transaksjonene, men et krasj mellom
commitene kan etterlate en referanse uten personrad. Vi velger atomisk
registrering for normale skriv og bruker backfill for historiske hull.

### Registrere alle personer som `PENDING`

Det gir én registreringsvei, men kaster opplysninger skrivestien allerede har
validert. Den vanlige veien får da et unødvendig vindu uten navn. Vi bruker
`PENDING` bare når skrivestien mangler opplysningene.

### Slå opp i PDL fra konsumenten av sendte sykmeldinger

Det ville gjort `PENDING` sjeldnere, men gjør konsumenten avhengig av PDL og
gir to reserveveier i stedet for én. Behov fra sykmeldinger kan vente på navn.

### Dele en skrivbar tabell eller et repository

Det gjør felles transaksjoner enkle, men sprer skrivereglene og eierskapet på
flere moduler. Vi velger en registreringskontrakt med én eier. Private
lesedefinisjoner beholder en bevisst skjemakobling for oppslag og joins.

## Konsekvenser

- Kodeavhengigheten går fra behovs- og relasjonsmodulen til `person`, aldri
  tilbake. Løsningen er fortsatt én applikasjon med én database.
- Registrering med opplysninger gir et brukbart navn i samme commit som
  referansen. `PENDING`-registrering garanterer bare en personrad, så
  dialogsending må tåle venting og feil uten å opprette dialoger uten navn.
- Etablering må hente personene ferskt fra PDL, med fødselsdato i tillegg til
  navn. Det gir opptil to flere PDL-kall per etablering enn dagens cachede
  oppslag.
- Nye skriv må oppfylle registreringsgarantien før opprydding aktiveres.
  Implementeringen må bevise rollback, idempotent registrering og sikker sletting
  ved samtidige referanseskriv.
- Innføringen deles opp i #602: flytt eierskapet, tilpass skrivere og
  etterfyll historiske personer uten å overskrive eksisterende opplysninger.
  Behold lesbarheten under overgangen. Navnekopien i `nl_behov` fjernes først
  fra kode, deretter med en egen Flyway-migrering når ingen kjørende versjon
  bruker kolonnene.
- Dette dokumentet og endringen i arkitekturbeskrivelsen er et forslag.
  De flytter ikke kode, endrer ikke database- eller hendelsesskjemaer og
  endrer ikke autentisering, autorisasjon eller leveringsgarantier.
