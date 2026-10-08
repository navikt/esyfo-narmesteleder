package no.nav.syfo.narmestelederbehov.api

import DefaultOrganization
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
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
import no.nav.syfo.narmestelederbehov.application.BehovPersonName
import no.nav.syfo.narmestelederbehov.application.EmployeeNameLookup
import no.nav.syfo.narmestelederbehov.application.ListNarmestelederbehovUseCase
import no.nav.syfo.narmestelederbehov.application.MarkFulfilledResult
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovDetails
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovEmployeeName
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovRepository
import no.nav.syfo.narmestelederbehov.application.OpenNarmestelederbehovRepository
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import no.nav.syfo.organisasjonstilgang.application.DenialReason
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccess
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessResult
import no.nav.syfo.texas.MASKINPORTEN_NL_SCOPE
import no.nav.syfo.texas.client.TexasHttpClient
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
) {
    val repository = ListRouteRepository()
    private val texas = mockk<TexasHttpClient>()
    private val useCase = ListNarmestelederbehovUseCase(
        repository = repository,
        organizationAccess = OrganizationAccess { _, _ -> access },
        employeeName = NarmestelederbehovEmployeeName(
            repository,
            EmployeeNameLookup {
                if (personFound) BehovPersonName("Looked", null, "Up") else null
            },
        ),
    )

    init {
        texas.defaultMocks(systemBrukerOrganisasjon = DefaultOrganization.copy(ID = "0192:$ORGANIZATION_NUMBER"), scope = MASKINPORTEN_NL_SCOPE)
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
                        registerListNarmestelederbehovApi(useCase, texas)
                    }
                }
            }
            block()
        }
    }
}

private class ListRouteRepository :
    OpenNarmestelederbehovRepository,
    NarmestelederbehovRepository {
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
    override suspend fun markFulfilled(id: NarmestelederbehovId): MarkFulfilledResult = error("List must not fulfill")
}

private fun routeDetails() = NarmestelederbehovDetails(
    id = NarmestelederbehovId(UUID.randomUUID()),
    employeeIdent = PersonIdent(EMPLOYEE_IDENT),
    organizationNumber = OrganizationNumber(ORGANIZATION_NUMBER),
    mainOrganizationNumber = "910000002",
    managerIdent = null,
    firstName = "Stored",
    middleName = "Middle",
    lastName = "Name",
    created = Instant.EPOCH,
    updated = Instant.EPOCH,
    status = BehovStatus.BEHOV_CREATED,
    reason = BehovReason.DEAKTIVERT_LEDER,
)
