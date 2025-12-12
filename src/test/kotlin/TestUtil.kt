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
import no.nav.syfo.application.auth.JwtIssuer
import no.nav.syfo.application.auth.SystemPrincipal
import no.nav.syfo.narmesteleder.db.NarmestelederBehovEntity
import no.nav.syfo.narmesteleder.domain.BehovReason
import no.nav.syfo.narmesteleder.domain.BehovStatus
import no.nav.syfo.narmesteleder.domain.Linemanager
import no.nav.syfo.narmesteleder.domain.LinemanagerRevoke
import no.nav.syfo.narmesteleder.domain.Manager
import no.nav.syfo.narmesteleder.kafka.model.LeesahStatus
import no.nav.syfo.sykmelding.kafka.model.Arbeidsgiver
import no.nav.syfo.sykmelding.kafka.model.ArbeidsgiverSykmelding
import no.nav.syfo.sykmelding.kafka.model.BrukerSvar
import no.nav.syfo.sykmelding.kafka.model.Event
import no.nav.syfo.sykmelding.kafka.model.KafkaMetadata
import no.nav.syfo.sykmelding.kafka.model.RiktigNarmesteLeder
import no.nav.syfo.sykmelding.kafka.model.SendtSykmeldingKafkaMessage
import no.nav.syfo.sykmelding.kafka.model.SykmeldingsperiodeAGDTO
import no.nav.syfo.texas.client.AuthorizationDetail
import no.nav.syfo.texas.client.OrganizationId
import no.nav.syfo.texas.client.TexasHttpClient
import no.nav.syfo.texas.client.TexasIntrospectionResponse
import no.nav.syfo.texas.client.TexasResponse
import java.time.LocalDate
import java.time.OffsetDateTime
import no.nav.syfo.ereg.client.Organisasjon
import no.nav.syfo.pdl.PdlService
import no.nav.syfo.pdl.Person
import no.nav.syfo.pdl.client.Navn

val faker = Faker(Random(Instant.now().epochSecond))

fun faker() = faker
fun manager(): Manager = Manager(
    nationalIdentificationNumber = faker.numerify("###########"),
    mobile = faker.phoneNumber().cellPhone(),
    email = faker.internet().emailAddress(),
    lastName = faker.name().lastName(),
)

fun linemanager(): Linemanager = Linemanager(
    manager = manager(),
    employeeIdentificationNumber = faker.numerify("###########"),
    orgNumber = faker.numerify("#########"),
    lastName = faker.name().lastName(),
)

fun organisasjon() = Organisasjon(
    organisasjonsnummer = faker.numerify("#########"),
    inngaarIJuridiskEnheter = listOf(Organisasjon(organisasjonsnummer = faker.numerify("#########")))
)

fun linemanagerRevoke(): LinemanagerRevoke = LinemanagerRevoke(
    employeeIdentificationNumber = faker.numerify("###########"),
    orgNumber = faker.numerify("#########"),
    lastName = faker.name().lastName(),
)

fun nlBehovEntity() = NarmestelederBehovEntity(
    id = UUID.randomUUID(),
    orgnummer = faker.numerify("#########"),
    hovedenhetOrgnummer = faker.numerify("#########"),
    sykmeldtFnr = faker.numerify("###########"),
    narmestelederFnr = faker.numerify("###########"),
    behovReason = BehovReason.valueOf(LeesahStatus.DEAKTIVERT_NY_LEDER.name),
    behovStatus = BehovStatus.BEHOV_CREATED,
    avbruttNarmesteLederId = UUID.randomUUID(),
)

fun createMockToken(
    ident: String,
    supplierId: String? = null,
    issuer: String = "https://test.maskinporten.no",
    expiresAt: Instant = Instant.now(),
    scope: String = "testscope",
): String {
    val hmacSecet = "not_for_prod!"
    val algorithm = Algorithm.HMAC256(hmacSecet)

    val builder = JWT.create()
    builder
        .withKeyId("fake")
        .withIssuer(issuer)
        .withExpiresAt(expiresAt)
        .withClaim("scope", scope)
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

val DefaultSystemPrincipal = SystemPrincipal(
    "0192:123456789",
    "token",
    "0192:systemowner",
    "systemId"
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


fun PdlService.prepareGetPersonResponse(manager: Manager) {
    prepareGetPersonResponse(manager.nationalIdentificationNumber, manager.lastName)
}

fun PdlService.prepareGetPersonResponse(fnr: String, lastName: String) {
    val name = faker().name()
    coEvery {
        getPersonFor(fnr)
    } returns Person(
        name = Navn(
            fornavn = name.firstName(),
            mellomnavn = "",
            etternavn = lastName,
        ),
        nationalIdentificationNumber = fnr,
    )
}

fun TexasHttpClient.defaultMocks(
    pid: String? = null,
    acr: String? = null,
    scope: String? = null,
    navident: String? = null,
    systemBrukerOrganisasjon: OrganizationId? = DefaultOrganization,
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
                    authorizationDetails = systemBrukerOrganisasjon?.let {
                        listOf(
                            AuthorizationDetail(
                                type = "urn:altinn:systemuser",
                                systemuserOrg = systemBrukerOrganisasjon,
                                systemuserId = listOf("some-user-id"),
                                systemId = "some-system-id"
                            )
                        )
                    }
                )
            }


            else -> TODO("Legg til identityProvider i mock")
        }
    }
}

fun defaultSendtSykmeldingMessage(
    fnr: String = "12345678901",
    orgnummer: String = "123456789",
    juridiskOrgnummer: String? = "987654321",
    sykmeldingsperioder: List<SykmeldingsperiodeAGDTO> = listOf(
        SykmeldingsperiodeAGDTO(fom = LocalDate.now().minusDays(5), tom = LocalDate.now().plusDays(5))
    ),
    riktigNarmesteLeder: RiktigNarmesteLeder? = null
): SendtSykmeldingKafkaMessage {
    return SendtSykmeldingKafkaMessage(
        kafkaMetadata = KafkaMetadata(
            sykmeldingId = "sykmelding-123",
            timestamp = OffsetDateTime.now(),
            fnr = fnr,
            source = "test"
        ),
        event = Event(
            sykmeldingId = "sykmelding-123",
            timestamp = OffsetDateTime.now(),
            brukerSvar = BrukerSvar(riktigNarmesteLeder = riktigNarmesteLeder),
            arbeidsgiver = Arbeidsgiver(
                orgnummer = orgnummer,
                juridiskOrgnummer = juridiskOrgnummer,
                orgNavn = "Test Bedrift AS"
            )
        ),
        sykmelding = ArbeidsgiverSykmelding(sykmeldingsperioder = sykmeldingsperioder)
    )
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
