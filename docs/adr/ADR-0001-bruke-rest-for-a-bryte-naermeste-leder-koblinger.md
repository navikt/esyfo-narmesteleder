# ADR-0001: Bruke REST for å bryte nærmeste leder-koblinger

**Dato:** 2026-08-21  
**Status:** Godkjent  
**Beslutningstakere:** Utviklerne i team-esyfo

## Beslutning

Interne tjenester skal bruke REST mot `esyfo-narmesteleder` når de skal be om at en nærmeste leder-kobling brytes. Dette gjelder blant annet `dinesykmeldte-backend` og Flex-tjenesten `ditt-sykefravaer`.

Beslutningen gjelder transporten av kommandoen fra konsumenten til `esyfo-narmesteleder`. Den endrer ikke hvordan `esyfo-narmesteleder` behandler eller distribuerer bruddet internt etter at forespørselen er mottatt.

## Kontekst

Flere interne tjenester må kunne bryte en nærmeste leder-kobling. Teamet måtte velge om tjenestene skulle sende kommandoen gjennom et REST-API eller publisere den på Kafka.

`esyfo-narmesteleder` eier koblingen og validerer om den kan brytes. Et REST-kall gjør det tydelig hvilken tjeneste som tar imot kommandoen, og gir konsumenten en umiddelbar bekreftelse på om forespørselen er mottatt eller avvist. Kafka ville gjort konsumentene avhengige av en ny meldingskontrakt og krevd mer infrastruktur, feilhåndtering og oppfølging.

Utviklerne drøftet alternativene 2026-08-21 og valgte REST.

## Alternativer vurdert

### Alternativ A: REST ✅ (valgt)

**Beskrivelse:** Konsumenten sender en autentisert forespørsel til `esyfo-narmesteleder`, som validerer og tar imot eller avviser kommandoen.

**Fordeler:**

- Plasserer ansvaret for å bryte koblingen hos tjenesten som eier dataene og reglene.
- Gir konsumenten et direkte svar på om forespørselen er mottatt eller avvist.
- Gjenbruker etablerte mekanismer for tjeneste-til-tjeneste-autentisering, tilgangsstyring og observerbarhet på Nais.
- Unngår et nytt Kafka-topic og en ny meldingskontrakt for en kommando med én kjent mottaker.

**Ulemper:**

- Gir synkron kobling til tilgjengeligheten og svartiden til `esyfo-narmesteleder`.
- Krever at konsumenten håndterer tidsavbrudd og midlertidige feil med kontrollert gjentakelse.
- Krever versjonering og koordinering dersom API-kontrakten endres.

**Nav-vurdering:** REST er den enkleste løsningen for en kommando med én kjent mottaker og et behov for direkte tilbakemelding. Valget begrenser unødvendig teknisk kompleksitet.

### Alternativ B: Kafka

**Beskrivelse:** Konsumenten publiserer en melding om ønsket brudd, og `esyfo-narmesteleder` konsumerer meldingen.

**Fordeler:**

- Reduserer tidsmessig kobling mellom konsument og mottaker.
- Kafka kan håndtere perioder der mottakeren er utilgjengelig.
- Passer dersom flere uavhengige konsumenter senere trenger samme hendelse.

**Ulemper:**

- Krever et nytt Kafka-topic, en versjonert meldingskontrakt, tilgangsstyring og konsumentdrift.
- Gir ikke konsumenten et direkte svar på om kommandoen er gyldig eller utført.
- Krever håndtering av duplikater, ugyldige meldinger, gjentatte feil og en eventuell feilkø.
- Behandler en kommando til én eier som om den var en hendelse for flere mottakere.

**Nav-vurdering:** Kafka gir nyttige egenskaper for hendelser og asynkrone arbeidsflyter, men tilfører mer kompleksitet enn dette behovet krever.

### Alternativ C: Gjøre ingenting

**Beskrivelse:** Beholde dagens løsning uten et felles grensesnitt for interne tjenester som skal bryte koblingen.

**Fordeler:**

- Ingen implementerings- eller migreringskostnad.

**Ulemper:**

- Tjenestene mangler en tydelig og felles måte å be om brudd.
- Ansvar og feiloppfølging blir uklart.
- Risikoen øker for tjenestespesifikke omveier og duplisert logikk.

**Nav-vurdering:** Alternativet løser ikke behovet og kan spre domenelogikk til tjenester som ikke eier koblingen.

## Nav-spesifikke vurderinger

### Sikkerhet og personvern

- **Dataklassifisering:** Forespørselen inneholder personopplysninger og skal behandles som fortrolig.
- **Autentisering:** Interne konsumenter bruker Entra ID (Azure AD) maskin-til-maskin-autentisering. `esyfo-narmesteleder` validerer tokenet via Texas.
- **Autorisasjon:** Bare navngitte applikasjoner får tilgang. Hver konsument må legges til som forhåndsautorisert klient og i inngående `accessPolicy`. API-et må i tillegg kontrollere at konsumenten har lov til å utføre bruddhandlingen for den aktuelle koblingen.
- **Dataminimering:** API-et skal bare motta identifikatorene og begrunnelsen som er nødvendig for å finne og bryte riktig kobling.
- **Logging:** Fødselsnummer, token og andre personopplysninger skal ikke logges. Logger skal bruke tekniske korrelasjons-ID-er.
- **Auditlogging:** Behovet for å dokumentere hvem som brøt koblingen, og hvordan dette skal gjøres uten personopplysninger i ordinære logger, må avklares før implementering.

### Plattform

- **Nais-konfigurasjon:** Løsningen krever inngående `accessPolicy` og forhåndsautorisasjon for `dinesykmeldte-backend` og `ditt-sykefravaer`. Konsumentene trenger tilsvarende utgående tilgang.
- **Infrastruktur:** Beslutningen oppretter ikke et nytt Kafka-topic eller andre nye plattformressurser.
- **Ressursbehov:** Den forventede trafikken håndteres av eksisterende applikasjon og skaleringsoppsett. Ressursbruken må følges under utrulling.
- **Observerbarhet:** Tjenesten skal måle antall mottatte, godkjente og avviste forespørsler samt feil og svartid per konsument, uten personopplysninger.
- **CI/CD:** Ingen ny distribusjonsmekanisme er nødvendig. API-kontrakten og tilgangene må testes som del av eksisterende pipeline.

### Team og organisasjon

- **Berørte team:** team-esyfo og team-flex som eier `ditt-sykefravaer`.
- **Ansvar:** team-esyfo eier REST-kontrakten og valideringen. Hver konsument eier feil- og gjentakelseshåndtering i egen tjeneste.
- **Kommunikasjon:** API-kontrakten, feilkoder og utrullingsrekkefølge skal avklares med alle konsumentene før de tar løsningen i bruk.

### Migrasjon

- **Bakoverkompatibilitet:** REST-endepunktet innføres additivt. Eksisterende flyter beholdes til hver konsument er migrert og verifisert.
- **Utrulling:** Konsumentene migreres én om gangen. `dinesykmeldte-backend` og `ditt-sykefravaer` kan derfor rulles ut og følges opp uavhengig.
- **Idempotens:** Gjentatte forespørsler for samme brudd skal gi samme sluttresultat og ikke opprette flere brudd.
- **Tilbakerulling:** Ved feil stoppes utrullingen for den aktuelle konsumenten, og gjeldende løsning beholdes eller gjenopprettes til feilen er rettet.
- **Ferdigkriterier:** Migreringen er ferdig når alle avtalte konsumenter bruker REST, resultatet er verifisert, og metrikker ikke viser uforklarte avvik.
- **Dekommisjonering:** Eventuelle tidligere innganger for det samme behovet fjernes først når alle konsumenter er migrert og observasjonsperioden er fullført.

## Konsekvenser

### Positive

- `esyfo-narmesteleder` beholder eierskapet til reglene for å bryte koblingen.
- Konsumentene får en tydelig kontrakt og direkte tilbakemelding.
- Teamet unngår drift og forvaltning av et nytt Kafka-topic.

### Negative

- Konsumentene blir avhengige av at REST-endepunktet er tilgjengelig.
- Teamene må koordinere kontrakt, tilgang og versjonering.
- Hver konsument må implementere trygg håndtering av tidsavbrudd og midlertidige feil.

### Risiko

| Risiko | Sannsynlighet | Konsekvens | Tiltak |
|---|---|---|---|
| Midlertidig utilgjengelighet hindrer et brudd | Middels | Bruddet blir forsinket | Bruk korte tidsavbrudd, begrenset gjentakelse med økende ventetid og tydelig varsling |
| En forespørsel gjentas etter tidsavbrudd | Middels | Samme kommando behandles flere ganger | Gjør operasjonen idempotent og mål gjentatte forespørsler |
| En tjeneste får bredere tilgang enn nødvendig | Lav | Uautorisert brudd på koblinger | Bruk navngitte klienter, eksplisitt `accessPolicy` og autorisasjon for selve bruddhandlingen |
| Bruddet kan ikke spores i ettertid | Lav | Feil eller misbruk blir vanskelig å undersøke | Avklar krav til auditlogging og registrer nødvendige sikkerhetshendelser uten personopplysninger i ordinære logger |
| Kontrakten tolkes ulikt av konsumentene | Middels | Feil kobling brytes eller forespørselen avvises | Publiser OpenAPI-kontrakt, valider input og legg til kontraktstester |

## Aksjonspunkter

- [ ] Definer og publiser REST-kontrakten, inkludert idempotens og feilkoder. Eier: team-esyfo.
- [ ] Implementer eller tilpass endepunktet og nødvendige valideringsregler. Eier: team-esyfo.
- [ ] Legg til Entra ID-forhåndsautorisasjon og inngående `accessPolicy` for hver konsument. Eier: team-esyfo.
- [ ] Legg til utgående `accessPolicy` og sikker gjentakelseshåndtering i hver konsument. Eier: konsumentteamene.
- [ ] Legg til kontraktstester og tester for autorisasjon, idempotens og feilhåndtering. Eier: team-esyfo og konsumentteamene.
- [ ] Avklar krav til auditlogging og implementer nødvendig sporbarhet. Eier: team-esyfo.
- [ ] Sett opp metrikker, logger og varsling for REST-kallene. Eier: team-esyfo.
- [ ] Migrer `dinesykmeldte-backend` og `ditt-sykefravaer` én om gangen, og verifiser resultatet. Eier: konsumentteamene.
- [ ] Informer berørte team og oppdater relevant API- og driftsdokumentasjon. Eier: team-esyfo.
