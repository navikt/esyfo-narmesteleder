# Regel for e-postadresse til nærmeste leder

E-postadressen til nærmeste leder brukes til eksterne e-postvarsler til arbeidsgivere. Regelen godtar adressene som et slikt varsel kan sendes til, og avviser resten. Regelen ligger i `narmestelederrelasjon.domain.EmailAddress`.

## Hvor regelen brukes

- **Innmelding:** En ugyldig adresse gir `400`, og hele innmeldingen avvises.
- **Oppslag i `/employee/linemanager`:** En ugyldig adresse utelates fra svaret. Metrikken `syfo_narmesteleder_employee_linemanager_discarded_email_address_total` teller dem.

## Hvorfor regelen er slik

Et e-postvarsel går gjennom to valideringer før det sendes:

1. arbeidsgiver-notifikasjon validerer `epostadresse` ([`NyNotifikasjonFelles.kt`](https://github.com/navikt/arbeidsgiver-notifikasjon-produsent-api/blob/784406334bbe6f3881b6a6b9db6d05bca25a8ea0/app/src/main/kotlin/no/nav/arbeidsgiver/notifikasjon/produsent/api/NyNotifikasjonFelles.kt#L163)) med [`Validators.Email`](https://github.com/navikt/arbeidsgiver-notifikasjon-produsent-api/blob/ffddafc312e42e22980fa3b86c29b4e4434bfb10/app/src/main/kotlin/no/nav/arbeidsgiver/notifikasjon/infrastruktur/graphql/GraphQLValidation.kt#L77): `^[\p{L}\p{N}._%+-]+@[\p{L}\p{N}.-]+\.\p{L}{2,}$`.
2. arbeidsgiver-notifikasjon bestiller varselet hos Altinn 3 notifications ([`Altinn3VarselKlient.kt`](https://github.com/navikt/arbeidsgiver-notifikasjon-produsent-api/blob/89bbe95faabab825c9aeeb4efed86672d120a32f/app/src/main/kotlin/no/nav/arbeidsgiver/notifikasjon/ekstern_varsling/Altinn3VarselKlient.kt#L60), `/notifications/api/v1/future/orders`). Altinn validerer adressen med [`RecipientRules.IsValidEmail`](https://github.com/Altinn/altinn-notifications/blob/d34becde184cb651e80eacd8abc86bd706a7e299/components/api/src/Altinn.Notifications/Validators/Rules/RecipientRules.cs#L238-L255).

Regelen vår er snittet av de to. En adresse som en av dem avviser, kan ikke få varsel. I tillegg avviser vi adresser over 254 tegn, som er grensen i RFC 5321. E-postservere avviser lengre adresser, og grensen hindrer at regexen bruker opp stakken på svært lang input.

| Del | arbeidsgiver-notifikasjon | Altinn 3 | Regelen vår |
|---|---|---|---|
| Tegn i lokaldelen | Alle bokstaver og sifre, `.` `_` `%` `+` `-` | a–z, 0–9, æøå og ``!#$%&'*+-=?^_`{\|}~`` | a–z, 0–9, æøå og `.` `_` `%` `+` `-` |
| Punktum i lokaldelen | Hvor som helst | Ikke først, sist eller to etter hverandre | Ikke først, sist eller to etter hverandre |
| Tegn i domenet | Alle bokstaver og sifre, `.` `-` | a–z, 0–9, æøå og `-` | a–z, 0–9, æøå og `-` |
| Domenedeler | Ingen grense | Maks 63 tegn, ikke `-` først eller sist, maks ti deler | Maks 63 tegn, ikke `-` først eller sist, maks ti deler |
| Toppdomene | Minst to bokstaver | 2–14 bokstaver a–z | 2–14 bokstaver a–z |
| Lengde | Ingen grense | Ingen grense | Maks 254 tegn |

Store og små bokstaver behandles likt.

### Kilden er kildekoden, ikke dokumentasjon

Ingen av tjenestene dokumenterer hvilke e-postadresser de godtar:

- Altinns dokumentasjon av Notifications API beskriver ikke formatet. Regexen i koden kalles «the Altinn 2 regex».
- Altinns [tester av `IsValidEmail`](https://github.com/Altinn/altinn-notifications/blob/3bc6f6f84881bcbd9f81fb30d72c4810dd044535/components/api/test/Altinn.Notifications.Tests/Notifications/TestingValidators/EmailNotificationOrderRequestValidatorTests.cs#L249-L266) viser at æ, ø og å godtas. De tester ikke andre bokstaver, som ö eller ü.
- I arbeidsgiver-notifikasjon har [`epostadresse` i GraphQL-skjemaet](https://github.com/navikt/arbeidsgiver-notifikasjon-produsent-api/blob/edc15732b6ba70120c840da63a36ef6030edd439/app/src/main/resources/produsent.graphql#L1677-L1681) ingen beskrivelse.

Regelen vår bygger derfor på koden i versjonene det lenkes til over. Det er ikke testet mot tjenestene at for eksempel ö avvises. Tjenestene kan endre valideringen uten varsel.

## Eksempler

| Adresse | Resultat | Årsak |
|---|---|---|
| `kari_ola%drift+tag-1@firma.no` | Godtas | |
| `ærlig@blåbær-økonomi.no` | Godtas | |
| `o'neill@firma.no`, `kari&ola@firma.no` | Avvises | arbeidsgiver-notifikasjon godtar ikke `'` og `&`. |
| `björn@firma.se`, `müller@firma.de` | Avvises | Altinn 3 godtar bare æ, ø og å utenom a–z. |
| `ola..nordmann@firma.no`, `.ola@firma.no` | Avvises | Altinn 3 godtar ikke punktum først, sist eller to etter hverandre. |
| `ærlig@blåbær.økonomi` | Avvises | Altinn 3 krever toppdomene med bokstavene a–z. |
| `ola٣@firma.no` | Avvises | Altinn 3 godtar bare sifrene 0–9. |

## Når regelen må endres

Endrer arbeidsgiver-notifikasjon eller Altinn 3 valideringen sin, må regelen og denne siden oppdateres. Sammenlign filene det lenkes til over, med siste versjon i de to repoene. En strengere regel gir `400` på innmeldinger fra LPS-er som går gjennom i dag.
