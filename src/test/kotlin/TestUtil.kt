import io.mockk.coEvery
import java.time.Instant
import java.util.Random
import java.util.UUID
import net.datafaker.Faker
import no.nav.syfo.narmesteleder.api.v1.NarmesteLederRelasjonerWrite
import no.nav.syfo.narmesteleder.api.v1.NarmestelederRelasjonAvkreft
import no.nav.syfo.narmesteleder.kafka.model.Leder
import no.nav.syfo.texas.client.TexasHttpClient
import no.nav.syfo.texas.client.TexasIntrospectionResponse
import no.nav.syfo.texas.client.TexasResponse

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

fun narmesteLederAvkreft(): NarmestelederRelasjonAvkreft = NarmestelederRelasjonAvkreft(
    sykmeldtFnr = faker.numerify("###########"),
    organisasjonsnummer = faker.numerify("#########"),
)

fun TexasHttpClient.defaultMocks(pid: String = "userIdentifier", acr: String = "Level4", navident: String? = null) {
    coEvery { introspectToken(any(), any()) } returns TexasIntrospectionResponse(
        active = true,
        pid = pid,
        acr = acr,
        sub = UUID.randomUUID().toString(),
        NAVident = navident
    )
}
