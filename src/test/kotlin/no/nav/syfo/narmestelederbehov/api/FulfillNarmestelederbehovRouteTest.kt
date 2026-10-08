package no.nav.syfo.narmestelederbehov.api

import DefaultOrganization
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import createMockToken
import defaultMocks
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.put
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
import io.mockk.spyk
import linemanager
import manager
import no.nav.syfo.application.api.API_V1_PATH
import no.nav.syfo.application.api.ApiError
import no.nav.syfo.application.api.ErrorType
import no.nav.syfo.application.api.installContentNegotiation
import no.nav.syfo.application.api.installStatusPages
import no.nav.syfo.application.auth.AddTokenIssuerPlugin
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.integration.aareg.TestAaregClient
import no.nav.syfo.integration.dinesykmeldte.DinesykmeldteClient
import no.nav.syfo.integration.dinesykmeldte.FakeDinesykmeldteClient
import no.nav.syfo.integration.ereg.CachedEregClient
import no.nav.syfo.integration.ereg.EregCache
import no.nav.syfo.integration.ereg.FakeEregClient
import no.nav.syfo.integration.ereg.Organisasjon
import no.nav.syfo.narmesteleder.domain.Manager
import no.nav.syfo.narmesteleder.domain.PersonalIdentificationNumber
import no.nav.syfo.narmestelederbehov.application.BehovPersonName
import no.nav.syfo.narmestelederbehov.application.FulfillNarmestelederbehovUseCase
import no.nav.syfo.narmestelederbehov.application.MarkDialogCompletedResult
import no.nav.syfo.narmestelederbehov.application.MarkFulfilledResult
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovDetails
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovDialog
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovRepository
import no.nav.syfo.narmestelederbehov.domain.Employee
import no.nav.syfo.narmestelederbehov.domain.Narmestelederbehov
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import no.nav.syfo.narmestelederrelasjon.application.EstablishNarmestelederrelasjonUseCase
import no.nav.syfo.narmestelederrelasjon.application.PersonDetails
import no.nav.syfo.narmestelederrelasjon.application.PersonLookup
import no.nav.syfo.narmestelederrelasjon.domain.PersonNameDetails
import no.nav.syfo.narmestelederrelasjon.domain.RegisteredName
import no.nav.syfo.narmestelederrelasjon.infrastructure.AaregEmploymentLookup
import no.nav.syfo.narmestelederrelasjon.infrastructure.DinesykmeldteActiveSykmeldingLookup
import no.nav.syfo.narmestelederrelasjon.infrastructure.KafkaPublishNarmestelederrelasjon
import no.nav.syfo.narmestelederrelasjon.infrastructure.MicrometerNameValidationMetrics
import no.nav.syfo.narmestelederrelasjon.infrastructure.kafka.FakeSykmeldingNarmestelederProducer
import no.nav.syfo.organisasjonstilgang.infrastructure.AltinnOrganizationAccess
import no.nav.syfo.organisasjonstilgang.infrastructure.altinnauthorization.AltinnAuthorizationClient
import no.nav.syfo.organisasjonstilgang.infrastructure.altinnauthorization.AltinnAuthorizationResponse
import no.nav.syfo.organisasjonstilgang.infrastructure.altinnauthorization.Decision
import no.nav.syfo.organisasjonstilgang.infrastructure.altinnauthorization.DecisionResult
import no.nav.syfo.organisasjonstilgang.infrastructure.altinntilganger.AltinnTilgangerService
import no.nav.syfo.organisasjonstilgang.infrastructure.altinntilganger.FakeAltinnTilgangerClient
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamName
import no.nav.syfo.texas.MASKINPORTEN_NL_SCOPE
import no.nav.syfo.texas.client.TexasHttpClient
import java.util.UUID

class FulfillNarmestelederbehovRouteTest :
    FunSpec({
        test("PUT /requirement/{id} 202 updates behov and sends kafka message") {
            withPutApplication { fixture ->
                val id = fixture.seed()
                val submittedManager = fixture.newManager()
                fixture.people.registerPerson(submittedManager.nationalIdentificationNumber.value, submittedManager.lastName)

                val response = client.put("$API_V1_PATH/$NARMESTELEDERBEHOV_PATH/$id") {
                    contentType(ContentType.Application.Json)
                    setBody(submittedManager)
                    bearerAuth(createMockToken(fixture.orgNumber))
                }
                response.status shouldBe HttpStatusCode.Accepted
                coVerify(exactly = 1) {
                    fixture.producer.sendSykmeldingNLRelasjon(
                        match {
                            it.sykmeldt.fnr == fixture.employeeIdent &&
                                it.orgnummer == fixture.orgNumber &&
                                it.leder.fnr == submittedManager.nationalIdentificationNumber.value
                        },
                        any(),
                    )
                }
                fixture.repository.fulfilled shouldBe listOf(NarmestelederbehovId(id))
            }
        }

        test("PUT /requirement/{id} returns upstream unavailable without publication or fulfilling the behov") {
            val failures = listOf(
                UpstreamFailure(UpstreamName("aareg"), UpstreamFailureStage.RESPONSE, 400, IllegalStateException()),
                UpstreamFailure(UpstreamName("aareg"), UpstreamFailureStage.RESPONSE, 404, IllegalStateException()),
                UpstreamFailure(UpstreamName("aareg"), UpstreamFailureStage.RESPONSE, 500, IllegalStateException()),
                UpstreamFailure(UpstreamName("aareg"), UpstreamFailureStage.REQUEST, null, java.net.SocketTimeoutException()),
                UpstreamFailure(UpstreamName("aareg"), UpstreamFailureStage.REQUEST, null, java.io.IOException()),
                UpstreamFailure(UpstreamName("aareg"), UpstreamFailureStage.RESPONSE, 200, IllegalStateException()),
                UpstreamFailure(UpstreamName("texas"), UpstreamFailureStage.TOKEN_EXCHANGE, 503, IllegalStateException()),
            )
            failures.forEach { failure ->
                withPutApplication { fixture ->
                    val id = fixture.seed()
                    fixture.aareg.setFailure(failure)
                    val response = client.put("/api/v1/linemanager/requirement/$id") {
                        contentType(ContentType.Application.Json)
                        bearerAuth(createMockToken(fixture.orgNumber))
                        setBody(fixture.newManager())
                    }
                    response.status shouldBe HttpStatusCode.InternalServerError
                    response.body<ApiError>().type shouldBe ErrorType.UPSTREAM_SERVICE_UNAVAILABLE
                    response.body<ApiError>().message shouldBe "An upstream service is unavailable"
                    fixture.repository.fulfilled shouldBe emptyList()
                    coVerify(exactly = 0) { fixture.producer.sendSykmeldingNLRelasjon(any(), any()) }
                }
            }
        }

        test("PUT /requirement/{id} should normalize spaces in valid phone numbers") {
            withPutApplication { fixture ->
                val id = fixture.seed()
                val submittedManager = fixture.newManager().copy(
                    mobile = "+47 90 00 00 00",
                    email = "leder+ø@eksempelø.no; annen@domene.no ",
                )
                fixture.people.registerPerson(submittedManager.nationalIdentificationNumber.value, submittedManager.lastName)

                val response = client.put("$API_V1_PATH/$NARMESTELEDERBEHOV_PATH/$id") {
                    contentType(ContentType.Application.Json)
                    setBody(submittedManager)
                    bearerAuth(createMockToken(fixture.orgNumber))
                }
                response.status shouldBe HttpStatusCode.Accepted
                coVerify(exactly = 1) {
                    fixture.producer.sendSykmeldingNLRelasjon(
                        match { it.leder.mobil == "+4790000000" && it.leder.epost == "leder+ø@eksempelø.no;annen@domene.no" },
                        any(),
                    )
                }
            }
        }

        test("PUT /requirement/{id} should return 400 for invalid phone and email") {
            withPutApplication { fixture ->
                val id = fixture.seed()
                val submittedManager = fixture.newManager().copy(
                    mobile = "90-00-00-00",
                    email = "gyldig@example.com; invalid @example.com",
                )
                val response = client.put("$API_V1_PATH/$NARMESTELEDERBEHOV_PATH/$id") {
                    contentType(ContentType.Application.Json)
                    setBody(submittedManager)
                    bearerAuth(createMockToken(fixture.orgNumber))
                }
                val error = response.body<ApiError>()
                response.status shouldBe HttpStatusCode.BadRequest
                error.type shouldBe ErrorType.INVALID_FORMAT
                error.message shouldBe
                    "Invalid manager contact details: mobile: PhoneNumber must contain only digits, with an optional leading plus sign; " +
                    "email: EmailAddress must not contain whitespace"
                error.message.contains("90-00-00-00") shouldBe false
                error.message.contains("invalid @example.com") shouldBe false
                error.message.contains("gyldig@example.com") shouldBe false
                coVerify(exactly = 0) { fixture.producer.sendSykmeldingNLRelasjon(any(), any()) }
            }
        }

        test("PUT /requirement/{id} 404 when behov not found") {
            withPutApplication { fixture ->
                val response = client.put("$API_V1_PATH/$NARMESTELEDERBEHOV_PATH/${UUID.randomUUID()}") {
                    contentType(ContentType.Application.Json)
                    setBody(manager())
                    bearerAuth(createMockToken(fixture.orgNumber))
                }
                response.status shouldBe HttpStatusCode.NotFound
                response.body<ApiError>().type shouldBe ErrorType.NOT_FOUND
                response.body<ApiError>().message shouldBe "A LinemanagerRequirement was not found"
            }
        }

        test("PUT /requirement/{id} 400 invalid payload") {
            withPutApplication { fixture ->
                val id = fixture.seed()
                val response = client.put("$API_V1_PATH/$NARMESTELEDERBEHOV_PATH/$id") {
                    contentType(ContentType.Application.Json)
                    setBody("""{ "foo": "bar" }""")
                    bearerAuth(createMockToken(fixture.orgNumber))
                }
                response.status shouldBe HttpStatusCode.BadRequest
                response.body<ApiError>().type shouldBe ErrorType.INVALID_FORMAT
            }
        }

        test("PUT /requirement/{id} 403 when principal lacks Altinn resource access to orgnumber") {
            withPutApplication { fixture ->
                val id = fixture.seed()
                fixture.texas.defaultMocks(
                    consumer = DefaultOrganization.copy(ID = "0192:000000000"),
                    scope = MASKINPORTEN_NL_SCOPE,
                )
                coEvery { fixture.pdp.authorize(any(), any(), any()) } returns
                    AltinnAuthorizationResponse(listOf(DecisionResult(Decision.Deny)))
                fixture.ereg.organisasjoner[fixture.orgNumber] = Organisasjon(
                    organisasjonsnummer = fixture.orgNumber,
                    inngaarIJuridiskEnheter = emptyList(),
                )
                val response = client.put("$API_V1_PATH/$NARMESTELEDERBEHOV_PATH/$id") {
                    contentType(ContentType.Application.Json)
                    setBody(manager())
                    bearerAuth(createMockToken("000000000"))
                }
                response.status shouldBe HttpStatusCode.Forbidden
                response.body<ApiError>().type shouldBe ErrorType.MISSING_ALITINN_RESOURCE_ACCESS
                response.body<ApiError>().message shouldBe
                    "System user does not have access to nav_syfo_oppgi-narmesteleder resource"
            }
        }

        test("PUT /requirement/{id} returns the existing no-active-sykmelding error") {
            withPutApplication { fixture ->
                val id = fixture.seed()
                coEvery { fixture.sykmelding.getIsActiveSykmelding(fixture.employeeIdent, fixture.orgNumber) } returns false
                val response = client.put("$API_V1_PATH/$NARMESTELEDERBEHOV_PATH/$id") {
                    contentType(ContentType.Application.Json)
                    setBody(manager())
                    bearerAuth(createMockToken(fixture.orgNumber))
                }
                response.status shouldBe HttpStatusCode.BadRequest
                response.body<ApiError>().type shouldBe ErrorType.NO_ACTIVE_SICK_LEAVE
                response.body<ApiError>().message shouldBe
                    "No active sick leave found for the given organization number: ${fixture.orgNumber}"
                coVerify(exactly = 0) { fixture.producer.sendSykmeldingNLRelasjon(any(), any()) }
            }
        }

        listOf(
            false to "Employee on sick leave is missing employment in any organization",
            true to "Employee on sick leave is missing employment in the organization indicated in the request",
        ).forEach { (otherOrganization, message) ->
            test("PUT /requirement/{id} returns the existing employment error: $message") {
                withPutApplication { fixture ->
                    val id = fixture.seed(seedEmployment = false)
                    if (otherOrganization) {
                        fixture.aareg.seedEmployment(fixture.employeeIdent, "999999999", "999999999")
                    }
                    val response = client.put("$API_V1_PATH/$NARMESTELEDERBEHOV_PATH/$id") {
                        contentType(ContentType.Application.Json)
                        setBody(manager())
                        bearerAuth(createMockToken(fixture.orgNumber))
                    }
                    response.status shouldBe HttpStatusCode.BadRequest
                    response.body<ApiError>().type shouldBe ErrorType.EMPLOYEE_MISSING_EMPLOYMENT_IN_ORG
                    response.body<ApiError>().message shouldBe message
                    coVerify(exactly = 0) { fixture.producer.sendSykmeldingNLRelasjon(any(), any()) }
                }
            }
        }
    })

private class PutFixture {
    private val relation = linemanager()
    val employeeIdent = relation.employeeIdentificationNumber.value
    val orgNumber = relation.orgNumber.value
    val repository = FakePutBehovRepository()
    val aareg = TestAaregClient()
    val texas = mockk<TexasHttpClient>()
    val pdp = mockk<AltinnAuthorizationClient>(relaxed = true)
    val ereg = FakeEregClient()
    val people = FakePersonLookup()
    val sykmelding: DinesykmeldteClient = spyk(FakeDinesykmeldteClient())
    val producer = spyk(FakeSykmeldingNarmestelederProducer())
    val altinn = AltinnTilgangerService(FakeAltinnTilgangerClient())
    private val organizationAccess = AltinnOrganizationAccess(
        altinn,
        pdp,
        CachedEregClient(ereg, mockk<EregCache>(relaxed = true)),
    )
    val useCase = FulfillNarmestelederbehovUseCase(
        repository,
        organizationAccess,
        EstablishNarmestelederrelasjonUseCase(
            DinesykmeldteActiveSykmeldingLookup(sykmelding),
            AaregEmploymentLookup(aareg),
            people,
            MicrometerNameValidationMetrics(),
            KafkaPublishNarmestelederrelasjon(producer),
        ),
        NarmestelederbehovDialog { },
    )

    init {
        texas.defaultMocks(
            systemBrukerOrganisasjon = DefaultOrganization.copy(ID = "0192:$orgNumber"),
            scope = MASKINPORTEN_NL_SCOPE,
        )
        coEvery { pdp.authorize(any(), any(), any()) } returns
            AltinnAuthorizationResponse(listOf(DecisionResult(Decision.Permit)))
    }

    suspend fun seed(seedEmployment: Boolean = true): UUID {
        if (seedEmployment) aareg.seedEmployment(employeeIdent, orgNumber, orgNumber)
        people.registerPerson(employeeIdent, relation.lastName)
        return repository.seed(employeeIdent, orgNumber)
    }

    fun newManager(): Manager = manager().copy(
        nationalIdentificationNumber = PersonalIdentificationNumber(relation.manager.nationalIdentificationNumber.value.reversed()),
    )
}

private class FakePutBehovRepository : NarmestelederbehovRepository {
    private val behov = mutableMapOf<NarmestelederbehovId, Narmestelederbehov>()
    val fulfilled = mutableListOf<NarmestelederbehovId>()

    fun seed(employeeIdent: String, orgNumber: String): UUID {
        val id = NarmestelederbehovId(UUID.randomUUID())
        behov[id] = Narmestelederbehov(id, Employee(PersonIdent(employeeIdent), OrganizationNumber(orgNumber)))
        return id.value
    }

    override suspend fun findDetails(id: NarmestelederbehovId): NarmestelederbehovDetails? = null

    override suspend fun saveEmployeeName(id: NarmestelederbehovId, name: BehovPersonName) = error("Fulfillment must not save employee name")

    override suspend fun findForFulfillment(id: NarmestelederbehovId): Narmestelederbehov? = behov[id]

    override suspend fun findOpenFor(employee: Employee): List<NarmestelederbehovId> = error("Fulfillment must not look up open behov")

    override suspend fun markFulfilled(id: NarmestelederbehovId): MarkFulfilledResult {
        if (id !in behov) return MarkFulfilledResult.Missing
        fulfilled += id
        return MarkFulfilledResult.Marked(id, null)
    }

    override suspend fun markDialogCompleted(id: NarmestelederbehovId) = MarkDialogCompletedResult.Marked
}

private fun withPutApplication(block: suspend ApplicationTestBuilder.(PutFixture) -> Unit) {
    val fixture = PutFixture()
    testApplication {
        this.client = createClient {
            install(ContentNegotiation) {
                jackson {
                    registerKotlinModule()
                    registerModule(JavaTimeModule())
                    configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
                    configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                }
            }
        }
        application {
            installContentNegotiation()
            installStatusPages()
            routing {
                route(API_V1_PATH) {
                    install(AddTokenIssuerPlugin)
                    registerFulfillNarmestelederbehovApi(fixture.useCase, fixture.texas)
                }
            }
        }
        block(fixture)
    }
}

private class FakePersonLookup : PersonLookup {
    private val people = mutableMapOf<PersonIdent, PersonDetails>()

    fun registerPerson(fnr: String, lastName: String) {
        val personIdent = PersonIdent(fnr)
        people[personIdent] = PersonDetails(
            personIdent,
            PersonNameDetails(firstName = "Test", lastName = lastName, registeredNames = listOf(RegisteredName(lastName))),
        )
    }

    override suspend fun find(personIdent: PersonIdent): PersonDetails? = people[personIdent]
}
