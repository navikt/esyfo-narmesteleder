package no.nav.syfo.narmestelederrelasjon.api

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.narmestelederrelasjon.application.inOrder
import no.nav.syfo.narmestelederrelasjon.application.revoke.RevocationInitiator
import no.nav.syfo.narmestelederrelasjon.observability.LINEMANAGER_REVOKE_BY_ID_TOTAL
import no.nav.syfo.organisasjonstilgang.application.DenialReason
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessResult
import no.nav.syfo.platform.auth.texas.stubTokenX
import no.nav.syfo.platform.observability.METRICS_REGISTRY
import no.nav.syfo.platform.observability.assertSingleUpstreamFailureLog
import no.nav.syfo.platform.upstream.UpstreamError
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamName

class RevokeNarmestelederrelasjonRouteTest :
    FunSpec({
        with(NarmestelederrelasjonRouteFixture()) {
            beforeTest { reset() }

            test("unavailable organization access returns 500 and logs once without publishing or counting") {
                val failure = UpstreamFailure(UpstreamName("arbeidsgiver-altinn-tilganger"), UpstreamFailureStage.RESPONSE, 503, errors = listOf(UpstreamError("server_error")))
                organizationAccess.result = OrganizationAccessResult.Unavailable(failure)
                texasHttpClient.stubTokenX(pid = "11111111111")
                val before = METRICS_REGISTRY.find(LINEMANAGER_REVOKE_BY_ID_TOTAL).counters().sumOf { it.count() }
                assertSingleUpstreamFailureLog(upstream = failure.upstream.value) {
                    withTestApplication {
                        val response = client.delete(path(id.toString())) { bearerAuth(token()) }
                        response.status shouldBe HttpStatusCode.InternalServerError
                        response.bodyAsText() shouldContain """"type":"UPSTREAM_SERVICE_UNAVAILABLE""""
                        response.bodyAsText() shouldContain """"message":"An upstream service is unavailable""""
                    }
                }
                METRICS_REGISTRY.find(LINEMANAGER_REVOKE_BY_ID_TOTAL).counters().sumOf { it.count() } shouldBe before
                published shouldBe emptyList()
            }

            test("accepts a TokenX employee and publishes the revocation") {
                withTestApplication {
                    val response = client.delete(path(id.toString())) { bearerAuth(token()) }
                    response.status shouldBe HttpStatusCode.Accepted
                    published.single().employeeIdent shouldBe PersonalIdentificationNumber(employeeIdent)
                    published.single().initiator shouldBe RevocationInitiator.EMPLOYEE
                }
                organizationAccess.calls shouldBe emptyList()
            }

            test("accepts an already revoked relation without publishing") {
                repository.revocableById = { revocableLookup(isActive = false) }
                withTestApplication {
                    client.delete(path(id.toString())) { bearerAuth(token()) }.status shouldBe HttpStatusCode.Accepted
                }
                published.size shouldBe 0
            }

            test("returns the same 404 body for an unknown relation and denied access") {
                repository.revocableById = inOrder(null, revocableLookup())
                organizationAccess.result = OrganizationAccessResult.Denied(DenialReason.MISSING_RESOURCE_ACCESS)
                texasHttpClient.stubTokenX(pid = "11111111111")
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
        }
    })
