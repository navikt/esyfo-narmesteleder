package no.nav.syfo.narmestelederbehov.api

import DefaultOrganization
import createMockToken
import defaultMocks
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.mockk.mockk
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.narmesteleder.domain.LineManagerRequirementStatus
import no.nav.syfo.narmesteleder.domain.LinemanagerRequirementRead
import no.nav.syfo.narmesteleder.domain.RevokedBy
import no.nav.syfo.narmestelederbehov.application.BehovPersonName
import no.nav.syfo.narmestelederbehov.application.EmployeeNameLookup
import no.nav.syfo.narmestelederbehov.application.EmployeeNameLookupResult
import no.nav.syfo.narmestelederbehov.application.GetNarmestelederbehovUseCase
import no.nav.syfo.narmestelederbehov.application.MarkDialogCompletedResult
import no.nav.syfo.narmestelederbehov.application.MarkDialogCreatedResult
import no.nav.syfo.narmestelederbehov.application.MarkFulfilledResult
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovDetails
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovEmployeeName
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovRepository
import no.nav.syfo.narmestelederbehov.application.NewNarmestelederbehov
import no.nav.syfo.narmestelederbehov.application.narmestelederbehovDetails
import no.nav.syfo.narmestelederbehov.domain.BehovReason
import no.nav.syfo.narmestelederbehov.domain.Employee
import no.nav.syfo.narmestelederbehov.domain.Narmestelederbehov
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import no.nav.syfo.organisasjonstilgang.application.DenialReason
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccess
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessResult
import no.nav.syfo.platform.api.API_V1_PATH
import no.nav.syfo.platform.api.ApiError
import no.nav.syfo.platform.api.ErrorType
import no.nav.syfo.platform.api.apiV1
import no.nav.syfo.platform.api.createJsonClient
import no.nav.syfo.platform.api.testRouteApplication
import no.nav.syfo.platform.auth.texas.MASKINPORTEN_NL_SCOPE
import no.nav.syfo.platform.auth.texas.client.TexasHttpClient
import no.nav.syfo.platform.observability.assertSingleUpstreamFailureLog
import no.nav.syfo.platform.upstream.UpstreamError
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamName
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

        test("upstream failures return HTTP 500 with UPSTREAM_SERVICE_UNAVAILABLE") {
            val failure = UpstreamFailure(UpstreamName("pdl"), UpstreamFailureStage.RESPONSE, 200, errors = listOf(UpstreamError("server_error")))
            val fixture = GetFixture(unavailable = failure)
            val behov = fixture.repository.seed(firstName = null)
            assertSingleUpstreamFailureLog {
                fixture.withApplication {
                    val response = client.get("$API_V1_PATH/linemanager/requirement/${behov.id.value}") {
                        bearerAuth(createMockToken(ORGANIZATION_NUMBER))
                    }
                    response.status shouldBe HttpStatusCode.InternalServerError
                    response.body<ApiError>().type shouldBe ErrorType.UPSTREAM_SERVICE_UNAVAILABLE
                    response.body<ApiError>().message shouldBe "An upstream service is unavailable"
                    fixture.effects shouldBe listOf("person")
                    fixture.repository.savedNames shouldBe emptyList()
                }
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
    unavailable: UpstreamFailure? = null,
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
                unavailable?.let { EmployeeNameLookupResult.Unavailable(it) } ?: person?.let { EmployeeNameLookupResult.Found(it) } ?: EmployeeNameLookupResult.NotFound
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
        testRouteApplication(
            routes = { apiV1 { registerGetNarmestelederbehovApi(getNarmestelederbehov = useCase, texasHttpClient = texas) } },
        ) {
            client = createJsonClient()
            block()
        }
    }
}

private class FakeReadBehovRepository : NarmestelederbehovRepository {
    override suspend fun findPendingDialogs(createdBefore: Instant, limit: Int) = error("Unexpected findPendingDialogs")
    override suspend fun findFulfilledDialogs(createdBefore: Instant) = error("Unexpected findFulfilledDialogs")
    override suspend fun findDialogsToExpire() = error("Unexpected findDialogsToExpire")
    override suspend fun markDialogExpired(id: NarmestelederbehovId, expirationTime: Instant) = error("Unexpected markDialogExpired")
    override suspend fun create(behov: NewNarmestelederbehov) = error("Unexpected create")

    private val behov = mutableMapOf<NarmestelederbehovId, NarmestelederbehovDetails>()
    val savedNames = mutableListOf<Pair<NarmestelederbehovId, BehovPersonName>>()
    var failure: Exception? = null

    fun seed(firstName: String? = "Stored", lastName: String? = "Name"): NarmestelederbehovDetails {
        val details = narmestelederbehovDetails(
            employeeIdent = PersonalIdentificationNumber(EMPLOYEE_IDENT),
            organizationNumber = OrganizationNumber(ORGANIZATION_NUMBER),
            mainOrganizationNumber = MAIN_ORGANIZATION_NUMBER,
            firstName = firstName,
            lastName = lastName,
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

    override suspend fun findOpenFor(employee: Employee): List<NarmestelederbehovId> = error("GET must not look up open behov")

    override suspend fun markFulfilled(id: NarmestelederbehovId): MarkFulfilledResult = error("GET must not write")

    override suspend fun markDialogCompleted(id: NarmestelederbehovId): MarkDialogCompletedResult = error("GET must not write")

    override suspend fun markDialogCreated(
        id: NarmestelederbehovId,
        dialogId: UUID,
        name: BehovPersonName?,
    ): MarkDialogCreatedResult = error("Unexpected markDialogCreated")
}
