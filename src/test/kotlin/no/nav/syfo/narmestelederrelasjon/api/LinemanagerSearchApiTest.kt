package no.nav.syfo.narmestelederrelasjon.api

import DefaultOrganization
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import createMockToken
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.mockk
import no.nav.syfo.application.api.API_V1_PATH
import no.nav.syfo.application.api.ErrorType
import no.nav.syfo.application.api.INTERNAL_API_V1_PATH
import no.nav.syfo.application.api.installContentNegotiation
import no.nav.syfo.application.api.installStatusPages
import no.nav.syfo.application.auth.AddTokenIssuerPlugin
import no.nav.syfo.application.metric.METRICS_REGISTRY
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmestelederrelasjon.application.LinemanagerSearchCursor
import no.nav.syfo.narmestelederrelasjon.application.NarmestelederrelasjonSearchQuery
import no.nav.syfo.narmestelederrelasjon.application.NarmestelederrelasjonSearchRepository
import no.nav.syfo.narmestelederrelasjon.application.NarmestelederrelasjonSearchRow
import no.nav.syfo.narmestelederrelasjon.application.SearchActiveNarmestelederrelasjonerUseCase
import no.nav.syfo.narmestelederrelasjon.application.SearchManager
import no.nav.syfo.narmestelederrelasjon.application.SearchName
import no.nav.syfo.narmestelederrelasjon.application.SearchNarmestelederrelasjon
import no.nav.syfo.narmestelederrelasjon.application.SearchPerson
import no.nav.syfo.narmestelederrelasjon.application.toOpaqueCursor
import no.nav.syfo.narmestelederrelasjon.observability.LINEMANAGER_SEARCH_TOTAL
import no.nav.syfo.organisasjonstilgang.application.AccessToken
import no.nav.syfo.organisasjonstilgang.application.DenialReason
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccess
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessResult
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessSubject
import no.nav.syfo.texas.MASKINPORTEN_NL_SCOPE
import no.nav.syfo.texas.client.AuthorizationDetail
import no.nav.syfo.texas.client.TexasHttpClient
import no.nav.syfo.texas.client.TexasIntrospectionResponse
import java.time.Instant
import java.util.UUID

private const val SEARCH_PATH = "$INTERNAL_API_V1_PATH$LINEMANAGER_SEARCH_API_PATH"
private const val SYSTEM_USER_ID = "system-user-id"
private val organizationNumber = OrganizationNumber("123456789")
private val callerPid = PersonIdent("11223344556")
private val responseMapper = jacksonObjectMapper()

class LinemanagerSearchApiTest :
    FunSpec({
        val access = RouteOrganizationAccess()
        val repository = RouteSearchRepository()
        val useCase = SearchActiveNarmestelederrelasjonerUseCase(access, repository)
        val texasHttpClient = mockk<TexasHttpClient>()

        beforeTest {
            access.result = OrganizationAccessResult.Granted(organizationName = null)
            access.calls.clear()
            repository.rows = listOf(searchRow(id = 1))
            repository.queries.clear()
            clearMocks(texasHttpClient)
            texasHttpClient.introspectsMaskinportenSystemUser()
            coEvery { texasHttpClient.introspectToken("tokenx", any()) } returns
                TexasIntrospectionResponse(active = true, acr = "Level4", pid = callerPid.value)
        }

        test("is not available through the external API") {
            withSearchApi(useCase, texasHttpClient) {
                val response = client.post("$API_V1_PATH$LINEMANAGER_SEARCH_API_PATH") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"orgNumber":"${organizationNumber.value}"}""")
                    bearerAuth(systemToken())
                }

                response.status shouldBe HttpStatusCode.NotFound
                repository.queries.shouldBeEmpty()
            }
        }

        test("returns paginated results for an authorized Maskinporten system user") {
            repository.rows = listOf(searchRow(id = 1), searchRow(id = 2, employeeFnr = "12345678911"))
            withSearchApi(useCase, texasHttpClient) {
                val response = client.search("""{"orgNumber":"${organizationNumber.value}","pageSize":1}""")

                response.status shouldBe HttpStatusCode.OK
                val body = response.json()
                body.path("linemanagers").size() shouldBe 1
                val linemanager = body.path("linemanagers")[0]
                linemanager.path("id").asText() shouldBe UUID(0, 1).toString()
                linemanager.path("orgNumber").asText() shouldBe organizationNumber.value
                linemanager.path("activeFrom").asText() shouldBe "2026-01-01T00:00:00Z"
                linemanager.path("employee").path("nationalIdentificationNumber").asText() shouldBe "12345678910"
                linemanager.path("employee").path("name").path("firstName").asText() shouldBe "Ola"
                linemanager.path("employee").path("name").path("middleName").isNull shouldBe true
                linemanager.path("manager").path("nationalIdentificationNumber").asText() shouldBe "10987654321"
                linemanager.path("manager").path("email").asText() shouldBe "kari@example.com"
                linemanager.path("manager").path("mobile").asText() shouldBe "99999999"
                body.path("meta").path("size").asInt() shouldBe 1
                body.path("meta").path("pageSize").asInt() shouldBe 1
                body.path("meta").path("hasMore").asBoolean() shouldBe true
                body.path("meta").path("nextPageToken").asText() shouldBe
                    LinemanagerSearchCursor(firstName = "ola", lastName = "nordmann", id = 1).toOpaqueCursor()
                access.calls shouldBe listOf(
                    OrganizationAccessSubject.LpsSystemUser(SYSTEM_USER_ID, organizationNumber) to organizationNumber,
                )
                repository.queries.single().pageSize shouldBe 1
            }
        }

        test("returns results for an authorized TokenX personnel manager") {
            withSearchApi(useCase, texasHttpClient) {
                val token = userToken()
                val response = client.search("""{"orgNumber":"${organizationNumber.value}"}""", token)

                response.status shouldBe HttpStatusCode.OK
                response.json().path("linemanagers")[0].path("manager").path("nationalIdentificationNumber").asText() shouldBe
                    "10987654321"
                access.calls shouldBe listOf(
                    OrganizationAccessSubject.PersonnelManager(callerPid, AccessToken(token)) to organizationNumber,
                )
            }
        }

        test("uses pageToken and default page size when querying the next page") {
            val cursor = LinemanagerSearchCursor(firstName = "ola", lastName = "nordmann", id = 1)
            withSearchApi(useCase, texasHttpClient) {
                val response = client.search(
                    """{"orgNumber":"${organizationNumber.value}","pageToken":"${cursor.toOpaqueCursor()}"}""",
                )

                response.status shouldBe HttpStatusCode.OK
                repository.queries shouldBe listOf(
                    NarmestelederrelasjonSearchQuery(orgNumber = organizationNumber, pageSize = 50, cursor = cursor),
                )
            }
        }

        test("passes request filters to the repository") {
            withSearchApi(useCase, texasHttpClient) {
                val response = client.search(
                    """
                    {
                      "orgNumber": "${organizationNumber.value}",
                      "managerNationalIdentificationNumber": "10987654321",
                      "employeeNationalIdentificationNumber": "12345678910",
                      "hasActiveSickLeave": true,
                      "text": "Kari Nordmann"
                    }
                    """.trimIndent(),
                )

                response.status shouldBe HttpStatusCode.OK
                repository.queries shouldBe listOf(
                    NarmestelederrelasjonSearchQuery(
                        orgNumber = organizationNumber,
                        managerNationalIdentificationNumber = PersonIdent("10987654321"),
                        employeeNationalIdentificationNumber = PersonIdent("12345678910"),
                        text = "Kari Nordmann",
                        hasActiveSickLeave = true,
                        pageSize = 50,
                    ),
                )
            }
        }

        test("normalizes blank text to null") {
            withSearchApi(useCase, texasHttpClient) {
                client.search("""{"orgNumber":"${organizationNumber.value}","text":"   "}""").status shouldBe HttpStatusCode.OK

                repository.queries.single().text shouldBe null
                repository.queries.single().nationalIdentificationNumber shouldBe null
            }
        }

        test("uses an eleven-digit text value to query either national identification number") {
            withSearchApi(useCase, texasHttpClient) {
                client.search("""{"orgNumber":"${organizationNumber.value}","text":"12345678910"}""").status shouldBe HttpStatusCode.OK

                repository.queries.single().text shouldBe null
                repository.queries.single().nationalIdentificationNumber shouldBe PersonIdent("12345678910")
            }
        }

        test("returns 400 when text exceeds 50 characters") {
            withSearchApi(useCase, texasHttpClient) {
                val response = client.search("""{"orgNumber":"${organizationNumber.value}","text":"${"a".repeat(51)}"}""")

                response.shouldBeError(HttpStatusCode.BadRequest, ErrorType.BAD_REQUEST, "text must be at most 50 characters")
                repository.queries.shouldBeEmpty()
            }
        }

        listOf(
            "not-a-valid-token" to "malformed",
            "djE6MQ" to "v1",
        ).forEach { (pageToken, description) ->
            test("returns 400 for $description pageToken") {
                withSearchApi(useCase, texasHttpClient) {
                    val response = client.search("""{"orgNumber":"${organizationNumber.value}","pageToken":"$pageToken"}""")

                    response.shouldBeError(HttpStatusCode.BadRequest, ErrorType.INVALID_FORMAT, "Invalid pageToken")
                    repository.queries.shouldBeEmpty()
                }
            }
        }

        listOf(
            """{"orgNumber":"12345678","pageSize":1}""" to "invalid orgNumber",
            """{"pageSize":1}""" to "missing orgNumber",
            """{"orgNumber":"${organizationNumber.value}","managerNationalIdentificationNumber":"1098765432"}""" to
                "invalid managerNationalIdentificationNumber",
            """{"orgNumber":"${organizationNumber.value}","employeeNationalIdentificationNumber":"1234567891"}""" to
                "invalid employeeNationalIdentificationNumber",
            """{"orgNumber":""" to "malformed JSON",
        ).forEach { (body, description) ->
            test("returns 400 for $description before checking access") {
                withSearchApi(useCase, texasHttpClient) {
                    val response = client.search(body)

                    response.shouldBeError(HttpStatusCode.BadRequest, ErrorType.INVALID_FORMAT, "Invalid search request")
                    access.calls.shouldBeEmpty()
                    repository.queries.shouldBeEmpty()
                }
            }
        }

        test("returns 400 naming an unknown field before checking access") {
            withSearchApi(useCase, texasHttpClient) {
                val response = client.search("""{"orgNumber":"${organizationNumber.value}","unknownField":"value"}""")

                response.shouldBeError(
                    HttpStatusCode.BadRequest,
                    ErrorType.INVALID_FORMAT,
                    "Invalid search request. Unknown field: unknownField",
                )
                access.calls.shouldBeEmpty()
                repository.queries.shouldBeEmpty()
            }
        }

        listOf(
            Triple(
                DenialReason.MISSING_ORGANIZATION_ACCESS,
                ErrorType.MISSING_ORG_ACCESS,
                "User lacks access to organization: ${organizationNumber.value}",
            ),
            Triple(
                DenialReason.MISSING_RESOURCE_ACCESS,
                ErrorType.MISSING_ALITINN_RESOURCE_ACCESS,
                "User lacks access to required Altinn resource for organization: ${organizationNumber.value}",
            ),
            Triple(
                DenialReason.SYSTEM_USER_REJECTED,
                ErrorType.MISSING_ALITINN_RESOURCE_ACCESS,
                "System user does not have access to nav_syfo_oppgi-narmesteleder resource",
            ),
        ).forEach { (reason, errorType, message) ->
            test("returns 403 for $reason before validating text and without querying") {
                access.result = OrganizationAccessResult.Denied(reason)
                withSearchApi(useCase, texasHttpClient) {
                    val response = client.search("""{"orgNumber":"${organizationNumber.value}","text":"${"a".repeat(51)}"}""")

                    response.shouldBeError(HttpStatusCode.Forbidden, errorType, message)
                    repository.queries.shouldBeEmpty()
                }
            }
        }

        test("returns 401 without a bearer token") {
            withSearchApi(useCase, texasHttpClient) {
                val response = client.post(SEARCH_PATH) {
                    contentType(ContentType.Application.Json)
                    setBody("""{"orgNumber":"${organizationNumber.value}"}""")
                }

                response.status shouldBe HttpStatusCode.Unauthorized
                access.calls.shouldBeEmpty()
                repository.queries.shouldBeEmpty()
            }
        }

        test("counts successful searches by principal type") {
            val systemSearchesBefore = searchCount("system")
            val userSearchesBefore = searchCount("user")
            withSearchApi(useCase, texasHttpClient) {
                client.search("""{"orgNumber":"${organizationNumber.value}"}""").status shouldBe HttpStatusCode.OK
                client.search("""{"orgNumber":"${organizationNumber.value}"}""", userToken()).status shouldBe HttpStatusCode.OK

                searchCount("system") shouldBeExactly systemSearchesBefore + 1
                searchCount("user") shouldBeExactly userSearchesBefore + 1
            }
        }

        test("does not count rejected searches") {
            val systemSearchesBefore = searchCount("system")
            access.result = OrganizationAccessResult.Denied(DenialReason.SYSTEM_USER_REJECTED)
            withSearchApi(useCase, texasHttpClient) {
                client.search("""{"orgNumber":"${organizationNumber.value}"}""").status shouldBe HttpStatusCode.Forbidden
                client.search("""{"orgNumber":"${organizationNumber.value}","text":"${"a".repeat(51)}"}""").status shouldBe
                    HttpStatusCode.Forbidden

                searchCount("system") shouldBeExactly systemSearchesBefore
            }
        }
    })

private class RouteOrganizationAccess : OrganizationAccess {
    var result: OrganizationAccessResult = OrganizationAccessResult.Granted(organizationName = null)
    val calls = mutableListOf<Pair<OrganizationAccessSubject, OrganizationNumber>>()

    override suspend fun evaluate(
        subject: OrganizationAccessSubject,
        organizationNumber: OrganizationNumber,
    ): OrganizationAccessResult {
        calls.add(subject to organizationNumber)
        return result
    }
}

private class RouteSearchRepository : NarmestelederrelasjonSearchRepository {
    var rows = emptyList<NarmestelederrelasjonSearchRow>()
    val queries = mutableListOf<NarmestelederrelasjonSearchQuery>()

    override suspend fun search(query: NarmestelederrelasjonSearchQuery): List<NarmestelederrelasjonSearchRow> {
        queries.add(query)
        return rows
    }
}

private fun searchRow(id: Int, employeeFnr: String = "12345678910") = NarmestelederrelasjonSearchRow(
    cursor = LinemanagerSearchCursor(firstName = "ola", lastName = "nordmann", id = id),
    linemanager = SearchNarmestelederrelasjon(
        id = UUID(0, id.toLong()),
        orgNumber = organizationNumber,
        activeFrom = Instant.parse("2026-01-01T00:00:00Z"),
        employee = SearchPerson(
            nationalIdentificationNumber = PersonIdent(employeeFnr),
            name = SearchName(firstName = "Ola", middleName = null, lastName = "Nordmann"),
        ),
        manager = SearchManager(
            nationalIdentificationNumber = PersonIdent("10987654321"),
            name = SearchName(firstName = "Kari", middleName = null, lastName = "Nordmann"),
            email = "kari@example.com",
            mobile = "99999999",
        ),
    ),
)

private fun TexasHttpClient.introspectsMaskinportenSystemUser() {
    coEvery { introspectToken("maskinporten", any()) } returns TexasIntrospectionResponse(
        active = true,
        scope = MASKINPORTEN_NL_SCOPE,
        consumer = DefaultOrganization,
        authorizationDetails = listOf(
            AuthorizationDetail(
                type = "urn:altinn:systemuser",
                systemuserOrg = DefaultOrganization.copy(ID = "0192:${organizationNumber.value}"),
                systemuserId = listOf(SYSTEM_USER_ID),
                systemId = "system-id",
            ),
        ),
    )
}

private fun systemToken() = createMockToken(organizationNumber.value, issuer = MASKINPORTEN_ISSUER)

private fun userToken() = createMockToken(callerPid.value, issuer = TOKEN_X_ISSUER)

private suspend fun HttpClient.search(body: String, token: String = systemToken()): HttpResponse = post(SEARCH_PATH) {
    contentType(ContentType.Application.Json)
    setBody(body)
    bearerAuth(token)
}

private suspend fun HttpResponse.json(): JsonNode = responseMapper.readTree(bodyAsText())

private suspend fun HttpResponse.shouldBeError(status: HttpStatusCode, type: ErrorType, message: String) {
    this.status shouldBe status
    val error = json()
    error.path("type").asText() shouldBe type.name
    error.path("message").asText() shouldBe message
}

private fun searchCount(principalType: String): Double = METRICS_REGISTRY
    .find(LINEMANAGER_SEARCH_TOTAL)
    .tag("principal_type", principalType)
    .counter()
    ?.count() ?: 0.0

private fun withSearchApi(
    useCase: SearchActiveNarmestelederrelasjonerUseCase,
    texasHttpClient: TexasHttpClient,
    test: suspend ApplicationTestBuilder.() -> Unit,
) {
    testApplication {
        application {
            installContentNegotiation()
            installStatusPages()
            routing {
                route(INTERNAL_API_V1_PATH) {
                    install(AddTokenIssuerPlugin)
                    registerLinemanagerSearchApi(useCase, texasHttpClient)
                }
            }
        }
        test()
    }
}
