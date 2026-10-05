package no.nav.syfo.narmestelederrelasjon.api

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import createMockToken
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
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
import no.nav.syfo.narmestelederrelasjon.application.EmployeeNarmestelederrelasjon
import no.nav.syfo.narmestelederrelasjon.application.EmployeeNarmestelederrelasjonRepository
import no.nav.syfo.narmestelederrelasjon.application.ListActiveNarmesteledereForEmployeeUseCase
import no.nav.syfo.narmestelederrelasjon.observability.EMPLOYEE_LINEMANAGER_DISCARDED_EMAIL_ADDRESS_TOTAL
import no.nav.syfo.narmestelederrelasjon.observability.EMPLOYEE_LINEMANAGER_TOTAL
import no.nav.syfo.texas.client.TexasHttpClient
import no.nav.syfo.texas.client.TexasIntrospectionResponse
import java.io.IOException
import java.time.Instant
import java.util.UUID

private const val EMPLOYEE_PATH = "$INTERNAL_API_V1_PATH$EMPLOYEE_LINEMANAGER_API_PATH"
private val caller = PersonIdent("11223344556")
private val responseMapper = jacksonObjectMapper()

class EmployeeLinemanagerApiTest :
    FunSpec({
        val repository = EmployeeApiRepository()
        val useCase = ListActiveNarmesteledereForEmployeeUseCase(repository)
        val texasHttpClient = mockk<TexasHttpClient>()

        beforeTest {
            repository.rows = emptyList()
            repository.lookups.clear()
            clearMocks(texasHttpClient)
            // Texas active=true validates audience and expiry before the application handles the request.
            coEvery { texasHttpClient.introspectToken("tokenx", any()) } returns
                TexasIntrospectionResponse(active = true, acr = "Level4", pid = caller.value)
        }

        test("returns the unchanged JSON shape without exposing employee details") {
            repository.rows = listOf(
                apiRelation(UUID(0, 1)),
                apiRelation(UUID(0, 2)).copy(organizationNumber = OrganizationNumber("987654321")),
            )
            withEmployeeApi(useCase, texasHttpClient) {
                val response = client.getEmployee()
                response.status shouldBe HttpStatusCode.OK
                val body = response.bodyAsText()
                val json = responseMapper.readTree(body)
                json.fieldNames().asSequence().toList() shouldBe listOf("linemanagers")
                json.findValues("meta").shouldBeEmpty()
                json.findValues("manager").shouldBeEmpty()
                json.findValues("nationalIdentificationNumber").shouldBeEmpty()
                body shouldNotContain caller.value
                val managers = json.path("linemanagers")
                managers.isArray shouldBe true
                managers.toList().shouldHaveSize(2)
                managers[0].path("orgNumber").isTextual shouldBe true
                managers[0].path("orgNumber").asText() shouldBe "123456789"
                managers[0].path("id").asText() shouldBe UUID(0, 1).toString()
                managers[0].path("activeFrom").asText() shouldBe "2026-01-01T00:00:00Z"
                managers.forEach { manager ->
                    manager.fieldNames().asSequence().toList() shouldBe listOf("id", "orgNumber", "activeFrom", "name", "emailAddresses", "mobile")
                    manager.path("name").fieldNames().asSequence().toList() shouldBe listOf("firstName", "lastName", "middleName")
                    manager.path("name").path("firstName").asText() shouldBe "Manager"
                    manager.path("name").path("lastName").asText() shouldBe "Person"
                    manager.path("name").path("middleName").isNull shouldBe true
                    manager.path("emailAddresses").isArray shouldBe true
                    manager.path("mobile").isTextual shouldBe true
                    manager.path("mobile").asText() shouldBe "99999999"
                }
            }
        }

        test("serializes one email address as an array") {
            repository.rows = listOf(apiRelation().copy(managerEmail = "single@example.com"))
            withEmployeeApi(useCase, texasHttpClient) {
                val response = client.getEmployee()
                response.status shouldBe HttpStatusCode.OK
                val emails = responseMapper.readTree(response.bodyAsText()).path("linemanagers")[0].path("emailAddresses")
                emails.isArray shouldBe true
                emails.toList().shouldHaveSize(1)
                emails[0].isTextual shouldBe true
                emails[0].asText() shouldBe "single@example.com"
            }
        }

        test("serializes missing manager names as null") {
            repository.rows = listOf(apiRelation().copy(managerFirstName = null))
            withEmployeeApi(useCase, texasHttpClient) {
                val response = client.getEmployee()
                response.status shouldBe HttpStatusCode.OK
                responseMapper.readTree(response.bodyAsText()).path("linemanagers")[0].path("name").isNull shouldBe true
            }
        }

        test("uses authenticated pid without an organization filter") {
            withEmployeeApi(useCase, texasHttpClient) {
                client.getEmployee().status shouldBe HttpStatusCode.OK
                repository.lookups shouldBe listOf(caller to null)
            }
        }

        test("uses orgNumber as an optional filter") {
            withEmployeeApi(useCase, texasHttpClient) {
                client.getEmployee("?orgNumber=123456789").status shouldBe HttpStatusCode.OK
                repository.lookups shouldBe listOf(caller to OrganizationNumber("123456789"))
            }
        }

        listOf(
            "?orgNumber=12345678" to ErrorType.INVALID_FORMAT,
            "?orgNumber=" to ErrorType.INVALID_FORMAT,
            "?orgNumber" to ErrorType.BAD_REQUEST,
            "?orgNumber=A&orgNumber=B" to ErrorType.BAD_REQUEST,
        ).forEach { (query, errorType) ->
            test("returns 400 with $errorType for $query without a repository lookup") {
                withEmployeeApi(useCase, texasHttpClient) {
                    val response = client.getEmployee(query)
                    response.status shouldBe HttpStatusCode.BadRequest
                    val error = responseMapper.readTree(response.bodyAsText())
                    error.path("type").asText() shouldBe errorType.name
                    error.path("message").asText() shouldBe if (errorType == ErrorType.INVALID_FORMAT) {
                        "OrganizationNumber must be exactly 9 digits"
                    } else {
                        "Expected exactly one orgNumber parameter"
                    }
                    repository.lookups.shouldBeEmpty()
                }
            }
        }

        test("returns 401 without an authorization header") {
            withEmployeeApi(useCase, texasHttpClient) {
                client.get(EMPLOYEE_PATH).status shouldBe HttpStatusCode.Unauthorized
                repository.lookups.shouldBeEmpty()
                coVerify(exactly = 0) { texasHttpClient.introspectToken(any(), any()) }
            }
        }

        test("returns 401 for an inactive TokenX token") {
            coEvery { texasHttpClient.introspectToken("tokenx", any()) } returns TexasIntrospectionResponse(active = false)
            withEmployeeApi(useCase, texasHttpClient) {
                client.getEmployee().status shouldBe HttpStatusCode.Unauthorized
                repository.lookups.shouldBeEmpty()
            }
        }

        test("returns generic 500 when TokenX introspection fails") {
            val failureMessage = "Texas is unavailable"
            coEvery { texasHttpClient.introspectToken("tokenx", any()) } throws IOException(failureMessage)
            withEmployeeApi(useCase, texasHttpClient) {
                val response = client.getEmployee()
                response.status shouldBe HttpStatusCode.InternalServerError
                val body = response.bodyAsText()
                responseMapper.readTree(body).path("message").asText() shouldBe "Internal Server Error"
                body shouldNotContain failureMessage
                repository.lookups.shouldBeEmpty()
            }
        }

        test("returns 401 without leaking an invalid pid") {
            coEvery { texasHttpClient.introspectToken("tokenx", any()) } returns
                TexasIntrospectionResponse(active = true, acr = "Level4", pid = "abc")
            withEmployeeApi(useCase, texasHttpClient) {
                val response = client.getEmployee()
                response.status shouldBe HttpStatusCode.Unauthorized
                val body = response.bodyAsText()
                body shouldNotContain "abc"
                responseMapper.readTree(body).path("message").asText() shouldBe "Invalid token subject"
                repository.lookups.shouldBeEmpty()
            }
        }

        test("returns 401 when TokenX has no pid") {
            coEvery { texasHttpClient.introspectToken("tokenx", any()) } returns
                TexasIntrospectionResponse(active = true, acr = "Level4", pid = null)
            withEmployeeApi(useCase, texasHttpClient) {
                client.getEmployee().status shouldBe HttpStatusCode.Unauthorized
                repository.lookups.shouldBeEmpty()
            }
        }

        test("returns 403 below Level4") {
            coEvery { texasHttpClient.introspectToken("tokenx", any()) } returns
                TexasIntrospectionResponse(active = true, acr = "Level3", pid = caller.value)
            withEmployeeApi(useCase, texasHttpClient) {
                client.getEmployee().status shouldBe HttpStatusCode.Forbidden
                repository.lookups.shouldBeEmpty()
            }
        }

        listOf(MASKINPORTEN_ISSUER, "https://login.microsoftonline.com/tenant/v2.0").forEach { issuer ->
            test("returns 401 for $issuer") {
                withEmployeeApi(useCase, texasHttpClient) {
                    val response = client.get(EMPLOYEE_PATH) { bearerAuth(createMockToken("ignored", issuer = issuer)) }
                    response.status shouldBe HttpStatusCode.Unauthorized
                    repository.lookups.shouldBeEmpty()
                    coVerify(exactly = 0) { texasHttpClient.introspectToken(any(), any()) }
                }
            }
        }

        test("returns an empty collection when the organization has no matches") {
            withEmployeeApi(useCase, texasHttpClient) {
                val response = client.getEmployee("?orgNumber=123456789")
                response.status shouldBe HttpStatusCode.OK
                val managers = responseMapper.readTree(response.bodyAsText()).path("linemanagers")
                managers.isArray shouldBe true
                managers.toList().shouldBeEmpty()
            }
        }

        test("is not registered through the external API") {
            withEmployeeApi(useCase, texasHttpClient) {
                client.get("$API_V1_PATH$EMPLOYEE_LINEMANAGER_API_PATH").status shouldBe HttpStatusCode.NotFound
                repository.lookups.shouldBeEmpty()
            }
        }

        test("counts filtered and unfiltered successful requests") {
            val unfilteredBefore = requestCount("false")
            val filteredBefore = requestCount("true")
            withEmployeeApi(useCase, texasHttpClient) {
                client.getEmployee().status shouldBe HttpStatusCode.OK
                client.getEmployee("?orgNumber=123456789").status shouldBe HttpStatusCode.OK
                requestCount("false") shouldBeExactly unfilteredBefore + 1
                requestCount("true") shouldBeExactly filteredBefore + 1
            }
        }

        test("counts discarded emails but does not count empty entries or valid addresses") {
            val before = discardedCount()
            repository.rows = listOf(
                apiRelation().copy(managerEmail = "first@example.com,invalid;second@example.com"),
                apiRelation(UUID(0, 2)).copy(managerEmail = "invalid; ,"),
            )
            withEmployeeApi(useCase, texasHttpClient) {
                client.getEmployee().status shouldBe HttpStatusCode.OK
                discardedCount() shouldBeExactly before + 2
                repository.rows = listOf(apiRelation().copy(managerEmail = "first@example.com; ,"))
                client.getEmployee().status shouldBe HttpStatusCode.OK
                discardedCount() shouldBeExactly before + 2
            }
        }

        test("does not count rejected requests") {
            val filteredBefore = requestCount("true")
            val discardedBefore = discardedCount()
            withEmployeeApi(useCase, texasHttpClient) {
                client.getEmployee("?orgNumber=").status shouldBe HttpStatusCode.BadRequest
                requestCount("true") shouldBeExactly filteredBefore
                discardedCount() shouldBeExactly discardedBefore
            }
        }
    })

private class EmployeeApiRepository : EmployeeNarmestelederrelasjonRepository {
    var rows = emptyList<EmployeeNarmestelederrelasjon>()
    val lookups = mutableListOf<Pair<PersonIdent, OrganizationNumber?>>()

    override suspend fun findActive(employeeIdent: PersonIdent, organizationNumber: OrganizationNumber?): List<EmployeeNarmestelederrelasjon> {
        lookups.add(employeeIdent to organizationNumber)
        return rows
    }
}

private fun apiRelation(id: UUID = UUID(0, 1)) = EmployeeNarmestelederrelasjon(
    id = id,
    organizationNumber = OrganizationNumber("123456789"),
    activeFrom = Instant.parse("2026-01-01T00:00:00Z"),
    managerFirstName = "Manager",
    managerMiddleName = null,
    managerLastName = "Person",
    managerEmail = "manager@example.com",
    managerMobile = "99999999",
)

private suspend fun HttpClient.getEmployee(query: String = "") = get("$EMPLOYEE_PATH$query") {
    bearerAuth(createMockToken(caller.value, issuer = TOKEN_X_ISSUER))
}

private fun withEmployeeApi(
    useCase: ListActiveNarmesteledereForEmployeeUseCase,
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
                    registerEmployeeLinemanagerApi(useCase, texasHttpClient)
                }
            }
        }
        test()
    }
}

private fun requestCount(filtered: String): Double = METRICS_REGISTRY
    .find(EMPLOYEE_LINEMANAGER_TOTAL)
    .tag("filtered", filtered)
    .counter()
    ?.count() ?: 0.0

private fun discardedCount(): Double = METRICS_REGISTRY
    .find(EMPLOYEE_LINEMANAGER_DISCARDED_EMAIL_ADDRESS_TOTAL)
    .counter()
    ?.count() ?: 0.0
