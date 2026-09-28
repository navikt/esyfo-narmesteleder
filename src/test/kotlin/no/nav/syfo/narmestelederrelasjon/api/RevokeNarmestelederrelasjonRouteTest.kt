package no.nav.syfo.narmestelederrelasjon.api

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.mockk.coEvery
import io.mockk.coVerify
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmestelederrelasjon.application.RevocationInitiator
import no.nav.syfo.organisasjonstilgang.application.DenialReason
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessResult
import no.nav.syfo.texas.client.TexasIntrospectionResponse

class RevokeNarmestelederrelasjonRouteTest :
    FunSpec({
        with(NarmestelederrelasjonRouteFixture()) {
            beforeTest { reset() }

            test("accepts a TokenX employee and publishes the revocation") {
                withTestApplication {
                    val response = client.delete(path(id.toString())) { bearerAuth(token()) }
                    response.status shouldBe HttpStatusCode.Accepted
                    published.single().employeeIdent shouldBe PersonIdent(employeeIdent)
                    published.single().initiator shouldBe RevocationInitiator.EMPLOYEE
                }
                coVerify(exactly = 0) { organizationAccess.evaluate(any(), OrganizationNumber("123456789")) }
            }

            test("accepts the relation's line manager without organization access") {
                coEvery { texasHttpClient.introspectToken("tokenx", any()) } returns
                    TexasIntrospectionResponse(active = true, acr = "Level4", pid = "10987654321")
                withTestApplication {
                    client.delete(path(id.toString())) { bearerAuth(token()) }.status shouldBe HttpStatusCode.Accepted
                }
                published.single().initiator shouldBe RevocationInitiator.LINEMANAGER
                coVerify(exactly = 0) { organizationAccess.evaluate(any(), OrganizationNumber("123456789")) }
            }

            test("accepts an already revoked relation without publishing") {
                coEvery { repository.findRevocableById(id) } returns revocableLookup(isActive = false)
                withTestApplication {
                    client.delete(path(id.toString())) { bearerAuth(token()) }.status shouldBe HttpStatusCode.Accepted
                }
                published.size shouldBe 0
            }

            test("returns the same 404 body for an unknown relation and denied access") {
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

            test("returns 400 for an invalid UUID and 401 without authentication") {
                withTestApplication {
                    val invalid = client.delete(path("not-a-uuid")) { bearerAuth(token()) }
                    invalid.status shouldBe HttpStatusCode.BadRequest
                    invalid.bodyAsText() shouldContain """"message":"Invalid UUID format for id parameter""""
                    client.delete(path(id.toString())).status shouldBe HttpStatusCode.Unauthorized
                }
                published.size shouldBe 0
            }

            test("accepts an authorized Maskinporten system user") {
                introspectMaskinporten()
                withTestApplication {
                    client.delete(path(id.toString())) { bearerAuth(token(MASKINPORTEN_ISSUER)) }.status shouldBe HttpStatusCode.Accepted
                }
                published.single().initiator shouldBe RevocationInitiator.LPS
            }

            test("masks a denied Maskinporten system user with the legacy 404") {
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

            test("treats the sibling search path as an invalid UUID") {
                withTestApplication {
                    client.delete(path("search")) { bearerAuth(token()) }.status shouldBe HttpStatusCode.BadRequest
                }
                published.size shouldBe 0
            }
        }
    })
