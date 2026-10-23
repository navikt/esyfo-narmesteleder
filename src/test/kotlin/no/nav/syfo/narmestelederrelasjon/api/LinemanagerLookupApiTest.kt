package no.nav.syfo.narmestelederrelasjon.api

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.mockk.mockk
import no.nav.syfo.createMockToken
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.narmestelederrelasjon.api.model.LinemanagerLookupRequest
import no.nav.syfo.narmestelederrelasjon.api.model.LinemanagerLookupResponse
import no.nav.syfo.narmestelederrelasjon.api.model.LinemanagerResponse
import no.nav.syfo.narmestelederrelasjon.application.ActiveNarmestelederrelasjon
import no.nav.syfo.narmestelederrelasjon.application.FakeActiveNarmestelederrelasjonRepository
import no.nav.syfo.narmestelederrelasjon.application.lookup.LookupActiveNarmestelederUseCase
import no.nav.syfo.narmestelederrelasjon.infrastructure.MicrometerDiscardedEmailAddressMetrics
import no.nav.syfo.platform.api.ApiError
import no.nav.syfo.platform.api.ErrorType
import no.nav.syfo.platform.api.createJsonClient
import no.nav.syfo.platform.api.internalApiV1
import no.nav.syfo.platform.api.testRouteApplication
import no.nav.syfo.platform.auth.texas.client.TexasHttpClient
import no.nav.syfo.platform.auth.texas.stubAzureAd
import java.time.Instant
import java.util.UUID

private const val CALLING_APP = "calling-app-id"
private const val LOOKUP_PATH = "/internal/api/v1/lookup"
private val employeeIdent = PersonalIdentificationNumber("12345678901")
private val organizationNumber = OrganizationNumber("123456789")
private val narmestelederId = UUID.fromString("c8d10801-a0cc-4d94-a9ab-0088e850d4f4")

class LinemanagerLookupApiTest :
    FunSpec({
        val texasHttpClient = mockk<TexasHttpClient>()
        val repository = FakeActiveNarmestelederrelasjonRepository()
        val lookupActiveNarmesteleder = LookupActiveNarmestelederUseCase(repository, MicrometerDiscardedEmailAddressMetrics.lookupNarmesteleder())

        beforeTest {
            repository.reset()
            texasHttpClient.stubAzureAd(CALLING_APP)
        }

        test("returns the active line manager with split email addresses") {
            repository.rows = listOf(
                ActiveNarmestelederrelasjon(
                    id = narmestelederId,
                    managerIdent = PersonalIdentificationNumber("10987654321"),
                    managerEmail = " leder@example.com, , annen@example.com ",
                    activeFrom = Instant.parse("2026-01-01T00:00:00Z"),
                )
            )

            withLookupApi(lookupActiveNarmesteleder, texasHttpClient) {
                val response = client.postLookup(LinemanagerLookupRequest(employeeIdent.value, organizationNumber.value))

                response.status shouldBe HttpStatusCode.OK
                response.body<LinemanagerLookupResponse>() shouldBe LinemanagerLookupResponse(
                    lineManager = LinemanagerResponse(
                        id = narmestelederId,
                        nationalIdentificationNumber = "10987654321",
                        emailAddresses = listOf("leder@example.com", "annen@example.com"),
                    )
                )
            }
        }

        test("returns null when no active line manager exists") {
            withLookupApi(lookupActiveNarmesteleder, texasHttpClient) {
                val response = client.postLookup(LinemanagerLookupRequest(employeeIdent.value, organizationNumber.value))

                response.status shouldBe HttpStatusCode.OK
                response.body<LinemanagerLookupResponse>() shouldBe LinemanagerLookupResponse(null)
            }
        }

        test("rejects missing organizationNumber without a database lookup") {
            withLookupApi(lookupActiveNarmesteleder, texasHttpClient) {
                val response = client.postLookup(LinemanagerLookupRequest(employeeIdent.value, null))

                response.status shouldBe HttpStatusCode.BadRequest
                repository.lookups shouldBe emptyList()
            }
        }

        test("rejects invalid employeeNationalIdentificationNumber with INVALID_FORMAT") {
            withLookupApi(lookupActiveNarmesteleder, texasHttpClient) {
                val response = client.postLookup(LinemanagerLookupRequest("invalid", organizationNumber.value))

                response.status shouldBe HttpStatusCode.BadRequest
                response.body<ApiError>().type shouldBe ErrorType.INVALID_FORMAT
            }
        }

        test("rejects applications outside the pre-authorized allowlist") {
            texasHttpClient.stubAzureAd("other-app-id")

            withLookupApi(lookupActiveNarmesteleder, texasHttpClient) {
                val response = client.postLookup(LinemanagerLookupRequest(employeeIdent.value, organizationNumber.value))

                response.status shouldBe HttpStatusCode.Forbidden
            }
        }
    })

private suspend fun HttpClient.postLookup(request: LinemanagerLookupRequest): HttpResponse = post(LOOKUP_PATH) {
    contentType(ContentType.Application.Json)
    setBody(request)
    bearerAuth(createMockToken("ignored", issuer = "https://login.microsoftonline.com/tenant/v2.0"))
}

private fun withLookupApi(
    lookupActiveNarmesteleder: LookupActiveNarmestelederUseCase,
    texasHttpClient: TexasHttpClient,
    test: suspend ApplicationTestBuilder.() -> Unit,
) {
    testRouteApplication(
        routes = {
            internalApiV1 {
                registerLineManagerLookupApi(
                    lookupActiveNarmesteleder = lookupActiveNarmesteleder,
                    texasHttpClient = texasHttpClient,
                    preAuthorizedApps = setOf(CALLING_APP),
                )
            }
        },
    ) {
        client = createJsonClient()
        test()
    }
}
