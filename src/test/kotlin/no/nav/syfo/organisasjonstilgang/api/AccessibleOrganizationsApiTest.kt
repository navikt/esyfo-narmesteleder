package no.nav.syfo.organisasjonstilgang.api

import DefaultOrganization
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import createMockToken
import defaultMocks
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.clearAllMocks
import io.mockk.mockk
import no.nav.syfo.application.api.API_V1_PATH
import no.nav.syfo.application.api.installContentNegotiation
import no.nav.syfo.application.api.installStatusPages
import no.nav.syfo.application.auth.AddTokenIssuerPlugin
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.organisasjonstilgang.application.AccessToken
import no.nav.syfo.organisasjonstilgang.application.AccessibleOrganization
import no.nav.syfo.organisasjonstilgang.application.AccessibleOrganizationsLookup
import no.nav.syfo.organisasjonstilgang.application.ListAccessibleOrganizationsResult
import no.nav.syfo.organisasjonstilgang.application.ListAccessibleOrganizationsUseCase
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessSubject
import no.nav.syfo.texas.MASKINPORTEN_NL_SCOPE
import no.nav.syfo.texas.client.TexasHttpClient

class AccessibleOrganizationsApiTest :
    FunSpec({
        val texasHttpClient = mockk<TexasHttpClient>()
        val userIdent = "12345678901"
        val token = createMockToken(ident = userIdent, issuer = "https://tokenx.nav.no")
        val mapper = jacksonObjectMapper()

        beforeTest {
            clearAllMocks(currentThreadOnly = true)
        }

        test("returns the unchanged recursive JSON shape for listed organizations") {
            val lookup = FakeAccessibleOrganizationsLookup(
                ListAccessibleOrganizationsResult.Listed(
                    listOf(
                        AccessibleOrganization(
                            organizationNumber = "100000000",
                            name = "Hovedenhet Uten Tilgang",
                            subOrganizations = listOf(
                                AccessibleOrganization(
                                    organizationNumber = "200000001",
                                    name = "Underenhet Med Tilgang",
                                    subOrganizations = emptyList(),
                                ),
                            ),
                        ),
                    ),
                ),
            )
            texasHttpClient.defaultMocks(pid = userIdent, acr = "Level4")

            withAccessibleOrganizationsApp(lookup, texasHttpClient) {
                val response = client.get("$API_V1_PATH$ACCESSIBLE_ORGANIZATIONS_API_PATH") {
                    bearerAuth(token)
                }

                response.status shouldBe HttpStatusCode.OK
                mapper.readTree(response.bodyAsText()) shouldBe mapper.readTree(
                    """{
                        "organizations": [{
                            "orgNumber": "100000000",
                            "name": "Hovedenhet Uten Tilgang",
                            "subOrganizations": [{
                                "orgNumber": "200000001",
                                "name": "Underenhet Med Tilgang",
                                "subOrganizations": []
                            }]
                        }]
                    }""",
                )
                lookup.subject shouldBe OrganizationAccessSubject.PersonnelManager(PersonIdent(userIdent), AccessToken(token))
            }
        }

        test("returns an empty organizations list when no organizations are listed") {
            val lookup = FakeAccessibleOrganizationsLookup(ListAccessibleOrganizationsResult.Listed(emptyList()))
            texasHttpClient.defaultMocks(pid = userIdent, acr = "Level4")

            withAccessibleOrganizationsApp(lookup, texasHttpClient) {
                val response = client.get("$API_V1_PATH$ACCESSIBLE_ORGANIZATIONS_API_PATH") {
                    bearerAuth(token)
                }

                response.status shouldBe HttpStatusCode.OK
                mapper.readTree(response.bodyAsText()) shouldBe mapper.readTree("""{"organizations":[]}""")
            }
        }

        test("returns the unchanged 500 error when the lookup is unavailable") {
            val lookup = FakeAccessibleOrganizationsLookup(ListAccessibleOrganizationsResult.Unavailable)
            texasHttpClient.defaultMocks(pid = userIdent, acr = "Level4")

            withAccessibleOrganizationsApp(lookup, texasHttpClient) {
                val response = client.get("$API_V1_PATH$ACCESSIBLE_ORGANIZATIONS_API_PATH") {
                    bearerAuth(token)
                }

                response.status shouldBe HttpStatusCode.InternalServerError
                val body = mapper.readTree(response.bodyAsText())
                body["message"].asText() shouldBe "An error occurred when fetching altinn tilganger"
                body["type"].asText() shouldBe "INTERNAL_SERVER_ERROR"
                body["path"].asText() shouldBe "$API_V1_PATH$ACCESSIBLE_ORGANIZATIONS_API_PATH"
            }
        }

        test("returns the unchanged 403 error for a system principal without invoking the lookup") {
            val lookup = FakeAccessibleOrganizationsLookup(ListAccessibleOrganizationsResult.Listed(emptyList()))
            texasHttpClient.defaultMocks(pid = null, consumer = DefaultOrganization, scope = MASKINPORTEN_NL_SCOPE)

            withAccessibleOrganizationsApp(lookup, texasHttpClient) {
                val response = client.get("$API_V1_PATH$ACCESSIBLE_ORGANIZATIONS_API_PATH") {
                    bearerAuth(createMockToken(ident = "0192:123456789"))
                }

                response.status shouldBe HttpStatusCode.Forbidden
                val body = mapper.readTree(response.bodyAsText())
                body["type"].asText() shouldBe "AUTHORIZATION_ERROR"
                body["message"].asText() shouldBe "Only user principals can access accessible organizations endpoint"
                body["path"].asText() shouldBe "$API_V1_PATH$ACCESSIBLE_ORGANIZATIONS_API_PATH"
                lookup.subject shouldBe null
            }
        }

        test("returns 401 for an unauthenticated request without invoking the lookup") {
            val lookup = FakeAccessibleOrganizationsLookup(ListAccessibleOrganizationsResult.Listed(emptyList()))

            withAccessibleOrganizationsApp(lookup, texasHttpClient) {
                client.get("$API_V1_PATH$ACCESSIBLE_ORGANIZATIONS_API_PATH").status shouldBe HttpStatusCode.Unauthorized
                lookup.subject shouldBe null
            }
        }
    })

private class FakeAccessibleOrganizationsLookup(
    private val result: ListAccessibleOrganizationsResult,
) : AccessibleOrganizationsLookup {
    var subject: OrganizationAccessSubject.PersonnelManager? = null
        private set

    override suspend fun find(subject: OrganizationAccessSubject.PersonnelManager): ListAccessibleOrganizationsResult {
        this.subject = subject
        return result
    }
}

private fun withAccessibleOrganizationsApp(
    lookup: AccessibleOrganizationsLookup,
    texasHttpClient: TexasHttpClient,
    assertions: suspend ApplicationTestBuilder.() -> Unit,
) {
    testApplication {
        application {
            installContentNegotiation()
            installStatusPages()
            routing {
                route(API_V1_PATH) {
                    install(AddTokenIssuerPlugin)
                    registerAccessibleOrganizationsApi(ListAccessibleOrganizationsUseCase(lookup), texasHttpClient)
                }
            }
        }
        assertions()
    }
}
