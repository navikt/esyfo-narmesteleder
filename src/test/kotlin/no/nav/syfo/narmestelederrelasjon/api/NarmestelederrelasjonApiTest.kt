package no.nav.syfo.narmestelederrelasjon.api

import DefaultOrganization
import createMockToken
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.mockk
import no.nav.syfo.application.api.installContentNegotiation
import no.nav.syfo.application.api.installStatusPages
import no.nav.syfo.application.auth.AddTokenIssuerPlugin
import no.nav.syfo.narmesteleder.api.internal.INTERNAL_API_V1_PATH
import no.nav.syfo.narmesteleder.api.internal.v1.registerLinemanagerRevokeApi
import no.nav.syfo.narmesteleder.service.LinemanagerRevokeService
import no.nav.syfo.narmestelederrelasjon.application.GetNarmestelederrelasjon
import no.nav.syfo.narmestelederrelasjon.application.NarmestelederrelasjonOrganization
import no.nav.syfo.narmestelederrelasjon.application.NarmestelederrelasjonOrganizationAccess
import no.nav.syfo.narmestelederrelasjon.application.NarmestelederrelasjonRepository
import no.nav.syfo.narmestelederrelasjon.domain.Narmestelederrelasjon
import no.nav.syfo.narmestelederrelasjon.domain.RelationPerson
import no.nav.syfo.narmestelederrelasjon.domain.RelationPersonName
import no.nav.syfo.texas.MASKINPORTEN_NL_SCOPE
import no.nav.syfo.texas.client.AuthorizationDetail
import no.nav.syfo.texas.client.TexasHttpClient
import no.nav.syfo.texas.client.TexasIntrospectionResponse
import java.util.UUID

private const val TOKEN_X_ISSUER = "https://tokenx.example.com"
private const val MASKINPORTEN_ISSUER = "https://test.maskinporten.no"

class NarmestelederrelasjonApiTest :
    DescribeSpec({
        val texasHttpClient = mockk<TexasHttpClient>()
        val repository = mockk<NarmestelederrelasjonRepository>()
        val organizationAccess = mockk<NarmestelederrelasjonOrganizationAccess>()
        val organization = mockk<NarmestelederrelasjonOrganization>()
        val getNarmestelederrelasjon = GetNarmestelederrelasjon(repository, organizationAccess, organization)
        val id = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val employeeIdent = "12345678901"

        fun relation() = Narmestelederrelasjon(
            id = id,
            orgNumber = "123456789",
            employee = RelationPerson(employeeIdent, RelationPersonName("Employee", null, "Person")),
        )

        fun withTestApplication(test: suspend ApplicationTestBuilder.() -> Unit) {
            testApplication {
                application {
                    installContentNegotiation()
                    installStatusPages()
                    routing {
                        route(INTERNAL_API_V1_PATH) {
                            install(AddTokenIssuerPlugin)
                            registerLinemanagerRevokeApi(
                                texasHttpClient = texasHttpClient,
                                linemanagerRevokeService = mockk<LinemanagerRevokeService>(),
                            )
                            registerNarmestelederrelasjonApi(getNarmestelederrelasjon, texasHttpClient)
                        }
                    }
                }
                test()
            }
        }

        fun token(issuer: String = TOKEN_X_ISSUER) = createMockToken(employeeIdent, issuer = issuer)
        fun path(id: String) = NARMESTELEDERRELASJON_API_PATH.replace("{id}", id)

        fun introspectMaskinporten() {
            coEvery { texasHttpClient.introspectToken("maskinporten", any()) } returns TexasIntrospectionResponse(
                active = true,
                scope = MASKINPORTEN_NL_SCOPE,
                consumer = DefaultOrganization,
                authorizationDetails = listOf(
                    AuthorizationDetail(
                        type = "urn:altinn:systemuser",
                        systemuserOrg = DefaultOrganization,
                        systemuserId = listOf("some-user-id"),
                        systemId = "some-system-id",
                    ),
                ),
            )
        }

        beforeTest {
            clearMocks(texasHttpClient, repository, organizationAccess, organization, answers = false)
            coEvery { texasHttpClient.introspectToken("tokenx", any()) } returns
                TexasIntrospectionResponse(active = true, acr = "Level4", pid = employeeIdent)
            coEvery { repository.findActiveById(id) } returns relation()
            coEvery { organizationAccess.hasAccess(any(), "123456789") } returns true
            coEvery { organization.findName("123456789") } returns "Organization"
        }

        it("returns the exact PII response body with no-store") {
            withTestApplication {
                val response = client.get(path(id.toString())) {
                    bearerAuth(token())
                }

                response.status shouldBe HttpStatusCode.OK
                response.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                response.bodyAsText() shouldBe """
                    {"linemanagerRelation":{"id":"00000000-0000-0000-0000-000000000001","employee":{"name":{"firstName":"Employee","middleName":null,"lastName":"Person"},"nationalIdentificationNumber":"12345678901"},"organization":{"orgNumber":"123456789","name":"Organization"}}}
                """.trimIndent()
            }
        }

        it("returns the relation with no-store for a Maskinporten system user with organization access") {
            introspectMaskinporten()

            withTestApplication {
                val response = client.get(path(id.toString())) {
                    bearerAuth(token(MASKINPORTEN_ISSUER))
                }

                response.status shouldBe HttpStatusCode.OK
                response.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                response.bodyAsText() shouldBe """
                    {"linemanagerRelation":{"id":"00000000-0000-0000-0000-000000000001","employee":{"name":{"firstName":"Employee","middleName":null,"lastName":"Person"},"nationalIdentificationNumber":"12345678901"},"organization":{"orgNumber":"123456789","name":"Organization"}}}
                """.trimIndent()
            }
        }

        it("returns an empty masked 404 for a Maskinporten system user without organization access") {
            introspectMaskinporten()
            coEvery { organizationAccess.hasAccess(any(), "123456789") } returns false

            withTestApplication {
                val response = client.get(path(id.toString())) {
                    bearerAuth(token(MASKINPORTEN_ISSUER))
                }

                response.status shouldBe HttpStatusCode.NotFound
                response.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                response.bodyAsText() shouldBe ""
            }
        }

        it("returns empty masked 404 for malformed, unknown, unauthorized and inactive-sykmelding relations") {
            coEvery { repository.findActiveById(id) } returnsMany listOf(null, relation(), null)
            coEvery { organizationAccess.hasAccess(any(), "123456789") } returns false

            withTestApplication {
                val malformed = client.get(path("not-a-uuid")) { bearerAuth(token()) }
                val unknown = client.get(path(id.toString())) { bearerAuth(token()) }
                val unauthorized = client.get(path(id.toString())) { bearerAuth(token()) }
                val withoutActiveSykmelding = client.get(path(id.toString())) { bearerAuth(token()) }

                listOf(malformed, unknown, unauthorized, withoutActiveSykmelding).forEach { response ->
                    response.status shouldBe HttpStatusCode.NotFound
                    response.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                    response.bodyAsText() shouldBe ""
                }
            }
        }

        it("returns 401 when authentication is missing") {
            withTestApplication {
                client.get(path(id.toString())).status shouldBe HttpStatusCode.Unauthorized
            }
        }
    })
