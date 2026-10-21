package no.nav.syfo.narmestelederrelasjon.api

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.mockk.coVerify
import io.mockk.mockk
import no.nav.syfo.createMockToken
import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.narmestelederrelasjon.application.ActiveNarmestelederrelasjon
import no.nav.syfo.narmestelederrelasjon.application.FakeActiveNarmestelederrelasjonRepository
import no.nav.syfo.narmestelederrelasjon.application.PersonDetails
import no.nav.syfo.narmestelederrelasjon.application.PersonLookupResult
import no.nav.syfo.narmestelederrelasjon.application.revoke.RevokeActiveNarmestelederrelasjonUseCase
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
import no.nav.syfo.platform.api.API_V1_PATH
import no.nav.syfo.platform.api.ApiError
import no.nav.syfo.platform.api.ErrorType
import no.nav.syfo.platform.api.apiV1
import no.nav.syfo.platform.api.createJsonClient
import no.nav.syfo.platform.api.testRouteApplication
import no.nav.syfo.platform.auth.texas.client.TexasHttpClient
import no.nav.syfo.platform.auth.texas.stubMaskinportenSystemUser
import no.nav.syfo.platform.auth.texas.stubTokenX
import no.nav.syfo.platform.observability.assertSingleUpstreamFailureLog
import no.nav.syfo.platform.upstream.UpstreamError
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamName
import java.time.Instant
import java.util.UUID

private val resolvedIdent = PersonalIdentificationNumber("12345678902")

class RevokeActiveNarmestelederrelasjonRouteTest :
    FunSpec({
        listOf(
            Triple("maskinporten", COUNT_REVOKE_LINEMANAGER_BY_LPS, NlResponseSource.LPS_REVOKE),
            Triple("tokenx", COUNT_REVOKE_LINEMANAGER_BY_PERSONNEL_MANAGER, NlResponseSource.PERSONALLEDER_REVOKE),
        ).forEach { (provider, counter, source) ->
            test("a $provider revocation returns 202, counts by principal and keeps the Kafka source $source") {
                val fixture = RevokeRouteFixture()
                fixture.authorize(provider, caller = "11223344556")
                val before = counter.count()
                fixture.withApplication {
                    client.revoke("11223344556", provider).status shouldBe HttpStatusCode.Accepted
                }
                fixture.messages.map { it.second } shouldBe listOf(source)
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

        test("invalid payload and missing person return 400") {
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

        listOf(
            """{"employeeIdentificationNumber":"1234567890","orgNumber":"123456789","lastName":"Hansen"}""" to
                "PersonalIdentificationNumber must be exactly 11 digits",
            """{"employeeIdentificationNumber":"12345678901","orgNumber":"12345678a","lastName":"Hansen"}""" to
                "OrganizationNumber must be exactly 9 digits",
        ).forEach { (body, message) ->
            test("an invalid identifier in the body returns 400 with '$message'") {
                val fixture = RevokeRouteFixture()
                fixture.authorize("maskinporten")
                fixture.withApplication {
                    client.revoke(body = body).let {
                        it.status shouldBe HttpStatusCode.BadRequest
                        it.body<ApiError>().let { error ->
                            error.type shouldBe ErrorType.INVALID_FORMAT
                            error.message shouldBe message
                        }
                    }
                }
                fixture.lookups shouldBe emptyList()
                fixture.messages shouldBe emptyList()
            }
        }

        test("mismatched last name returns the name mismatch error") {
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
            fixture.messages shouldBe emptyList()
        }

        test("access denial returns 403 with MISSING_ORG_ACCESS") {
            val fixture = RevokeRouteFixture(access = OrganizationAccess { _, _ -> OrganizationAccessResult.Denied(DenialReason.MISSING_ORGANIZATION_ACCESS) })
            fixture.authorize("maskinporten")
            fixture.withApplication {
                client.revoke().let {
                    it.status shouldBe HttpStatusCode.Forbidden
                    it.body<ApiError>().let { error ->
                        error.type shouldBe ErrorType.MISSING_ORG_ACCESS
                        error.message shouldBe "User lacks access to organization: 123456789"
                    }
                }
            }
            fixture.messages shouldBe emptyList()
        }

        test("upstream failures return HTTP 500 with UPSTREAM_SERVICE_UNAVAILABLE") {
            val failure = UpstreamFailure(UpstreamName("pdl"), UpstreamFailureStage.RESPONSE, 200, errors = listOf(UpstreamError("server_error")))
            val fixture = RevokeRouteFixture(unavailable = failure)
            fixture.authorize("maskinporten")
            assertSingleUpstreamFailureLog {
                fixture.withApplication {
                    client.revoke().let {
                        it.status shouldBe HttpStatusCode.InternalServerError
                        it.body<ApiError>().let { error ->
                            error.type shouldBe ErrorType.UPSTREAM_SERVICE_UNAVAILABLE
                            error.message shouldBe "An upstream service is unavailable"
                        }
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
    access: OrganizationAccess = OrganizationAccess { _, _ -> OrganizationAccessResult.Granted(organizationName = null) },
    private val unavailable: UpstreamFailure? = null,
) {
    private val texas = mockk<TexasHttpClient>()
    private val repository = FakeActiveNarmestelederrelasjonRepository(
        if (active) listOf(ActiveNarmestelederrelasjon(UUID(0, 1), PersonalIdentificationNumber("10987654321"), "manager@example.test", Instant.EPOCH)) else emptyList(),
    )
    val lookups get() = repository.lookups.map { it.first }
    val messages = mutableListOf<Pair<NlAvbrutt, NlResponseSource>>()
    private val producer = object : SykmeldingNarmestelederProducer {
        override fun sendSykmeldingNLRelasjon(sykmeldingNL: NlResponse, source: NlResponseSource) = error("Unexpected relation")
        override fun sendSykmldingNLBrudd(nlAvbrutt: NlAvbrutt, source: NlResponseSource) {
            messages += nlAvbrutt to source
        }
    }
    private val useCase = RevokeActiveNarmestelederrelasjonUseCase(
        access,
        {
            unavailable?.let { PersonLookupResult.Unavailable(it) } ?: person?.let { PersonLookupResult.Found(it) } ?: PersonLookupResult.NotFound
        },
        MicrometerNameValidationMetrics(),
        repository,
        KafkaPublishNarmestelederrelasjonRevocation(producer),
    )

    fun authorize(provider: String, caller: String = "12345678901") {
        if (provider == "maskinporten") {
            texas.stubMaskinportenSystemUser()
        } else {
            texas.stubTokenX(pid = caller)
        }
    }

    fun verifyIntrospection(provider: String) {
        coVerify(exactly = 1) { texas.introspectToken(provider, any()) }
    }

    fun withApplication(test: suspend ApplicationTestBuilder.() -> Unit) {
        testRouteApplication(
            routes = {
                apiV1 {
                    registerRevokeActiveNarmestelederrelasjonApi(useCase, texas)
                }
            },
        ) {
            client = createJsonClient()
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
