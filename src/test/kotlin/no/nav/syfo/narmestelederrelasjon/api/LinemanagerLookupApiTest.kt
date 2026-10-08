package no.nav.syfo.narmestelederrelasjon.api

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import createMockToken
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.jackson.jackson
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.mockk
import no.nav.syfo.application.api.ApiError
import no.nav.syfo.application.api.ErrorType
import no.nav.syfo.application.api.INTERNAL_API_V1_PATH
import no.nav.syfo.application.api.installContentNegotiation
import no.nav.syfo.application.api.installStatusPages
import no.nav.syfo.application.auth.AddTokenIssuerPlugin
import no.nav.syfo.application.metric.METRICS_REGISTRY
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmestelederrelasjon.api.model.LinemanagerLookupRequest
import no.nav.syfo.narmestelederrelasjon.api.model.LinemanagerLookupResponse
import no.nav.syfo.narmestelederrelasjon.api.model.LinemanagerResponse
import no.nav.syfo.narmestelederrelasjon.application.ActiveNarmestelederrelasjon
import no.nav.syfo.narmestelederrelasjon.application.FakeActiveNarmestelederrelasjonRepository
import no.nav.syfo.narmestelederrelasjon.application.LookupActiveNarmestelederUseCase
import no.nav.syfo.narmestelederrelasjon.infrastructure.LOOKUP_NARMESTELEDER_DISCARDED_EMAIL_ADDRESS_TOTAL
import no.nav.syfo.narmestelederrelasjon.infrastructure.MicrometerDiscardedEmailAddressMetrics
import no.nav.syfo.texas.client.TexasHttpClient
import no.nav.syfo.texas.client.TexasIntrospectionResponse
import java.time.Instant
import java.util.UUID

private const val CALLING_APP = "calling-app-id"
private const val LOOKUP_PATH = "/internal/api/v1/lookup"
private val employeeIdent = PersonIdent("12345678901")
private val organizationNumber = OrganizationNumber("123456789")
private val narmestelederId = UUID.fromString("c8d10801-a0cc-4d94-a9ab-0088e850d4f4")

class LinemanagerLookupApiTest :
    FunSpec({
        val texasHttpClient = mockk<TexasHttpClient>()
        val repository = FakeActiveNarmestelederrelasjonRepository()
        val lookupActiveNarmesteleder = LookupActiveNarmestelederUseCase(repository, MicrometerDiscardedEmailAddressMetrics.lookupNarmesteleder())

        beforeTest {
            repository.reset()
            texasHttpClient.authorizes(CALLING_APP)
        }

        test("returns the active line manager with split email addresses") {
            repository.rows = listOf(
                ActiveNarmestelederrelasjon(
                    id = narmestelederId,
                    managerIdent = PersonIdent("10987654321"),
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

        test("returns only valid email addresses and counts the discarded ones") {
            repository.rows = listOf(activeRelation("leder@example.com;not-an-email, annen@example.com,ugyldig@"))
            val before = discardedCount()

            withLookupApi(lookupActiveNarmesteleder, texasHttpClient) {
                val response = client.postLookup(LinemanagerLookupRequest(employeeIdent.value, organizationNumber.value))

                response.status shouldBe HttpStatusCode.OK
                response.body<LinemanagerLookupResponse>().lineManager?.emailAddresses shouldBe
                    listOf("leder@example.com", "annen@example.com")
                discardedCount() shouldBeExactly before + 2
            }
        }

        test("returns the line manager with empty emailAddresses when no address is valid") {
            repository.rows = listOf(activeRelation("not-an-email;ugyldig@"))
            val before = discardedCount()

            withLookupApi(lookupActiveNarmesteleder, texasHttpClient) {
                val response = client.postLookup(LinemanagerLookupRequest(employeeIdent.value, organizationNumber.value))

                response.status shouldBe HttpStatusCode.OK
                response.body<LinemanagerLookupResponse>() shouldBe LinemanagerLookupResponse(
                    lineManager = LinemanagerResponse(
                        id = narmestelederId,
                        nationalIdentificationNumber = "10987654321",
                        emailAddresses = emptyList(),
                    )
                )
                discardedCount() shouldBeExactly before + 2
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
            texasHttpClient.authorizes("other-app-id")

            withLookupApi(lookupActiveNarmesteleder, texasHttpClient) {
                val response = client.postLookup(LinemanagerLookupRequest(employeeIdent.value, organizationNumber.value))

                response.status shouldBe HttpStatusCode.Forbidden
            }
        }
    })

private fun activeRelation(managerEmail: String) = ActiveNarmestelederrelasjon(
    id = narmestelederId,
    managerIdent = PersonIdent("10987654321"),
    managerEmail = managerEmail,
    activeFrom = Instant.parse("2026-01-01T00:00:00Z"),
)

private fun discardedCount(): Double = METRICS_REGISTRY
    .find(LOOKUP_NARMESTELEDER_DISCARDED_EMAIL_ADDRESS_TOTAL)
    .counter()
    ?.count() ?: 0.0

private fun TexasHttpClient.authorizes(azp: String) {
    coEvery { introspectToken("azuread", any()) } returns TexasIntrospectionResponse(active = true, azp = azp)
}

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
                route(INTERNAL_API_V1_PATH) {
                    install(AddTokenIssuerPlugin)
                    registerLineManagerLookupApi(
                        lookupActiveNarmesteleder = lookupActiveNarmesteleder,
                        texasHttpClient = texasHttpClient,
                        preAuthorizedApps = setOf(CALLING_APP),
                    )
                }
            }
        }
        test()
    }
}
