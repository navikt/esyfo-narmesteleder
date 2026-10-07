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
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.jackson.jackson
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.mockk
import no.nav.syfo.application.api.API_V1_PATH
import no.nav.syfo.application.api.ApiError
import no.nav.syfo.application.api.ErrorType
import no.nav.syfo.application.api.installContentNegotiation
import no.nav.syfo.application.api.installStatusPages
import no.nav.syfo.application.auth.AddTokenIssuerPlugin
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmesteleder.domain.BehovReason
import no.nav.syfo.narmesteleder.domain.BehovStatus
import no.nav.syfo.narmesteleder.domain.LineManagerRequirementStatus
import no.nav.syfo.narmesteleder.domain.LinemanagerRequirementRead
import no.nav.syfo.narmesteleder.domain.RevokedBy
import no.nav.syfo.narmestelederbehov.application.BehovPersonName
import no.nav.syfo.narmestelederbehov.application.EmployeeNameLookup
import no.nav.syfo.narmestelederbehov.application.GetNarmestelederbehovUseCase
import no.nav.syfo.narmestelederbehov.application.MarkDialogCompletedResult
import no.nav.syfo.narmestelederbehov.application.MarkFulfilledResult
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovDetails
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovEmployeeName
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovRepository
import no.nav.syfo.narmestelederbehov.domain.Narmestelederbehov
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import no.nav.syfo.organisasjonstilgang.application.DenialReason
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccess
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessResult
import no.nav.syfo.texas.MASKINPORTEN_NL_SCOPE
import no.nav.syfo.texas.client.TexasHttpClient
import java.time.Instant
import java.util.UUID

class GetNarmestelederbehovRouteTest :
    FunSpec({
        test("GET /requirement/{id} 200 maps the stored behov to the legacy response") {
            val fixture = GetFixture(organizationName = "Bedrift AS")
            val behov = fixture.repository.seed()
            fixture.withApplication {
                val response = client.get("$API_V1_PATH/linemanager/requirement/${behov.id.value}") {
                    bearerAuth(createMockToken(ORGANIZATION_NUMBER))
                }
                response.status shouldBe HttpStatusCode.OK
                val body = response.body<LinemanagerRequirementRead>()
                body.id shouldBe behov.id.value
                body.employeeIdentificationNumber.value shouldBe EMPLOYEE_IDENT
                body.orgNumber.value shouldBe ORGANIZATION_NUMBER
                body.orgName shouldBe "Bedrift AS"
                body.mainOrgNumber.value shouldBe MAIN_ORGANIZATION_NUMBER
                body.name.firstName shouldBe "Stored"
                body.name.middleName shouldBe "Middle"
                body.name.lastName shouldBe "Name"
                body.status shouldBe LineManagerRequirementStatus.CREATED
                body.revokedBy shouldBe RevokedBy.LINEMANAGER
                fixture.effects shouldBe listOf("access")
            }
        }

        test("GET /requirement/{id} 200 looks up and stores the name when the stored behov has none") {
            val fixture = GetFixture()
            val behov = fixture.repository.seed(firstName = null, lastName = null)
            fixture.withApplication {
                val response = client.get("$API_V1_PATH/linemanager/requirement/${behov.id.value}") {
                    bearerAuth(createMockToken(ORGANIZATION_NUMBER))
                }
                response.status shouldBe HttpStatusCode.OK
                val body = response.body<LinemanagerRequirementRead>()
                body.orgName shouldBe null
                body.name.firstName shouldBe "Looked"
                body.name.lastName shouldBe "Up"
                fixture.effects shouldBe listOf("person", "access")
                fixture.repository.savedNames shouldBe listOf(behov.id to BehovPersonName(firstName = "Looked", middleName = null, lastName = "Up"))
            }
        }

        test("GET /requirement/{id} 404 when behov is not found and access is not evaluated") {
            val fixture = GetFixture()
            fixture.withApplication {
                val response = client.get("$API_V1_PATH/linemanager/requirement/${UUID.randomUUID()}") {
                    bearerAuth(createMockToken(ORGANIZATION_NUMBER))
                }
                response.status shouldBe HttpStatusCode.NotFound
                response.body<ApiError>().type shouldBe ErrorType.NOT_FOUND
                response.body<ApiError>().message shouldBe "LinemanagerRequirement"
                fixture.effects shouldBe emptyList()
            }
        }

        listOf(
            Triple(
                DenialReason.MISSING_ORGANIZATION_ACCESS,
                ErrorType.MISSING_ORG_ACCESS,
                "User lacks access to organization: $ORGANIZATION_NUMBER",
            ),
            Triple(
                DenialReason.MISSING_RESOURCE_ACCESS,
                ErrorType.MISSING_ALITINN_RESOURCE_ACCESS,
                "User lacks access to required Altinn resource for organization: $ORGANIZATION_NUMBER",
            ),
            Triple(
                DenialReason.SYSTEM_USER_REJECTED,
                ErrorType.MISSING_ALITINN_RESOURCE_ACCESS,
                "System user does not have access to nav_syfo_oppgi-narmesteleder resource",
            ),
        ).forEach { (reason, type, message) ->
            test("GET /requirement/{id} 403 when access is denied with $reason") {
                val fixture = GetFixture(denialReason = reason)
                val behov = fixture.repository.seed(firstName = null, lastName = null)
                fixture.withApplication {
                    val response = client.get("$API_V1_PATH/linemanager/requirement/${behov.id.value}") {
                        bearerAuth(createMockToken(ORGANIZATION_NUMBER))
                    }
                    response.status shouldBe HttpStatusCode.Forbidden
                    response.body<ApiError>().type shouldBe type
                    response.body<ApiError>().message shouldBe message
                    fixture.effects shouldBe listOf("person", "access")
                }
            }
        }

        test("GET /requirement/{id} 500 when the person is not found") {
            val fixture = GetFixture(person = null)
            val behov = fixture.repository.seed(firstName = null, lastName = null)
            fixture.withApplication {
                val response = client.get("$API_V1_PATH/linemanager/requirement/${behov.id.value}") {
                    bearerAuth(createMockToken(ORGANIZATION_NUMBER))
                }
                response.status shouldBe HttpStatusCode.InternalServerError
                response.body<ApiError>().message shouldBe "Something went wrong while fetching LinemanagerRequirement"
                fixture.effects shouldBe listOf("person")
                fixture.repository.savedNames shouldBe emptyList()
            }
        }

        test("GET /requirement/{id} 500 with the legacy message when reading fails unexpectedly") {
            val fixture = GetFixture()
            fixture.repository.failure = IllegalStateException("database unavailable")
            fixture.withApplication {
                val response = client.get("$API_V1_PATH/linemanager/requirement/${UUID.randomUUID()}") {
                    bearerAuth(createMockToken(ORGANIZATION_NUMBER))
                }
                response.status shouldBe HttpStatusCode.InternalServerError
                response.body<ApiError>().type shouldBe ErrorType.INTERNAL_SERVER_ERROR
                response.body<ApiError>().message shouldBe "Something went wrong while fetching LinemanagerRequirement"
            }
        }
    })

private const val EMPLOYEE_IDENT = "12345678901"
private const val ORGANIZATION_NUMBER = "910000001"
private const val MAIN_ORGANIZATION_NUMBER = "910000002"

private class GetFixture(
    organizationName: String? = null,
    denialReason: DenialReason? = null,
    person: BehovPersonName? = BehovPersonName(firstName = "Looked", middleName = null, lastName = "Up"),
) {
    val effects = mutableListOf<String>()
    val repository = FakeReadBehovRepository()
    private val texas = mockk<TexasHttpClient>()
    private val useCase = GetNarmestelederbehovUseCase(
        repository = repository,
        organizationAccess = OrganizationAccess { _, _ ->
            effects += "access"
            denialReason?.let { OrganizationAccessResult.Denied(it) } ?: OrganizationAccessResult.Granted(organizationName)
        },
        employeeName = NarmestelederbehovEmployeeName(
            repository,
            EmployeeNameLookup {
                effects += "person"
                person
            },
        ),
    )

    init {
        texas.defaultMocks(
            systemBrukerOrganisasjon = DefaultOrganization.copy(ID = "0192:$ORGANIZATION_NUMBER"),
            scope = MASKINPORTEN_NL_SCOPE,
        )
    }

    fun withApplication(block: suspend ApplicationTestBuilder.() -> Unit) {
        testApplication {
            client = createClient {
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
                        registerGetNarmestelederbehovApi(getNarmestelederbehov = useCase, texasHttpClient = texas)
                    }
                }
            }
            block()
        }
    }
}

private class FakeReadBehovRepository : NarmestelederbehovRepository {
    private val behov = mutableMapOf<NarmestelederbehovId, NarmestelederbehovDetails>()
    val savedNames = mutableListOf<Pair<NarmestelederbehovId, BehovPersonName>>()
    var failure: Exception? = null

    fun seed(firstName: String? = "Stored", lastName: String? = "Name"): NarmestelederbehovDetails {
        val details = NarmestelederbehovDetails(
            id = NarmestelederbehovId(UUID.randomUUID()),
            employeeIdent = PersonIdent(EMPLOYEE_IDENT),
            organizationNumber = OrganizationNumber(ORGANIZATION_NUMBER),
            mainOrganizationNumber = MAIN_ORGANIZATION_NUMBER,
            managerIdent = null,
            firstName = firstName,
            middleName = "Middle",
            lastName = lastName,
            created = Instant.EPOCH,
            updated = Instant.EPOCH,
            status = BehovStatus.BEHOV_CREATED,
            reason = BehovReason.DEAKTIVERT_LEDER,
        )
        behov[details.id] = details
        return details
    }

    override suspend fun findDetails(id: NarmestelederbehovId): NarmestelederbehovDetails? {
        failure?.let { throw it }
        return behov[id]
    }

    override suspend fun saveEmployeeName(id: NarmestelederbehovId, name: BehovPersonName) {
        savedNames += id to name
    }

    override suspend fun findForFulfillment(id: NarmestelederbehovId): Narmestelederbehov? = error("GET must not read for fulfillment")

    override suspend fun markFulfilled(id: NarmestelederbehovId): MarkFulfilledResult = error("GET must not write")

    override suspend fun markDialogCompleted(id: NarmestelederbehovId): MarkDialogCompletedResult = error("GET must not write")
}
