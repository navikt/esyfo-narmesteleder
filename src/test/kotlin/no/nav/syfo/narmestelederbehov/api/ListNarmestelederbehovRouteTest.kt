package no.nav.syfo.narmestelederbehov.api

import DefaultOrganization
import com.fasterxml.jackson.databind.JsonNode
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
import no.nav.syfo.narmestelederbehov.application.BehovPersonName
import no.nav.syfo.narmestelederbehov.application.EmployeeNameLookupResult
import no.nav.syfo.narmestelederbehov.application.ListNarmestelederbehovUseCase
import no.nav.syfo.narmestelederbehov.application.MarkDialogCompletedResult
import no.nav.syfo.narmestelederbehov.application.MarkDialogCreatedResult
import no.nav.syfo.narmestelederbehov.application.MarkFulfilledResult
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovDetails
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovEmployeeName
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovRepository
import no.nav.syfo.narmestelederbehov.application.NewNarmestelederbehov
import no.nav.syfo.narmestelederbehov.application.OpenNarmestelederbehovRepository
import no.nav.syfo.narmestelederbehov.application.narmestelederbehovDetails
import no.nav.syfo.narmestelederbehov.domain.BehovReason
import no.nav.syfo.narmestelederbehov.domain.Employee
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import no.nav.syfo.organisasjonstilgang.application.DenialReason
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

class ListNarmestelederbehovRouteTest :
    FunSpec({
        test("200 preserves the collection JSON shape and organization name without counting a complete page") {
            val fixture = ListRouteFixture()
            val row = fixture.repository.rows.single()
            fixture.withApplication {
                val response = client.get("$LIST_PATH?$VALID_QUERY&pageSize=10") { bearerAuth(createMockToken(ORGANIZATION_NUMBER)) }
                response.status shouldBe HttpStatusCode.OK
                val body = response.body<JsonNode>()
                body.fieldNames().asSequence().toSet() shouldBe setOf("linemanagerRequirements", "meta")
                val meta = body["meta"]
                meta.fieldNames().asSequence().toSet() shouldBe setOf("size", "pageSize", "hasMore", "total")
                meta["size"].asInt() shouldBe 1
                meta["pageSize"].asInt() shouldBe 10
                meta["hasMore"].asBoolean() shouldBe false
                meta["total"].asLong() shouldBe 1L
                val item = body["linemanagerRequirements"].single()
                item["id"].asText() shouldBe row.id.value.toString()
                item["employeeIdentificationNumber"].asText() shouldBe EMPLOYEE_IDENT
                item["orgNumber"].asText() shouldBe ORGANIZATION_NUMBER
                item["orgName"].asText() shouldBe "Bedrift AS"
                item["mainOrgNumber"].asText() shouldBe "910000002"
                item["name"]["firstName"].asText() shouldBe "Stored"
                item["name"]["middleName"].asText() shouldBe "Middle"
                item["name"]["lastName"].asText() shouldBe "Name"
                item["created"].asText() shouldBe Instant.EPOCH.toString()
                item["updated"].asText() shouldBe Instant.EPOCH.toString()
                item["status"].asText() shouldBe "CREATED"
                item["revokedBy"].asText() shouldBe "LINEMANAGER"
                fixture.repository.requestedLimit shouldBe 11
                fixture.repository.countCalls shouldBe 0
                fixture.repository.requestedOrganization shouldBe OrganizationNumber(ORGANIZATION_NUMBER)
                fixture.repository.requestedCreatedAfter shouldBe Instant.EPOCH
            }
        }

        test("200 counts only with overflow and excludes that row from the response") {
            val fixture = ListRouteFixture()
            fixture.repository.rows += routeDetails()
            fixture.withApplication {
                val response = client.get("$LIST_PATH?$VALID_QUERY&pageSize=1") { bearerAuth(createMockToken(ORGANIZATION_NUMBER)) }
                response.status shouldBe HttpStatusCode.OK
                val body = response.body<LinemanagerRequirementCollection>()
                body.linemanagerRequirements.size shouldBe 1
                body.meta.size shouldBe 1
                body.meta.pageSize shouldBe 1
                body.meta.hasMore shouldBe true
                body.meta.total shouldBe 42L
                fixture.repository.countCalls shouldBe 1
            }
        }

        listOf(null, "0", "-1", "51", "2147483648", "invalid", "1", "50").forEach { pageSize ->
            test("pageSize $pageSize preserves default and clamping") {
                val fixture = ListRouteFixture()
                val expected = pageSize?.toIntOrNull()?.takeIf { it in 1..50 } ?: 50
                fixture.withApplication {
                    val query = VALID_QUERY + (pageSize?.let { "&pageSize=$it" } ?: "")
                    val response = client.get("$LIST_PATH?$query") { bearerAuth(createMockToken(ORGANIZATION_NUMBER)) }
                    response.status shouldBe HttpStatusCode.OK
                    response.body<LinemanagerRequirementCollection>().meta.pageSize shouldBe expected
                    fixture.repository.requestedLimit shouldBe expected + 1
                }
            }
        }

        listOf(
            Triple("orgNumber=$ORGANIZATION_NUMBER", ErrorType.BAD_REQUEST, "Missing createdAfter parameter"),
            Triple("orgNumber=$ORGANIZATION_NUMBER&createdAfter=invalid", ErrorType.BAD_REQUEST, "Invalid date format for createdAfter parameter. Expected ISO-8601 format."),
            Triple("createdAfter=1970-01-01T00:00:00Z", ErrorType.BAD_REQUEST, "Missing orgNumber parameter"),
        ).forEach { (query, type, message) ->
            test("invalid query $query returns the legacy 400") {
                val fixture = ListRouteFixture()
                fixture.withApplication {
                    val response = client.get("$LIST_PATH?$query") { bearerAuth(createMockToken(ORGANIZATION_NUMBER)) }
                    response.status shouldBe HttpStatusCode.BadRequest
                    response.body<ApiError>().type shouldBe type
                    response.body<ApiError>().message shouldBe message
                    fixture.repository.requestedLimit shouldBe null
                }
            }
        }

        listOf("12345678", "12345678a").forEach { organizationNumber ->
            test("invalid organization number $organizationNumber returns INVALID_FORMAT") {
                val fixture = ListRouteFixture()
                fixture.withApplication {
                    val response = client.get("$LIST_PATH?orgNumber=$organizationNumber&createdAfter=1970-01-01T00:00:00Z") {
                        bearerAuth(createMockToken(ORGANIZATION_NUMBER))
                    }
                    response.status shouldBe HttpStatusCode.BadRequest
                    response.body<ApiError>().type shouldBe ErrorType.INVALID_FORMAT
                }
            }
        }

        listOf(
            Triple(DenialReason.MISSING_ORGANIZATION_ACCESS, ErrorType.MISSING_ORG_ACCESS, "User lacks access to organization: $ORGANIZATION_NUMBER"),
            Triple(DenialReason.MISSING_RESOURCE_ACCESS, ErrorType.MISSING_ALITINN_RESOURCE_ACCESS, "User lacks access to required Altinn resource for organization: $ORGANIZATION_NUMBER"),
            Triple(DenialReason.SYSTEM_USER_REJECTED, ErrorType.MISSING_ALITINN_RESOURCE_ACCESS, "System user does not have access to nav_syfo_oppgi-narmesteleder resource"),
        ).forEach { (reason, type, message) ->
            test("access denial $reason returns the legacy 403") {
                val fixture = ListRouteFixture(access = OrganizationAccessResult.Denied(reason))
                fixture.withApplication {
                    val response = client.get("$LIST_PATH?$VALID_QUERY") { bearerAuth(createMockToken(ORGANIZATION_NUMBER)) }
                    response.status shouldBe HttpStatusCode.Forbidden
                    response.body<ApiError>().type shouldBe type
                    response.body<ApiError>().message shouldBe message
                    fixture.repository.requestedLimit shouldBe null
                }
            }
        }

        test("missing names are fetched and stored with a null system organization name") {
            val fixture = ListRouteFixture(access = OrganizationAccessResult.Granted(null))
            fixture.repository.rows[0] = routeDetails().copy(firstName = null)
            fixture.withApplication {
                val response = client.get("$LIST_PATH?$VALID_QUERY") { bearerAuth(createMockToken(ORGANIZATION_NUMBER)) }
                response.status shouldBe HttpStatusCode.OK
                val item = response.body<LinemanagerRequirementCollection>().linemanagerRequirements.single()
                item.orgName shouldBe null
                item.name.firstName shouldBe "Looked"
                fixture.repository.savedNames shouldBe listOf(fixture.repository.rows.single().id to BehovPersonName("Looked", null, "Up"))
            }
        }

        test("upstream failures return HTTP 500 with UPSTREAM_SERVICE_UNAVAILABLE") {
            val failure = UpstreamFailure(UpstreamName("pdl"), UpstreamFailureStage.RESPONSE, 200, errors = listOf(UpstreamError("server_error")))
            val fixture = ListRouteFixture(unavailable = failure)
            fixture.repository.rows[0] = routeDetails().copy(firstName = null)
            assertSingleUpstreamFailureLog {
                fixture.withApplication {
                    val response = client.get("$LIST_PATH?$VALID_QUERY") { bearerAuth(createMockToken(ORGANIZATION_NUMBER)) }
                    response.status shouldBe HttpStatusCode.InternalServerError
                    response.body<ApiError>().type shouldBe ErrorType.UPSTREAM_SERVICE_UNAVAILABLE
                    response.body<ApiError>().message shouldBe "An upstream service is unavailable"
                    fixture.repository.savedNames shouldBe emptyList()
                    fixture.repository.countCalls shouldBe 0
                }
            }
        }

        test("person not found returns the exact legacy generic 500") {
            val fixture = ListRouteFixture(personFound = false)
            fixture.repository.rows[0] = routeDetails().copy(firstName = null)
            fixture.withApplication {
                val response = client.get("$LIST_PATH?$VALID_QUERY") { bearerAuth(createMockToken(ORGANIZATION_NUMBER)) }
                response.status shouldBe HttpStatusCode.InternalServerError
                response.body<ApiError>().type shouldBe ErrorType.INTERNAL_SERVER_ERROR
                response.body<ApiError>().message shouldBe "Internal server error"
            }
        }

        test("unexpected read failures retain the StatusPages generic 500") {
            val fixture = ListRouteFixture()
            fixture.repository.failure = IllegalStateException("database unavailable")
            fixture.withApplication {
                val response = client.get("$LIST_PATH?$VALID_QUERY") { bearerAuth(createMockToken(ORGANIZATION_NUMBER)) }
                response.status shouldBe HttpStatusCode.InternalServerError
                response.body<ApiError>().type shouldBe ErrorType.INTERNAL_SERVER_ERROR
                response.body<ApiError>().message shouldBe "Internal server error"
            }
        }

        test("an unauthenticated call returns 401 before reading") {
            val fixture = ListRouteFixture()
            fixture.withApplication {
                client.get("$LIST_PATH?$VALID_QUERY").status shouldBe HttpStatusCode.Unauthorized
                fixture.repository.requestedLimit shouldBe null
            }
        }
    })

private const val EMPLOYEE_IDENT = "12345678901"
private const val ORGANIZATION_NUMBER = "910000001"
private const val LIST_PATH = "$API_V1_PATH$NARMESTELEDERBEHOV_PATH"
private const val VALID_QUERY = "orgNumber=$ORGANIZATION_NUMBER&createdAfter=1970-01-01T00:00:00Z"

private class ListRouteFixture(
    access: OrganizationAccessResult = OrganizationAccessResult.Granted("Bedrift AS"),
    personFound: Boolean = true,
    unavailable: UpstreamFailure? = null,
) {
    val repository = ListRouteRepository()
    private val texas = mockk<TexasHttpClient>()
    private val useCase = ListNarmestelederbehovUseCase(
        repository = repository,
        organizationAccess = { _, _ -> access },
        employeeName = NarmestelederbehovEmployeeName(
            repository,
        ) {
            unavailable?.let { EmployeeNameLookupResult.Unavailable(it) }
                ?: if (personFound) {
                    EmployeeNameLookupResult.Found(BehovPersonName("Looked", null, "Up"))
                } else {
                    EmployeeNameLookupResult.NotFound
                }
        },
    )

    init {
        texas.defaultMocks(systemBrukerOrganisasjon = DefaultOrganization.copy(ID = "0192:$ORGANIZATION_NUMBER"), scope = MASKINPORTEN_NL_SCOPE)
    }

    fun withApplication(block: suspend ApplicationTestBuilder.() -> Unit) {
        testRouteApplication(routes = { apiV1 { registerListNarmestelederbehovApi(useCase, texas) } }) {
            client = createJsonClient()
            block()
        }
    }
}

private class ListRouteRepository :
    OpenNarmestelederbehovRepository,
    NarmestelederbehovRepository {
    override suspend fun findPendingDialogs(createdBefore: Instant, limit: Int) = error("Unexpected findPendingDialogs")
    override suspend fun findFulfilledDialogs(createdBefore: Instant) = error("Unexpected findFulfilledDialogs")
    override suspend fun findDialogsToExpire() = error("Unexpected findDialogsToExpire")
    override suspend fun markDialogExpired(id: NarmestelederbehovId, expirationTime: Instant) = error("Unexpected markDialogExpired")
    override suspend fun create(behov: NewNarmestelederbehov) = error("Unexpected create")

    val rows = mutableListOf(routeDetails())
    val savedNames = mutableListOf<Pair<NarmestelederbehovId, BehovPersonName>>()
    var requestedLimit: Int? = null
    var requestedOrganization: OrganizationNumber? = null
    var requestedCreatedAfter: Instant? = null
    var countCalls = 0
    var failure: Exception? = null

    override suspend fun findOpen(organizationNumber: OrganizationNumber, createdAfter: Instant, limit: Int): List<NarmestelederbehovDetails> {
        failure?.let { throw it }
        requestedLimit = limit
        requestedOrganization = organizationNumber
        requestedCreatedAfter = createdAfter
        return rows.take(limit)
    }
    override suspend fun countOpen(organizationNumber: OrganizationNumber, createdAfter: Instant): Long {
        countCalls++
        return 42L
    }
    override suspend fun findDetails(id: NarmestelederbehovId) = error("List must not read by id")
    override suspend fun saveEmployeeName(id: NarmestelederbehovId, name: BehovPersonName) {
        savedNames += id to name
    }
    override suspend fun findForFulfillment(id: NarmestelederbehovId) = error("List must not read for fulfillment")
    override suspend fun findOpenFor(employee: Employee): List<NarmestelederbehovId> = error("List must not look up open behov by employee")

    override suspend fun markFulfilled(id: NarmestelederbehovId): MarkFulfilledResult = error("List must not fulfill")
    override suspend fun markDialogCompleted(id: NarmestelederbehovId): MarkDialogCompletedResult = error("List must not complete dialogs")

    override suspend fun markDialogCreated(
        id: NarmestelederbehovId,
        dialogId: UUID,
        name: BehovPersonName?,
    ): MarkDialogCreatedResult = error("Unexpected markDialogCreated")
}

private fun routeDetails() = narmestelederbehovDetails(
    employeeIdent = PersonalIdentificationNumber(EMPLOYEE_IDENT),
    organizationNumber = OrganizationNumber(ORGANIZATION_NUMBER),
    reason = BehovReason.DEAKTIVERT_LEDER,
)
