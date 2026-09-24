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
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.spyk
import linemanager
import manager
import nlBehovEntity
import no.nav.syfo.API_V1_PATH
import no.nav.syfo.aareg.AaregService
import no.nav.syfo.aareg.client.TestAaregClient
import no.nav.syfo.altinn.pdp.client.Decision
import no.nav.syfo.altinn.pdp.service.PdpService
import no.nav.syfo.altinntilganger.AltinnTilgangerService
import no.nav.syfo.altinntilganger.client.FakeAltinnTilgangerClient
import no.nav.syfo.application.api.ApiError
import no.nav.syfo.application.api.ErrorType
import no.nav.syfo.application.api.installContentNegotiation
import no.nav.syfo.application.api.installStatusPages
import no.nav.syfo.application.valkey.EregCache
import no.nav.syfo.application.valkey.PdlCache
import no.nav.syfo.dinesykmeldte.DinesykmeldteService
import no.nav.syfo.dinesykmeldte.IDinesykmeldteService
import no.nav.syfo.dinesykmeldte.client.FakeDinesykmeldteClient
import no.nav.syfo.ereg.EregService
import no.nav.syfo.ereg.client.FakeEregClient
import no.nav.syfo.ereg.client.Organisasjon
import no.nav.syfo.narmesteleder.api.v1.LinemanagerRequirementRESTHandler
import no.nav.syfo.narmesteleder.api.v1.REQUIREMENT_PATH
import no.nav.syfo.narmesteleder.db.FakeNarmestelederDb
import no.nav.syfo.narmesteleder.domain.BehovStatus
import no.nav.syfo.narmesteleder.domain.Manager
import no.nav.syfo.narmesteleder.domain.PersonalIdentificationNumber
import no.nav.syfo.narmesteleder.kafka.FakeSykmeldingNLKafkaProducer
import no.nav.syfo.narmesteleder.service.NarmestelederKafkaService
import no.nav.syfo.narmesteleder.service.NarmestelederLookupService
import no.nav.syfo.narmesteleder.service.ValidationService
import no.nav.syfo.narmesteleder.service.validators.PrincipalAccessValidator
import no.nav.syfo.narmestelederbehov.application.FulfillNarmestelederbehovUseCase
import no.nav.syfo.narmestelederbehov.infrastructure.AaregEmploymentLookup
import no.nav.syfo.narmestelederbehov.infrastructure.DbNarmestelederbehovRepository
import no.nav.syfo.narmestelederbehov.infrastructure.DialogportenNarmestelederbehovDialog
import no.nav.syfo.narmestelederbehov.infrastructure.DinesykmeldteActiveSykmeldingLookup
import no.nav.syfo.narmestelederbehov.infrastructure.LegacyManagerNameValidationMetrics
import no.nav.syfo.narmestelederbehov.infrastructure.PdlPersonLookup
import no.nav.syfo.narmestelederrelasjon.infrastructure.KafkaEstablishNarmestelederrelasjon
import no.nav.syfo.organisasjonstilgang.infrastructure.LegacyOrganizationAccess
import no.nav.syfo.pdl.PdlService
import no.nav.syfo.pdl.client.FakePdlClient
import no.nav.syfo.registerApiV1
import no.nav.syfo.texas.MASKINPORTEN_NL_SCOPE
import no.nav.syfo.texas.client.TexasHttpClient
import prepareGetPersonResponse
import java.util.UUID

class FulfillNarmestelederbehovRouteTest :
    FunSpec({
        test("PUT /requirement/{id} 202 updates behov and sends kafka message") {
            withPutApplication { fixture ->
                val id = fixture.seed()
                val submittedManager = fixture.newManager()
                fixture.pdl.prepareGetPersonResponse(submittedManager)

                val response = client.put("$API_V1_PATH/$REQUIREMENT_PATH/$id") {
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
                (fixture.db.findBehovById(id) ?: error("Stored requirement missing")).behovStatus shouldBe BehovStatus.BEHOV_FULFILLED
            }
        }

        test("PUT /requirement/{id} should normalize spaces in valid phone numbers") {
            withPutApplication { fixture ->
                val id = fixture.seed()
                val submittedManager = fixture.newManager().copy(
                    mobile = "+47 90 00 00 00",
                    email = "leder+ø@eksempelø.no; annen@domene.no ",
                )
                fixture.pdl.prepareGetPersonResponse(submittedManager)

                val response = client.put("$API_V1_PATH/$REQUIREMENT_PATH/$id") {
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
                val response = client.put("$API_V1_PATH/$REQUIREMENT_PATH/$id") {
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
                val response = client.put("$API_V1_PATH/$REQUIREMENT_PATH/${UUID.randomUUID()}") {
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
                val response = client.put("$API_V1_PATH/$REQUIREMENT_PATH/$id") {
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
                coEvery { fixture.pdp.accessDecisionForResource(any(), any(), any()) } returns Decision.Deny
                fixture.ereg.organisasjoner[fixture.orgNumber] = Organisasjon(
                    organisasjonsnummer = fixture.orgNumber,
                    inngaarIJuridiskEnheter = emptyList(),
                )
                val response = client.put("$API_V1_PATH/$REQUIREMENT_PATH/$id") {
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
                val response = client.put("$API_V1_PATH/$REQUIREMENT_PATH/$id") {
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
                    val response = client.put("$API_V1_PATH/$REQUIREMENT_PATH/$id") {
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
    val db = FakeNarmestelederDb()
    val aareg = TestAaregClient()
    val texas = mockk<TexasHttpClient>()
    val pdp = mockk<PdpService>(relaxed = true)
    val ereg = FakeEregClient()
    private val pdlCache = mockk<PdlCache>(relaxed = true)
    val pdl = spyk(PdlService(FakePdlClient(), pdlCache))
    val sykmelding: IDinesykmeldteService = spyk(DinesykmeldteService(FakeDinesykmeldteClient()))
    val producer = spyk(FakeSykmeldingNLKafkaProducer())
    val altinn = AltinnTilgangerService(FakeAltinnTilgangerClient())
    private val principalAccess = PrincipalAccessValidator(
        altinn,
        pdp,
        EregService(ereg, mockk<EregCache>(relaxed = true)),
    )
    val useCase = FulfillNarmestelederbehovUseCase(
        DbNarmestelederbehovRepository(db),
        LegacyOrganizationAccess(principalAccess),
        DinesykmeldteActiveSykmeldingLookup(sykmelding),
        AaregEmploymentLookup(AaregService(aareg)),
        PdlPersonLookup(pdl),
        KafkaEstablishNarmestelederrelasjon(producer),
        DialogportenNarmestelederbehovDialog(db, mockk(relaxed = true)),
        LegacyManagerNameValidationMetrics(),
    )

    init {
        coEvery { pdlCache.getPerson(any()) } returns null
        texas.defaultMocks(
            systemBrukerOrganisasjon = DefaultOrganization.copy(ID = "0192:$orgNumber"),
            scope = MASKINPORTEN_NL_SCOPE,
        )
        coEvery { pdp.accessDecisionForResource(any(), any(), any()) } returns Decision.Permit
    }

    suspend fun seed(seedEmployment: Boolean = true): UUID {
        if (seedEmployment) aareg.seedEmployment(employeeIdent, orgNumber, orgNumber)
        pdl.prepareGetPersonResponse(employeeIdent, relation.lastName)
        return db.insertNlBehov(
            nlBehovEntity().copy(
                sykmeldtFnr = employeeIdent,
                orgnummer = orgNumber,
                hovedenhetOrgnummer = orgNumber,
                behovStatus = BehovStatus.BEHOV_CREATED,
            ),
        ).id ?: error("No requirement seeded")
    }

    fun newManager(): Manager = manager().copy(
        nationalIdentificationNumber = PersonalIdentificationNumber(relation.manager.nationalIdentificationNumber.value.reversed()),
    )
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
                registerApiV1(
                    NarmestelederKafkaService(fixture.producer),
                    fixture.texas,
                    mockk<ValidationService>(relaxed = true),
                    mockk<LinemanagerRequirementRESTHandler>(relaxed = true),
                    fixture.altinn,
                    mockk<NarmestelederLookupService>(relaxed = true),
                    fixture.useCase,
                )
            }
        }
        block(fixture)
    }
}
