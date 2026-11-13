# Regel for e-postadresse til nærmeste leder

Adressen brukes til e-postvarsler til arbeidsgivere. Regelen godtar bare
adresser et varsel kan sendes til. Den ligger i
`narmestelederrelasjon.domain.EmailAddress`. Godtatte og avviste adresser, med
årsak, står i
[`EmailAddressTest`](../src/test/kotlin/no/nav/syfo/narmestelederrelasjon/domain/EmailAddressTest.kt).

## Bruk

- **Innmelding:** Ugyldig adresse gir `400`, og hele innmeldingen avvises.
- **`/employee/linemanager`:** Ugyldig adresse utelates og telles i
  `syfo_narmesteleder_employee_linemanager_discarded_email_address_total`.

## Hvorfor

Et varsel valideres to steder før det sendes:

1. arbeidsgiver-notifikasjon validerer `epostadresse` ([`NyNotifikasjonFelles.kt`](https://github.com/navikt/arbeidsgiver-notifikasjon-produsent-api/blob/784406334bbe6f3881b6a6b9db6d05bca25a8ea0/app/src/main/kotlin/no/nav/arbeidsgiver/notifikasjon/produsent/api/NyNotifikasjonFelles.kt#L163)) med [`Validators.Email`](https://github.com/navikt/arbeidsgiver-notifikasjon-produsent-api/blob/ffddafc312e42e22980fa3b86c29b4e4434bfb10/app/src/main/kotlin/no/nav/arbeidsgiver/notifikasjon/infrastruktur/graphql/GraphQLValidation.kt#L77): `^[\p{L}\p{N}._%+-]+@[\p{L}\p{N}.-]+\.\p{L}{2,}$`.
2. arbeidsgiver-notifikasjon bestiller varselet hos Altinn 3 notifications ([`Altinn3VarselKlient.kt`](https://github.com/navikt/arbeidsgiver-notifikasjon-produsent-api/blob/89bbe95faabab825c9aeeb4efed86672d120a32f/app/src/main/kotlin/no/nav/arbeidsgiver/notifikasjon/ekstern_varsling/Altinn3VarselKlient.kt#L60), `/notifications/api/v1/future/orders`). Altinn validerer adressen med [`RecipientRules.IsValidEmail`](https://github.com/Altinn/altinn-notifications/blob/d34becde184cb651e80eacd8abc86bd706a7e299/components/api/src/Altinn.Notifications/Validators/Rules/RecipientRules.cs#L238-L255).

Regelen er snittet av de to, pluss maks 254 tegn (RFC 5321). Grensen hindrer
også at regexen bruker opp stakken.

| Del | arbeidsgiver-notifikasjon | Altinn 3 | Regelen vår |
|---|---|---|---|
| Tegn i lokaldelen | Alle bokstaver og sifre, `.` `_` `%` `+` `-` | a–z, 0–9, æøå og ``!#$%&'*+-=?^_`{\|}~`` | a–z, 0–9, æøå og `.` `_` `%` `+` `-` |
| Punktum i lokaldelen | Hvor som helst | Ikke først, sist eller to etter hverandre | Ikke først, sist eller to etter hverandre |
| Tegn i domenet | Alle bokstaver og sifre, `.` `-` | a–z, 0–9, æøå og `-` | a–z, 0–9, æøå og `-` |
| Domenedeler | Ingen grense | Maks 63 tegn, ikke `-` først eller sist, maks ti deler | Maks 63 tegn, ikke `-` først eller sist, maks ti deler |
| Toppdomene | Minst to bokstaver | 2–14 bokstaver a–z | 2–14 bokstaver a–z |
| Lengde | Ingen grense | Ingen grense | Maks 254 tegn |

Store og små bokstaver behandles likt.

Ingen av tjenestene dokumenterer formatet. Regelen bygger på koden i
versjonene det lenkes til, og er ikke testet mot tjenestene.

## Endring

Endrer en av tjenestene valideringen, oppdater regelen og denne siden. Sammenlign
med siste versjon av filene over. En strengere regel gir `400` på innmeldinger
som går gjennom i dag.
