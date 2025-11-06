import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode
import io.ktor.http.fullPath
import io.ktor.http.isSuccess
import io.mockk.coEvery
import java.time.Instant
import java.util.*
import net.datafaker.Faker
import no.nav.syfo.aareg.client.AaregClient
import no.nav.syfo.aareg.client.FakeAaregClient
import no.nav.syfo.application.auth.JwtIssuer
import no.nav.syfo.application.auth.maskinportenIdToOrgnumber
import no.nav.syfo.narmesteleder.db.NarmestelederBehovEntity
import no.nav.syfo.narmesteleder.domain.BehovStatus
import no.nav.syfo.narmesteleder.domain.Linemanager
import no.nav.syfo.narmesteleder.domain.LinemanagerRevoke
import no.nav.syfo.narmesteleder.domain.Manager
import no.nav.syfo.narmesteleder.kafka.model.LeesahStatus
import no.nav.syfo.texas.client.OrganizationId
import no.nav.syfo.texas.client.TexasHttpClient
import no.nav.syfo.texas.client.TexasIntrospectionResponse
import no.nav.syfo.texas.client.TexasResponse

val faker = Faker(Random(Instant.now().epochSecond))

fun manager(): Manager = Manager(
    nationalIdentificationNumber = faker.numerify("###########"),
    firstName = faker.name().firstName(),
    lastName = faker.name().lastName(),
    mobile = faker.phoneNumber().cellPhone(),
    email = faker.internet().emailAddress(),
)

fun linemanager(): Linemanager = Linemanager(
    manager = manager(),
    employeeIdentificationNumber = faker.numerify("###########"),
    orgnumber = faker.numerify("#########"),
)

fun linemanagerRevoke(): LinemanagerRevoke = LinemanagerRevoke(
    employeeIdentificationNumber = faker.numerify("###########"),
    orgnumber = faker.numerify("#########"),
)

fun nlBehovEntity() = NarmestelederBehovEntity(
    id = UUID.randomUUID(),
    orgnummer = faker.numerify("#########"),
    hovedenhetOrgnummer = faker.numerify("#########"),
    sykmeldtFnr = faker.numerify("###########"),
    narmestelederFnr = faker.numerify("###########"),
    leesahStatus = LeesahStatus.DEAKTIVERT_NY_LEDER.name,
    behovStatus = BehovStatus.RECEIVED,
    avbruttNarmesteLederId = UUID.randomUUID(),
)
fun createMockToken(
    ident: String,
    supplierId: String? = null,
    issuer: String = "https://test.maskinporten.no"
): String {
    val hmacSecet = "not_for_prod!"
    val algorithm = Algorithm.HMAC256(hmacSecet)

    val builder = JWT.create()
    builder
        .withKeyId("fake")
        .withIssuer(issuer)
    if (issuer.contains(JwtIssuer.MASKINPORTEN.value!!)) {
        builder.withClaim("consumer", """{"authority": "some-authority", "ID": "$ident"}""")
        if (supplierId != null) {
            builder.withClaim("supplier", """{"authority": "some-authority", "ID": "$supplierId"}""")
        }
    }
    if (issuer.contains(JwtIssuer.TOKEN_X.value!!)) {
        builder.withClaim("pid", ident)
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
        repeat(count) { add(faker.regexify("$prefix[0-9]{$orgNumLength}")) }
    }

fun addMaskinportenOrgPrefix(orgNumber: String): String =
    "0192:$orgNumber"

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

fun TexasHttpClient.defaultMocks(
    pid: String? = null,
    acr: String? = null,
    scope: String? = null,
    navident: String? = null,
    consumer: OrganizationId = DefaultOrganization,
    supplier: OrganizationId? = null
) {
    coEvery { systemToken(any(), any()) } returns TexasResponse(
        accessToken = createMockToken(
            ident = consumer.ID,
            supplierId = supplier?.ID
        ),
        expiresIn = 3600L,
        tokenType = "Bearer",
    )

    coEvery { introspectToken(any(), any()) } answers {
        val identityProvider = firstArg<String>()

        when (identityProvider) {
            "maskinporten",
            "tokenx" -> {
                TexasIntrospectionResponse(
                    active = true,
                    pid = pid,
                    acr = acr,
                    sub = UUID.randomUUID().toString(),
                    NAVident = navident,
                    consumer = consumer,
                    supplier = supplier,
                    scope = scope,
                )
            }


            else -> TODO("Legg til identityProvider i mock")
        }
    }
}

fun TexasHttpClient.defaultMocks(pid: String = "userIdentifier", acr: String = "Level4", navident: String? = null) {
    coEvery { introspectToken(any(), any()) } returns TexasIntrospectionResponse(
        active = true,
        pid = pid,
        acr = acr,
        sub = UUID.randomUUID().toString(),
        NAVident = navident
    )
}
