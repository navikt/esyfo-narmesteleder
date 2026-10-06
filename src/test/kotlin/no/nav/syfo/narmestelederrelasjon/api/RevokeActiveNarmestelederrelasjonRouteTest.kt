package no.nav.syfo.narmestelederrelasjon.api

import DefaultOrganization
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import createMockToken
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.jackson.jackson
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import no.nav.syfo.application.api.API_V1_PATH
import no.nav.syfo.application.api.ApiError
import no.nav.syfo.application.api.ErrorType
import no.nav.syfo.application.api.installContentNegotiation
import no.nav.syfo.application.api.installStatusPages
import no.nav.syfo.application.auth.AddTokenIssuerPlugin
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmesteleder.domain.PersonalIdentificationNumber
import no.nav.syfo.narmestelederbehov.api.registerFulfillNarmestelederbehovApi
import no.nav.syfo.narmestelederbehov.api.registerListNarmestelederbehovApi
import no.nav.syfo.narmestelederbehov.application.FulfillNarmestelederbehovUseCase
import no.nav.syfo.narmestelederbehov.application.ListNarmestelederbehovUseCase
import no.nav.syfo.narmestelederrelasjon.api.model.LinemanagerRevoke
import no.nav.syfo.narmestelederrelasjon.application.ActiveNarmestelederrelasjon
import no.nav.syfo.narmestelederrelasjon.application.ActiveNarmestelederrelasjonRepository
import no.nav.syfo.narmestelederrelasjon.application.PersonDetails
import no.nav.syfo.narmestelederrelasjon.application.PersonLookup
import no.nav.syfo.narmestelederrelasjon.application.RevokeActiveNarmestelederrelasjonUseCase
import no.nav.syfo.narmestelederrelasjon.domain.PersonNameDetails
import no.nav.syfo.narmestelederrelasjon.domain.RegisteredName
import no.nav.syfo.narmestelederrelasjon.infrastructure.KafkaPublishNarmestelederrelasjonRevocation
import no.nav.syfo.narmestelederrelasjon.infrastructure.MicrometerNameValidationMetrics
import no.nav.syfo.narmestelederrelasjon.infrastructure.kafka.NlAvbrutt
import no.nav.syfo.narmestelederrelasjon.infrastructure.kafka.NlResponse
import no.nav.syfo.narmestelederrelasjon.infrastructure.kafka.NlResponseSource
import no.nav.syfo.narmestelederrelasjon.infrastructure.kafka.SykmeldingNarmestelederProducer
import no.nav.syfo.narmestelederrelasjon.observability.COUNT_REVOKE_LINEMANAGER_BY_LPS
import no.nav.syfo.narmestelederrelasjon.observability.COUNT_REVOKE_LINEMANAGER_BY_PERSONNEL_MANAGER
import no.nav.syfo.narmestelederrelasjon.observability.COUNT_REVOKE_LINEMANAGER_WITHOUT_ACTIVE_RELATION
import no.nav.syfo.organisasjonstilgang.application.DenialReason
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccess
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessResult
import no.nav.syfo.pdl.exception.PdlRequestException
import no.nav.syfo.texas.client.TexasHttpClient
import no.nav.syfo.texas.client.TexasIntrospectionResponse
import java.time.Instant
import java.util.UUID
import no.nav.syfo.narmesteleder.domain.OrganizationNumber as RequestOrganizationNumber

private val request = LinemanagerRevoke(PersonalIdentificationNumber("12345678901"), RequestOrganizationNumber("123456789"), "Hansen")
private val resolvedIdent = PersonIdent("12345678902")

class RevokeActiveNarmestelederrelasjonRouteTest :
    FunSpec({
        test("Maskinporten and TokenX revoke once, publish the resolved ident and preserve Kafka source") {
            listOf(
                Triple("maskinporten", "12345678901", NlResponseSource.LPS_REVOKE),
                Triple("tokenx", "12345678902", NlResponseSource.ARBEIDSTAGER_REVOKE),
                Triple("tokenx", "11223344556", NlResponseSource.PERSONALLEDER_REVOKE),
            ).forEach { (provider, caller, source) ->
                val fixture = RevokeRouteFixture()
                fixture.authorize(provider, caller)
                val counter = if (provider == "maskinporten") COUNT_REVOKE_LINEMANAGER_BY_LPS else COUNT_REVOKE_LINEMANAGER_BY_PERSONNEL_MANAGER
                val before = counter.count()
                fixture.withApplication {
                    client.revoke(caller, provider).status shouldBe HttpStatusCode.Accepted
                }
                fixture.messages.size shouldBe 1
                fixture.messages.single().let { (payload, publishedSource) ->
                    payload.sykmeldtFnr shouldBe resolvedIdent.value
                    payload.orgnummer shouldBe request.orgNumber.value
                    publishedSource shouldBe source
                }
                fixture.lookups shouldBe listOf(resolvedIdent)
                counter.count() shouldBe before + 1.0
                fixture.verifyIntrospection(provider)
            }
        }

        test("no active relation returns 204 and never publishes") {
            val fixture = RevokeRouteFixture(active = false)
            fixture.authorize("maskinporten")
            val before = COUNT_REVOKE_LINEMANAGER_WITHOUT_ACTIVE_RELATION.count()
            fixture.withApplication { client.revoke().status shouldBe HttpStatusCode.NoContent }
            fixture.messages shouldBe emptyList()
            COUNT_REVOKE_LINEMANAGER_WITHOUT_ACTIVE_RELATION.count() shouldBe before + 1.0
        }

        test("invalid payload and missing person return legacy 400 responses") {
            val fixture = RevokeRouteFixture(person = null)
            fixture.authorize("maskinporten")
            fixture.withApplication {
                client.revoke(body = """{"navn":"Test"}""").let {
                    it.status shouldBe HttpStatusCode.BadRequest
                    it.body<ApiError>().type shouldBe ErrorType.INVALID_FORMAT
                }
                client.revoke().let {
                    it.status shouldBe HttpStatusCode.BadRequest
                    it.body<ApiError>().let { error ->
                        error.type shouldBe ErrorType.BAD_REQUEST
                        error.message shouldBe "Could not find person in PDL"
                    }
                }
            }
            fixture.messages shouldBe emptyList()
        }

        test("mismatched last name returns the legacy error and never looks up active relations") {
            val fixture = RevokeRouteFixture(name = "Other")
            fixture.authorize("maskinporten")
            fixture.withApplication {
                client.revoke().let {
                    it.status shouldBe HttpStatusCode.BadRequest
                    it.body<ApiError>().let { error ->
                        error.type shouldBe ErrorType.EMPLOYEE_NAME_NATIONAL_IDENTIFICATION_NUMBER_MISMATCH
                        error.message shouldBe "Last name for employee on sick leave does not correspond with registered value for the given national identification number"
                    }
                }
            }
            fixture.lookups shouldBe emptyList()
            fixture.messages shouldBe emptyList()
        }

        test("revocation does not require employment or a valid stored manager email") {
            val fixture = RevokeRouteFixture(managerEmail = "invalid")
            fixture.authorize("maskinporten")
            fixture.withApplication { client.revoke().status shouldBe HttpStatusCode.Accepted }
            fixture.messages.size shouldBe 1
        }

        test("organization and resource access denials preserve status, message and error type") {
            listOf(
                Triple(DenialReason.MISSING_ORGANIZATION_ACCESS, ErrorType.MISSING_ORG_ACCESS, "User lacks access to organization: 123456789"),
                Triple(DenialReason.MISSING_RESOURCE_ACCESS, ErrorType.MISSING_ALITINN_RESOURCE_ACCESS, "User lacks access to required Altinn resource for organization: 123456789"),
                Triple(DenialReason.SYSTEM_USER_REJECTED, ErrorType.MISSING_ALITINN_RESOURCE_ACCESS, "System user does not have access to nav_syfo_oppgi-narmesteleder resource"),
            ).forEach { (reason, type, message) ->
                val fixture = RevokeRouteFixture(access = OrganizationAccess { _, _ -> OrganizationAccessResult.Denied(reason) })
                fixture.authorize("maskinporten")
                fixture.withApplication {
                    client.revoke().let {
                        it.status shouldBe HttpStatusCode.Forbidden
                        it.body<ApiError>().let { error ->
                            error.type shouldBe type
                            error.message shouldBe message
                        }
                    }
                }
                fixture.messages shouldBe emptyList()
            }
        }

        test("missing token cannot reach revoke or requirement routes") {
            val fixture = RevokeRouteFixture()
            fixture.withApplication {
                client.post("$API_V1_PATH$REVOKE_ACTIVE_NARMESTELEDERRELASJON_PATH").status shouldBe HttpStatusCode.Unauthorized
                client.get("$API_V1_PATH/linemanager/requirement").status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("PDL technical failures become generic 500 without publishing") {
            val fixture = RevokeRouteFixture(failure = PdlRequestException("PDL unavailable"))
            fixture.authorize("maskinporten")
            fixture.withApplication {
                client.revoke().let {
                    it.status shouldBe HttpStatusCode.InternalServerError
                    it.body<ApiError>().let { error ->
                        error.type shouldBe ErrorType.INTERNAL_SERVER_ERROR
                        error.message shouldBe "Internal server error"
                    }
                }
            }
            fixture.messages shouldBe emptyList()
        }
    })

private class RevokeRouteFixture(
    private val active: Boolean = true,
    private val name: String = "Hansen",
    private val person: PersonDetails? = PersonDetails(resolvedIdent, PersonNameDetails("Test", name, registeredNames = listOf(RegisteredName(name)))),
    private val managerEmail: String = "manager@example.test",
    private val access: OrganizationAccess = OrganizationAccess { _, _ -> OrganizationAccessResult.Granted(organizationName = null) },
    private val failure: Exception? = null,
) {
    private val texas = mockk<TexasHttpClient>()
    val lookups = mutableListOf<PersonIdent>()
    val messages = mutableListOf<Pair<NlAvbrutt, NlResponseSource>>()
    private val producer = object : SykmeldingNarmestelederProducer {
        override fun sendSykmeldingNLRelasjon(sykmeldingNL: NlResponse, source: NlResponseSource) = error("Unexpected relation")
        override fun sendSykmldingNLBrudd(nlAvbrutt: NlAvbrutt, source: NlResponseSource) {
            messages += nlAvbrutt to source
        }
    }
    private val useCase = RevokeActiveNarmestelederrelasjonUseCase(
        access,
        PersonLookup {
            failure?.let { throw it }
            person
        },
        MicrometerNameValidationMetrics(),
        object : ActiveNarmestelederrelasjonRepository {
            override suspend fun findActive(employeeIdent: PersonIdent, organizationNumber: OrganizationNumber): List<ActiveNarmestelederrelasjon> {
                lookups += employeeIdent
                return if (active) listOf(ActiveNarmestelederrelasjon(UUID(0, 1), PersonIdent("10987654321"), managerEmail, Instant.EPOCH)) else emptyList()
            }
        },
        KafkaPublishNarmestelederrelasjonRevocation(producer),
    )

    fun authorize(provider: String, caller: String = "12345678901") {
        if (provider == "maskinporten") {
            coEvery { texas.introspectToken("maskinporten", any()) } returns TexasIntrospectionResponse(
                active = true,
                scope = no.nav.syfo.texas.MASKINPORTEN_NL_SCOPE,
                consumer = DefaultOrganization,
                authorizationDetails = listOf(
                    no.nav.syfo.texas.client.AuthorizationDetail(
                        type = "urn:altinn:systemuser",
                        systemuserOrg = DefaultOrganization,
                        systemuserId = listOf("some-user-id"),
                        systemId = "some-system-id",
                    ),
                ),
            )
        } else {
            coEvery { texas.introspectToken("tokenx", any()) } returns TexasIntrospectionResponse(active = true, acr = "Level4", pid = caller)
        }
    }

    fun verifyIntrospection(provider: String) {
        coVerify(exactly = 1) { texas.introspectToken(provider, any()) }
    }

    fun withApplication(test: suspend ApplicationTestBuilder.() -> Unit) {
        testApplication {
            client = createClient {
                install(ContentNegotiation) {
                    jackson {
                        registerKotlinModule()
                        registerModule(JavaTimeModule())
                    }
                }
            }
            application {
                installContentNegotiation()
                installStatusPages()
                routing {
                    route(API_V1_PATH) {
                        install(AddTokenIssuerPlugin)
                        registerFulfillNarmestelederbehovApi(mockk<FulfillNarmestelederbehovUseCase>(), texas)
                        registerListNarmestelederbehovApi(mockk<ListNarmestelederbehovUseCase>(), texas)
                        registerRevokeActiveNarmestelederrelasjonApi(useCase, texas)
                    }
                }
            }
            test()
        }
    }
}

private suspend fun HttpClient.revoke(
    caller: String = "12345678901",
    provider: String = "maskinporten",
    body: String = """{"employeeIdentificationNumber":"12345678901","orgNumber":"123456789","lastName":"Hansen"}""",
) = post("$API_V1_PATH$REVOKE_ACTIVE_NARMESTELEDERRELASJON_PATH") {
    contentType(ContentType.Application.Json)
    bearerAuth(createMockToken(caller, issuer = if (provider == "maskinporten") "https://test.maskinporten.no" else "https://tokenx.example.com"))
    setBody(body)
}
