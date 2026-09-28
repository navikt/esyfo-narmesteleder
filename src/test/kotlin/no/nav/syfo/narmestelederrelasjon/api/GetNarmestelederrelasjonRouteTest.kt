package no.nav.syfo.narmestelederrelasjon.api

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.mockk.coEvery
import io.mockk.coVerify
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.organisasjonstilgang.application.DenialReason
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessResult

class GetNarmestelederrelasjonRouteTest :
    FunSpec({
        with(NarmestelederrelasjonRouteFixture()) {
            beforeTest { reset() }

            test("returns the exact PII response body with no-store") {
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

            test("returns the relation with no-store for a Maskinporten system user with organization access") {
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

            test("returns indistinguishable data-free masked 404 errors") {
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

            test("returns a generic no-store 500 when the relation projection is unavailable") {
                coEvery { organization.findName(OrganizationNumber("123456789")) } returns null

                withTestApplication {
                    val response = client.get(path(id.toString())) { bearerAuth(token()) }

                    response.status shouldBe HttpStatusCode.InternalServerError
                    response.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                    response.bodyAsText() shouldContain """"message":"Internal Server Error""""
                }
            }

            test("returns a successful relation with null name and no-store when the employee name is incomplete") {
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

            test("returns 401 when authentication is missing") {
                withTestApplication {
                    client.get(path(id.toString())).status shouldBe HttpStatusCode.Unauthorized
                }
            }
        }
    })
