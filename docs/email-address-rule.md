# Regel for e-postadresse til nærmeste leder

E-postadressen til nærmeste leder brukes til eksterne e-postvarsler til arbeidsgivere. Regelen godtar adressene som et slikt varsel kan sendes til, og avviser resten. Regelen ligger i `narmestelederrelasjon.domain.EmailAddress`.

## Hvor regelen brukes

- **Innmelding:** En ugyldig adresse gir `400`, og hele innmeldingen avvises.
- **Oppslag i `/employee/linemanager`:** En ugyldig adresse utelates fra svaret. Metrikken `syfo_narmesteleder_employee_linemanager_discarded_email_address_total` teller dem.

## Hvorfor regelen er slik

Et e-postvarsel går gjennom to valideringer før det sendes:

1. [arbeidsgiver-notifikasjon](https://github.com/navikt/arbeidsgiver-notifikasjon-produsent-api) validerer `epostadresse` med `Validators.Email` i `GraphQLValidation.kt`: `^[\p{L}\p{N}._%+-]+@[\p{L}\p{N}.-]+\.\p{L}{2,}$`.
2. arbeidsgiver-notifikasjon bestiller varselet hos [Altinn 3 notifications](https://github.com/Altinn/altinn-notifications) (`/notifications/api/v1/future/orders`). Altinn validerer adressen med `RecipientRules.IsValidEmail`.

Regelen vår er snittet av de to. En adresse som en av dem avviser, kan ikke få varsel.

| Del | arbeidsgiver-notifikasjon | Altinn 3 | Regelen vår |
|---|---|---|---|
| Tegn i lokaldelen | Bokstaver, sifre, `.` `_` `%` `+` `-` | a–z, sifre, æøå og ``!#$%&'*+-=?^_`{\|}~`` | a–z, sifre, æøå og `.` `_` `%` `+` `-` |
| Punktum i lokaldelen | Hvor som helst | Ikke først, sist eller to etter hverandre | Ikke først, sist eller to etter hverandre |
| Tegn i domenet | Bokstaver, sifre, `.` `-` | a–z, sifre, æøå og `-` | a–z, sifre, æøå og `-` |
| Domenedeler | Ingen grense | Maks 63 tegn, ikke `-` først eller sist, maks ti deler | Maks 63 tegn, ikke `-` først eller sist, maks ti deler |
| Toppdomene | Minst to bokstaver | 2–14 bokstaver a–z | 2–14 bokstaver a–z |

Store og små bokstaver behandles likt.

## Eksempler

| Adresse | Resultat | Årsak |
|---|---|---|
| `kari_ola%drift+tag-1@firma.no` | Godtas | |
| `ærlig@blåbær-økonomi.no` | Godtas | |
| `o'neill@firma.no`, `kari&ola@firma.no` | Avvises | arbeidsgiver-notifikasjon godtar ikke `'` og `&`. |
| `björn@firma.se`, `müller@firma.de` | Avvises | Altinn 3 godtar bare æ, ø og å utenom a–z. |
| `ola..nordmann@firma.no`, `.ola@firma.no` | Avvises | Altinn 3 godtar ikke punktum først, sist eller to etter hverandre. |
| `ærlig@blåbær.økonomi` | Avvises | Altinn 3 krever toppdomene med bokstavene a–z. |

## Når regelen må endres

Endrer arbeidsgiver-notifikasjon eller Altinn 3 valideringen sin, må regelen og denne siden oppdateres. En strengere regel gir `400` på innmeldinger fra LPS-er som går gjennom i dag.
