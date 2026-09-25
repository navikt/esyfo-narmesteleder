package no.nav.syfo.narmestelederrelasjon.api

import DefaultOrganization
import createMockToken
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
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
import io.mockk.coVerify
import io.mockk.mockk
import no.nav.syfo.application.api.installContentNegotiation
import no.nav.syfo.application.api.installStatusPages
import no.nav.syfo.application.auth.AddTokenIssuerPlugin
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmesteleder.api.internal.INTERNAL_API_V1_PATH
import no.nav.syfo.narmestelederrelasjon.application.ActiveSykmeldingLookup
import no.nav.syfo.narmestelederrelasjon.application.GetNarmestelederrelasjon
import no.nav.syfo.narmestelederrelasjon.application.NarmestelederrelasjonLookup
import no.nav.syfo.narmestelederrelasjon.application.NarmestelederrelasjonOrganization
import no.nav.syfo.narmestelederrelasjon.application.NarmestelederrelasjonRepository
import no.nav.syfo.narmestelederrelasjon.application.PublishNarmestelederrelasjonRevocationCommand
import no.nav.syfo.narmestelederrelasjon.application.RevocableNarmestelederrelasjon
import no.nav.syfo.narmestelederrelasjon.application.RevocationInitiator
import no.nav.syfo.narmestelederrelasjon.application.RevokeNarmestelederrelasjon
import no.nav.syfo.organisasjonstilgang.application.DenialReason
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccess
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessResult
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
        val organizationAccess = mockk<OrganizationAccess>()
        val organization = mockk<NarmestelederrelasjonOrganization>()
        val activeSykmeldingLookup = ActiveSykmeldingLookup { _, _ -> true }
        val published = mutableListOf<PublishNarmestelederrelasjonRevocationCommand>()
        val revokeNarmestelederrelasjon = RevokeNarmestelederrelasjon(
            repository,
            organizationAccess,
        ) { command -> published.add(command) }
        val getNarmestelederrelasjon = GetNarmestelederrelasjon(
            repository,
            organizationAccess,
            activeSykmeldingLookup,
            organization,
        )
        val id = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val employeeIdent = "12345678901"

        fun lookup() = NarmestelederrelasjonLookup(
            id = id,
            organizationNumber = OrganizationNumber("123456789"),
            employeeIdent = PersonIdent(employeeIdent),
            employeeFirstName = "Employee",
            employeeMiddleName = null,
            employeeLastName = "Person",
            isActive = true,
        )

        fun revocableLookup(isActive: Boolean = true) = RevocableNarmestelederrelasjon(
            id = id,
            employeeIdent = PersonIdent(employeeIdent),
            managerIdent = PersonIdent("10987654321"),
            organizationNumber = OrganizationNumber("123456789"),
            isActive = isActive,
        )

        fun withTestApplication(test: suspend ApplicationTestBuilder.() -> Unit) {
            testApplication {
                application {
                    installContentNegotiation()
                    installStatusPages()
                    routing {
                        route(INTERNAL_API_V1_PATH) {
                            install(AddTokenIssuerPlugin)
                            registerNarmestelederrelasjonApi(getNarmestelederrelasjon, revokeNarmestelederrelasjon, texasHttpClient)
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
            published.clear()
            clearMocks(texasHttpClient, repository, organizationAccess, organization, answers = false)
            coEvery { texasHttpClient.introspectToken("tokenx", any()) } returns
                TexasIntrospectionResponse(active = true, acr = "Level4", pid = employeeIdent)
            coEvery { repository.findById(id) } returns lookup()
            coEvery { repository.findRevocableById(id) } returns revocableLookup()
            coEvery { organizationAccess.evaluate(any(), OrganizationNumber("123456789")) } returns OrganizationAccessResult.Granted
            coEvery { organization.findName(OrganizationNumber("123456789")) } returns "Organization"
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
                coVerify(exactly = 1) {
                    organizationAccess.evaluate(any(), OrganizationNumber("123456789"))
                }
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
                coVerify(exactly = 1) {
                    organizationAccess.evaluate(any(), OrganizationNumber("123456789"))
                }
            }
        }

        it("returns indistinguishable data-free masked 404 errors") {
            introspectMaskinporten()
            coEvery { repository.findById(id) } returnsMany listOf(null, lookup(), lookup())
            coEvery { organizationAccess.evaluate(any(), OrganizationNumber("123456789")) } returns
                OrganizationAccessResult.Denied(DenialReason.MISSING_ORGANIZATION_ACCESS)

            withTestApplication {
                val malformedId = "not-a-uuid"
                val tokenXToken = token()
                val maskinportenToken = token(MASKINPORTEN_ISSUER)
                val responses = listOf(
                    client.get(path(malformedId)) { bearerAuth(tokenXToken) },
                    client.get(path(id.toString())) { bearerAuth(tokenXToken) },
                    client.get(path(id.toString())) { bearerAuth(tokenXToken) },
                    client.get(path(id.toString())) { bearerAuth(maskinportenToken) },
                )

                val bodies = responses.map { response ->
                    response.status shouldBe HttpStatusCode.NotFound
                    response.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                    response.bodyAsText()
                }

                bodies.forEach { body ->
                    body shouldContain """"type":"NOT_FOUND""""
                    body shouldContain """"message":"Linemanager relation was not found""""
                    body shouldContain """"path":null"""
                    body shouldNotContain id.toString()
                    body shouldNotContain malformedId
                    body shouldNotContain employeeIdent
                    body shouldNotContain "123456789"
                    body shouldNotContain tokenXToken
                    body shouldNotContain maskinportenToken
                    body shouldNotContain NARMESTELEDERRELASJON_API_PATH.substringBefore("/{id}")
                }

                bodies.map(::replaceTimestamp).distinct() shouldBe listOf(replaceTimestamp(bodies.first()))
            }
        }

        it("returns a generic no-store 500 when the relation projection is unavailable") {
            coEvery { organization.findName(OrganizationNumber("123456789")) } returns null

            withTestApplication {
                val response = client.get(path(id.toString())) { bearerAuth(token()) }

                response.status shouldBe HttpStatusCode.InternalServerError
                response.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                response.bodyAsText() shouldContain """"message":"Internal Server Error""""
            }
        }

        it("returns a successful relation with null name and no-store when the employee name is incomplete") {
            coEvery { repository.findById(id) } returns lookup().copy(employeeFirstName = " ")

            withTestApplication {
                val response = client.get(path(id.toString())) { bearerAuth(token()) }

                response.status shouldBe HttpStatusCode.OK
                response.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                response.bodyAsText() shouldBe """
                    {"linemanagerRelation":{"id":"00000000-0000-0000-0000-000000000001","employee":{"name":null,"nationalIdentificationNumber":"12345678901"},"organization":{"orgNumber":"123456789","name":"Organization"}}}
                """.trimIndent()
            }
        }

        it("returns 401 when authentication is missing") {
            withTestApplication {
                client.get(path(id.toString())).status shouldBe HttpStatusCode.Unauthorized
            }
        }

        it("DELETE accepts a TokenX employee and publishes the revocation") {
            withTestApplication {
                val response = client.delete(path(id.toString())) { bearerAuth(token()) }
                response.status shouldBe HttpStatusCode.Accepted
                published.single().employeeIdent shouldBe PersonIdent(employeeIdent)
                published.single().initiator shouldBe RevocationInitiator.EMPLOYEE
            }
            coVerify(exactly = 0) { organizationAccess.evaluate(any(), OrganizationNumber("123456789")) }
        }

        it("DELETE accepts the relation's line manager without organization access") {
            coEvery { texasHttpClient.introspectToken("tokenx", any()) } returns
                TexasIntrospectionResponse(active = true, acr = "Level4", pid = "10987654321")
            withTestApplication {
                client.delete(path(id.toString())) { bearerAuth(token()) }.status shouldBe HttpStatusCode.Accepted
            }
            published.single().initiator shouldBe RevocationInitiator.LINEMANAGER
            coVerify(exactly = 0) { organizationAccess.evaluate(any(), OrganizationNumber("123456789")) }
        }

        it("DELETE accepts an already revoked relation without publishing") {
            coEvery { repository.findRevocableById(id) } returns revocableLookup(isActive = false)
            withTestApplication {
                client.delete(path(id.toString())) { bearerAuth(token()) }.status shouldBe HttpStatusCode.Accepted
            }
            published.size shouldBe 0
        }

        it("DELETE returns the same 404 body for an unknown relation and denied access") {
            coEvery { repository.findRevocableById(id) } returnsMany listOf(null, revocableLookup())
            coEvery { organizationAccess.evaluate(any(), OrganizationNumber("123456789")) } returns
                OrganizationAccessResult.Denied(DenialReason.MISSING_RESOURCE_ACCESS)
            coEvery { texasHttpClient.introspectToken("tokenx", any()) } returns
                TexasIntrospectionResponse(active = true, acr = "Level4", pid = "11111111111")
            withTestApplication {
                val first = client.delete(path(id.toString())) { bearerAuth(token()) }
                val second = client.delete(path(id.toString())) { bearerAuth(token()) }
                listOf(first, second).forEach {
                    it.status shouldBe HttpStatusCode.NotFound
                    it.bodyAsText() shouldContain """"message":"Linemanager relation not found""""
                    it.bodyAsText() shouldContain """"type":"NOT_FOUND""""
                    it.bodyAsText() shouldContain """"path":"/internal/api/v1/linemanager/"""
                }
                replaceTimestamp(first.bodyAsText()) shouldBe replaceTimestamp(second.bodyAsText())
            }
            published.size shouldBe 0
        }

        it("DELETE returns 400 for an invalid UUID and 401 without authentication") {
            withTestApplication {
                val invalid = client.delete(path("not-a-uuid")) { bearerAuth(token()) }
                invalid.status shouldBe HttpStatusCode.BadRequest
                invalid.bodyAsText() shouldContain """"message":"Invalid UUID format for id parameter""""
                client.delete(path(id.toString())).status shouldBe HttpStatusCode.Unauthorized
            }
            published.size shouldBe 0
        }

        it("DELETE accepts an authorized Maskinporten system user") {
            introspectMaskinporten()
            withTestApplication {
                client.delete(path(id.toString())) { bearerAuth(token(MASKINPORTEN_ISSUER)) }.status shouldBe HttpStatusCode.Accepted
            }
            published.single().initiator shouldBe RevocationInitiator.LPS
        }

        it("DELETE masks a denied Maskinporten system user with the legacy 404") {
            introspectMaskinporten()
            coEvery { organizationAccess.evaluate(any(), OrganizationNumber("123456789")) } returns
                OrganizationAccessResult.Denied(DenialReason.SYSTEM_USER_REJECTED)
            withTestApplication {
                val response = client.delete(path(id.toString())) { bearerAuth(token(MASKINPORTEN_ISSUER)) }
                response.status shouldBe HttpStatusCode.NotFound
                response.bodyAsText() shouldContain """"message":"Linemanager relation not found""""
            }
            published.size shouldBe 0
        }

        it("DELETE treats the sibling search path as an invalid UUID") {
            withTestApplication {
                client.delete(path("search")) { bearerAuth(token()) }.status shouldBe HttpStatusCode.BadRequest
            }
            published.size shouldBe 0
        }
    })

private fun replaceTimestamp(body: String): String = body.replace(Regex("""("timestamp":")[^"]+(")"""), "$1<dynamic>$2")
