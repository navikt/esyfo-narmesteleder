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
- idempotent registrering av personer som mangler lokalt;
- asynkron beriking av navn og fødselsdato, med status, forsøk og backoff;
- behandling av PDLs `NAVN_V1`-hendelser for registrerte personer;
- sletting av personrader som ikke lenger er referert.

`narmestelederbehov` eier fortsatt behovets tilstand og Dialogporten-flyten.
`narmestelederrelasjon` eier fortsatt relasjoner og reglene for å etablere og
bryte dem. PDL-klienten blir i `integration/pdl`, og
`PersonalIdentificationNumber` blir i `ident`.

### Registrering er atomisk; beriking er asynkron

Når en modul lagrer en ny personreferanse i en relasjon eller et behov, skal
den registrere personen som `PENDING` dersom personen mangler. Referansen og
personregistreringen skal committes i samme PostgreSQL-transaksjon. Dette
gjelder også behov utløst av sykmeldinger og begge personrollene når en
ansatt eller nærmeste leder refereres.

Registreringen går gjennom en smal application-kontrakt som eies av `person`.
Den lar eksisterende opplysninger og berikingsstatus være uendret. Ved
gjentatt behandling skal registreringen sikres selv om behovet eller
relasjonen allerede finnes.

En databasefeil ruller tilbake hele den lokale transaksjonen og sendes videre
til kalleren. Normal registrering skal ikke bygge på separate commits med
etterfølgende reparasjon. Felles commit og rollback må bevises i
implementeringen uten å eksponere Exposed/JDBC i application-kontrakten eller
la andre moduler skrive direkte til `person`.

Registreringen kaller ikke PDL eller Dialogporten. Beriking skjer etter commit
og kan forsøkes på nytt uavhengig av Dialogporten. Et behov kan vente i
`BEHOV_CREATED` til personen har et brukbart navn. `person` eier
berikingsutfallet; `narmestelederbehov` avgjør hvordan utfallet påvirker
behovet og om en dialog kan opprettes.

Atomisiteten gjelder de lokale databaseskrivene, ikke Kafka-publisering eller
andre eksterne effekter. Eksisterende synkron PDL-validering ved etablering av
relasjoner endres ikke av denne beslutningen.

### Konsumentene leser, men eier ikke persondata

Behovs- og relasjonsmodulen kan definere egne private, skrivebeskyttede
tabelldefinisjoner for å kombinere personopplysninger med egne data. De
importerer ikke `person` sine tabeller, repositories eller adaptere.
Skjemaendringer må fortsatt ta hensyn til disse leserne.

Lesestier for behov skal hente navn lokalt uten PDL-kall eller skriving.
`SendDialogTask` blir eneste sted som oppretter dialoger for behov, og venter
på et brukbart navn før den gjør det. Dette innføres gradvis i #602; ADR-en
alene endrer ikke dagens kjøretid.

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
nytt vindu med referanser uten personrader. Regelen gjelder uansett inngang;
den avhenger ikke av når overgangen bort fra team sykmeldings Kafka-flyt skjer.

## Alternativer vurdert

### Beholde eierskapet i `narmestelederrelasjon`

Det gir færre moduler, men gjør behov uten en relasjon avhengige av
relasjonsmodulens personlivsløp. Beriking, PDL-oppdateringer og sletting gjelder
begge konsumentene og får derfor en egen eier.

### Registrere i en separat transaksjon og reparere senere

Det gjør det enklere å isolere repository-transaksjonene, men et krasj mellom
commitene kan etterlate en referanse uten personrad. Vi velger atomisk
registrering for normale skriv og bruker backfill for historiske hull.

### Dele en skrivbar tabell eller et repository

Det gjør felles transaksjoner enkle, men sprer skrivereglene og eierskapet på
flere moduler. Vi velger en registreringskontrakt med én eier. Private
lesedefinisjoner beholder en bevisst skjemakobling for oppslag og joins.

## Konsekvenser

- Kodeavhengigheten går fra behovs- og relasjonsmodulen til `person`, aldri
  tilbake. Løsningen er fortsatt én applikasjon med én database.
- Registrering garanterer en personrad, ikke ferdig beriking. Dialogsending
  må tåle venting og feil uten å opprette dialoger uten navn.
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
