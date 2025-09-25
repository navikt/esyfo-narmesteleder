import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.mockk.coEvery
import net.datafaker.Faker
import no.nav.syfo.aareg.client.AaregClient
import no.nav.syfo.aareg.client.FakeAaregClient
import no.nav.syfo.application.auth.maskinportenIdToOrgnumber
import no.nav.syfo.narmesteleder.api.v1.NarmesteLederRelasjonerWrite
import no.nav.syfo.narmesteleder.api.v1.NarmestelederRelasjonAvkreft
import no.nav.syfo.narmesteleder.kafka.model.Leder
import no.nav.syfo.texas.client.OrganizationId
import no.nav.syfo.texas.client.TexasHttpClient
import no.nav.syfo.texas.client.TexasIntrospectionResponse
import no.nav.syfo.texas.client.TexasResponse
import java.time.Instant
import java.util.*

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

fun createMockToken(
    consumerId: String,
    supplierId: String? = null,
    issuer: String = "https://test.maskinporten.no"
): String {
    val hmacSecet = "not_for_prod!"
    val algorithm = Algorithm.HMAC256(hmacSecet)

    val builder = JWT.create()
    builder
        .withKeyId("fake")
        .withClaim("consumer", """{"authority": "some-authority", "ID": "$consumerId"}""")
        .withIssuer(issuer)
    if (supplierId != null) {
        builder.withClaim("supplier", """{"authority": "some-authority", "ID": "$supplierId"}""")
    }

    val signedToken = builder.sign(algorithm)
    return signedToken
}

val DefaultOrganization = OrganizationId(
    ID = "0192:123456789",
    authority = "some-authority",
)

/**
 * @param prefix prefix the org.num with this. Default `0192:`
 * @param count how many org numbers should be generated. Default `20`
 * @param orgNumLength the length of the org.number itself. Default `9`
 * */
fun createRandomValidOrgNumbers(
    prefix: String = "0192:",
    count: Int = 20,
    orgNumLength: Int = 9
): List<String> =
    buildList {
        repeat(count) { add(faker.regexify("$prefix:[0-9]{$orgNumLength}")) }
    }


fun getMockEngine(path: String = "", status: HttpStatusCode, headers: Headers, content: String) =
    MockEngine.Companion { request ->
        when (request.url.fullPath) {
            path -> {
                if (status.isSuccess()) {
                    respond(
                        status = status,
                        headers = headers,
                        content = content.toByteArray(Charsets.UTF_8),
                    )
                } else {
                    respond(
                        status = status,
                        headers = headers,
                        content = content,
                    )
                }
            }

            else -> error("Unhandled request ${request.url.fullPath}")
        }
    }

fun AaregClient.defaultMocks(
    arbeidstakerHovedenhet: String = maskinportenIdToOrgnumber(DefaultOrganization.ID),
    arbeidstakerUnderenhet: String? = null,
) {
    val client = FakeAaregClient(
        arbeidsstedOrgnummer = arbeidstakerUnderenhet ?: arbeidstakerHovedenhet,
        juridiskOrgnummer = arbeidstakerHovedenhet,
    )

    coEvery { getArbeidsforhold(any()) } coAnswers {
        val persIdent = firstArg<String>()
        client.getArbeidsforhold(persIdent)
    }
}

fun TexasHttpClient.defaultMocks(
    pid: String? = null,
    acr: String? = null,
    navident: String? = null,
    consumer: OrganizationId = DefaultOrganization,
    supplier: OrganizationId? = null
) {
    coEvery { systemToken(any(), any()) } returns TexasResponse(
        accessToken = createMockToken(
            consumerId = consumer.ID,
            supplierId = supplier?.ID
        ),
        expiresIn = 3600L,
        tokenType = "Bearer",
    )

    coEvery { introspectToken(any(), any()) } answers {
        val identityProvider = firstArg<String>()

        when (identityProvider) {
            "maskinporten" -> {
                TexasIntrospectionResponse(
                    active = true,
                    pid = pid,
                    acr = acr,
                    sub = UUID.randomUUID().toString(),
                    NAVident = navident,
                    consumer = consumer,
                    supplier = supplier,
                )
            }

            else -> TODO("Legg til identityProvider i mock")
        }
    }
}
