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
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.narmestelederrelasjon.application.get.OrganizationNameResult
import no.nav.syfo.narmestelederrelasjon.application.inOrder
import no.nav.syfo.narmestelederrelasjon.observability.GET_NARMESTELEDERRELASJON_TOTAL
import no.nav.syfo.organisasjonstilgang.application.DenialReason
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessResult
import no.nav.syfo.platform.observability.METRICS_REGISTRY
import no.nav.syfo.platform.observability.assertSingleUpstreamFailureLog
import no.nav.syfo.platform.upstream.UpstreamError
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamName

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
                    organizationAccess.calls.map { it.second } shouldBe listOf(OrganizationNumber("123456789"))
                }
            }

            test("returns indistinguishable data-free masked 404 errors") {
                val before = getOutcomeCount("not_found")
                repository.lookupById = inOrder(null, lookup())
                organizationAccess.result = OrganizationAccessResult.Denied(DenialReason.MISSING_ORGANIZATION_ACCESS)

                withTestApplication {
                    val malformedId = "not-a-uuid"
                    val tokenXToken = token()
                    val responses = listOf(
                        client.get(path(malformedId)) { bearerAuth(tokenXToken) },
                        client.get(path(id.toString())) { bearerAuth(tokenXToken) },
                        client.get(path(id.toString())) { bearerAuth(tokenXToken) },
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
                        body shouldNotContain NARMESTELEDERRELASJON_API_PATH.substringBefore("/{id}")
                    }

                    bodies.map(::replaceTimestamp).distinct() shouldBe listOf(replaceTimestamp(bodies.first()))
                }
                getOutcomeCount("not_found") shouldBe before + 3
            }

            test("maps missing organization and incomplete employee names to explicit nulls and counts it as found") {
                val before = getOutcomeCount("found")
                repository.lookupById = { lookup().copy(employeeFirstName = " ") }
                organization.result = OrganizationNameResult.Missing

                withTestApplication {
                    val response = client.get(path(id.toString())) { bearerAuth(token()) }

                    response.status shouldBe HttpStatusCode.OK
                    response.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                    response.bodyAsText() shouldBe """
                        {"linemanagerRelation":{"id":"00000000-0000-0000-0000-000000000001","employee":{"name":null,"nationalIdentificationNumber":"12345678901"},"organization":{"orgNumber":"123456789","name":null}}}
                    """.trimIndent()
                }
                getOutcomeCount("found") shouldBe before + 1
                METRICS_REGISTRY.find(GET_NARMESTELEDERRELASJON_TOTAL).counters()
                    .map { it.id.getTag("outcome") }.toSet() shouldBe setOf("found", "not_found", "upstream_unavailable")
            }

            test("returns a no-store upstream unavailable 500 and logs the failure exactly once at the edge") {
                val before = getOutcomeCount("upstream_unavailable")
                organization.result = OrganizationNameResult.Unavailable(
                    UpstreamFailure(UpstreamName("ereg"), UpstreamFailureStage.RESPONSE, 503, errors = listOf(UpstreamError("server_error"))),
                )

                assertSingleUpstreamFailureLog(upstream = "ereg") {
                    withTestApplication {
                        val response = client.get(path(id.toString())) { bearerAuth(token()) }

                        response.status shouldBe HttpStatusCode.InternalServerError
                        response.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                        val body = response.bodyAsText()
                        body shouldContain """"type":"UPSTREAM_SERVICE_UNAVAILABLE""""
                        body shouldContain """"message":"An upstream service is unavailable""""
                        body shouldNotContain "123456789"
                    }
                }
                getOutcomeCount("upstream_unavailable") shouldBe before + 1
            }

            test("returns 401 when authentication is missing") {
                withTestApplication {
                    client.get(path(id.toString())).status shouldBe HttpStatusCode.Unauthorized
                }
            }
        }
    })

private fun getOutcomeCount(outcome: String): Double = METRICS_REGISTRY.find(GET_NARMESTELEDERRELASJON_TOTAL)
    .tag("outcome", outcome).counter()?.count() ?: 0.0
