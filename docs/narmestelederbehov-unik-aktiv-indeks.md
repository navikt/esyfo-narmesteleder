# Én aktiv rad per ansatt og organisasjon

[Issue #605](https://github.com/navikt/esyfo-narmesteleder/issues/605) innfører
`uq_nl_behov_active_employee_org` i V30. Indeksen tillater høyst ett aktivt
narmestelederbehov per `(sykemeldt_fnr, orgnummer)`, samlet for
`BEHOV_CREATED` og `DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION`.
Historiske rader og feilstatuser reserverer ikke plassen.

## Før utrulling

Bruk godkjent databaseverktøy og tilgang for miljøet. Auditen returnerer bare
antall par, ikke fødselsnummer eller rå rader:

```sql
SELECT COUNT(*) AS duplicate_active_pairs
FROM (
    SELECT sykemeldt_fnr, orgnummer
    FROM nl_behov
    WHERE behov_status IN (
        'BEHOV_CREATED',
        'DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION'
    )
    GROUP BY sykemeldt_fnr, orgnummer
    HAVING COUNT(*) > 1
) duplicates;
```

Stopp utrullingen hvis resultatet er større enn null. Brukerens separate
oppryddings-PR med V29 må deployes først; V29 er bevisst ikke med her.
Oppryddingen må avklare hvilke behov og dialoger som skal beholdes.
V30 sletter, oppfyller eller utløper ingen rader. Kjør auditen på nytt etter
oppryddingen og rett før indeksbyggingen. Null funn garanterer ikke at det
ikke oppstår nye duplikater før eller under byggingen.

Mål tabellstørrelse og relevant byggetid før valg av migreringskjøring.
`CREATE UNIQUE INDEX CONCURRENTLY` reduserer blokkering av skriving, men Flyway
venter til byggingen er ferdig. Produksjonsmanifestets startup-probe har
`periodSeconds: 10` og `failureThreshold: 720`, altså omtrent to timer.
Bruk oppstartsmigrering bare når forventet byggetid **og** venting på Flyways
migreringslås ligger godt innenfor dette budsjettet for alle nye replikaer.
Ellers må en separat migreringsjobb kjøre Flyway før applikasjonsutrulling,
med tilstrekkelig kjøretidsgrense og en eksplisitt strategi for omstart og
feilretting.

## Deploy-rekkefølge

Konflikthåndteringen bør være deployet til alle replikaer før indeksen
aktiveres. Denne slicen leverer kode og migrering sammen; ved vanlig rullerende
deploy kan gamle pods fortsatt kjøre når en ny pod starter Flyway.
Gamle pods uten konflikthåndteringen kan da få exception ved en innsetting
som taper et kappløp, både under indeksbyggingen og etter at indeksen finnes.
Avklar derfor utrullingsrekkefølge og migreringskjøring før deploy.

V30 har `executeInTransaction=false` i sin `.sql.conf`.
Flyways `isTransactionalLock = false` beholdes. Ikke legg V30 til
`REPAIRABLE_FLYWAY_VERSIONS` eller bruk `IF NOT EXISTS`: det kan skjule en
ugyldig indeks med samme navn.

## Feilretting ved avbrutt eller mislykket bygging

1. Stopp nye migreringsforsøk. Kontroller indeksstatus og Flyway-historikk:

   ```sql
   SELECT i.indisunique, i.indisvalid,
          pg_get_expr(i.indpred, i.indrelid) AS predicate
   FROM pg_index i
   JOIN pg_class c ON c.oid = i.indexrelid
   JOIN pg_namespace n ON n.oid = c.relnamespace
   WHERE n.nspname = 'public'
     AND c.relname = 'uq_nl_behov_active_employee_org';

   SELECT version, description, success
   FROM flyway_schema_history
   WHERE version = '30';
   ```

2. Bekreft at ingen indeksbygging fortsatt kjører. En kontroll uten
   spørringstekst eller persondata er:

   ```sql
   SELECT pid, phase
   FROM pg_stat_progress_create_index
   WHERE relid = 'public.nl_behov'::regclass;
   ```

   Avklar også eventuelle andre migreringsjobber eller ventende bygg før
   inngrep. PostgreSQL tillater bare ett samtidig indeksbygg per tabell.
3. Hvis indeksen finnes og `indisvalid = false`, dropp den eksplisitt utenfor
   en transaksjon, med godkjent operatørtilgang:

   ```sql
   DROP INDEX CONCURRENTLY public.uq_nl_behov_active_employee_org;
   ```

   Ikke dropp en gyldig indeks som del av denne prosedyren. En **ugyldig unik
   indeks kan fortsatt håndheve unikhet**; et feilet bygg betyr ikke at
   regelen er inaktiv.
4. Rett årsaken, inkludert eventuelle nye aktive duplikater gjennom avtalt
   opprydding, og kjør auditen igjen. Reparer en eventuell feilet
   Flyway-oppføring med en eksplisitt, godkjent Flyway `repair()`, og kjør
   migreringen på nytt. `repair()` alene reparerer **ikke** indeksen.
   Oppstartsreparasjonens allowlist dekker bare V25–V27; V30 er ikke med.
5. Kontroller at V30 er vellykket i historikken, og at indeksen er unik,
   gyldig og har predikatet for nøyaktig de to aktive statusene.

Testene bruker PostgreSQL 18 og dekker tom database, oppgradering fra V28,
mislykket bygg med aktive duplikater, katalogstatus og konkurrerende
opprettelse. De måler ikke byggetid eller låseventing i produksjon og gir ikke
en garanti om nøyaktig én Dialogporten-leveranse ved gjentatte forsøk for
samme behov.
