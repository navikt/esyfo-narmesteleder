import java.time.Instant
import java.util.Random
import net.datafaker.Faker
import no.nav.syfo.narmesteleder.api.v1.NarmesteLederRelasjonerWrite
import no.nav.syfo.narmesteleder.kafka.model.Leder

val faker = Faker(Random(Instant.now().epochSecond))

fun narmesteLederRelasjon(): NarmesteLederRelasjonerWrite = NarmesteLederRelasjonerWrite(
    leder = Leder(
        fnr = faker.numerify("###########"),
        fornavn = faker.name().firstName(),
        etternavn = faker.name().lastName(),
        mobil = faker.phoneNumber().cellPhone(),
        epost = faker.internet().emailAddress(),
    ),
    sykmeldtFnr = faker.numerify("###########"),
    organisasjonsnummer = faker.numerify("#########"),
)
