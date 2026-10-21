package no.nav.syfo.narmestelederrelasjon.api

import ch.qos.logback.classic.Level
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
import no.nav.syfo.platform.api.STATUS_PAGES_LOGGER_NAME
import no.nav.syfo.platform.auth.texas.stubMaskinportenSystemUser
import no.nav.syfo.platform.observability.METRICS_REGISTRY
import no.nav.syfo.platform.observability.ProductionLogCapture
import no.nav.syfo.platform.observability.ProductionLogs
import no.nav.syfo.platform.upstream.UpstreamError
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamName

class GetNarmestelederrelasjonRouteTest :
    FunSpec({
        val productionLogs = extension(ProductionLogs(listOf(STATUS_PAGES_LOGGER_NAME)))
        lateinit var failureLogs: ProductionLogCapture
        afterTest { failureLogs.close() }

        with(NarmestelederrelasjonRouteFixture()) {
            beforeTest {
                reset()
                failureLogs = productionLogs.capture()
            }

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

            test("returns the relation with no-store for a Maskinporten system user with organization access") {
                texasHttpClient.stubMaskinportenSystemUser()

                withTestApplication {
                    val response = client.get(path(id.toString())) {
                        bearerAuth(token(MASKINPORTEN_ISSUER))
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
                texasHttpClient.stubMaskinportenSystemUser()
                repository.lookupById = inOrder(null, lookup(), lookup())
                organizationAccess.result = OrganizationAccessResult.Denied(DenialReason.MISSING_ORGANIZATION_ACCESS)

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
                getOutcomeCount("not_found") shouldBe before + 4
            }

            test("returns 200 with an explicit null organization name and counts it as found") {
                val before = getOutcomeCount("found")
                organization.result = OrganizationNameResult.Missing

                withTestApplication {
                    val response = client.get(path(id.toString())) { bearerAuth(token()) }

                    response.status shouldBe HttpStatusCode.OK
                    response.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                    response.bodyAsText() shouldBe """
                        {"linemanagerRelation":{"id":"00000000-0000-0000-0000-000000000001","employee":{"name":{"firstName":"Employee","middleName":null,"lastName":"Person"},"nationalIdentificationNumber":"12345678901"},"organization":{"orgNumber":"123456789","name":null}}}
                    """.trimIndent()
                }
                getOutcomeCount("found") shouldBe before + 1
                failureLogs.logs.size shouldBe 0
                METRICS_REGISTRY.find(GET_NARMESTELEDERRELASJON_TOTAL).counters()
                    .map { it.id.getTag("outcome") }.toSet() shouldBe setOf("found", "not_found", "upstream_unavailable")
            }

            listOf(
                UpstreamFailure(UpstreamName("altinn-pdp"), UpstreamFailureStage.RESPONSE, 503, IllegalStateException("private-provider-message")),
                UpstreamFailure(UpstreamName("altinn-pdp"), UpstreamFailureStage.RESPONSE, 200, errors = listOf(UpstreamError("indeterminate", detail = "unknown"))),
                UpstreamFailure(UpstreamName("texas"), UpstreamFailureStage.TOKEN_EXCHANGE, 400, errors = listOf(UpstreamError("invalid_grant"))),
                UpstreamFailure(UpstreamName("altinn-authentication"), UpstreamFailureStage.TOKEN_EXCHANGE, 200, IllegalArgumentException("private-provider-message")),
                UpstreamFailure(UpstreamName("ereg"), UpstreamFailureStage.RESPONSE, 503, IllegalStateException("private-provider-message")),
            ).forEach { failure ->
                test("system user ${failure.upstream} ${failure.status} failure returns 500 and increments upstream unavailable") {
                    val before = getOutcomeCount("upstream_unavailable")
                    texasHttpClient.stubMaskinportenSystemUser()
                    organizationAccess.result = OrganizationAccessResult.Unavailable(failure)
                    withTestApplication {
                        val accessToken = token(MASKINPORTEN_ISSUER)
                        val response = client.get(path(id.toString())) { bearerAuth(accessToken) }
                        response.status shouldBe HttpStatusCode.InternalServerError
                        response.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                        val body = response.bodyAsText()
                        body shouldContain """"type":"UPSTREAM_SERVICE_UNAVAILABLE""""
                        listOf("private-provider-message", "123456789", accessToken).forEach { body shouldNotContain it }
                        organization.calls shouldBe emptyList()
                    }
                    getOutcomeCount("upstream_unavailable") shouldBe before + 1
                    val record = failureLogs.logs.single()
                    val fields = record.fields
                    fields["event_type"] shouldBe "api_request_failed"
                    fields["upstream"] shouldBe failure.upstream.value
                    fields["failure_stage"] shouldBe failure.stage.logValue
                    fields["upstream_status"] shouldBe failure.status
                    record.serialized shouldNotContain "private-provider-message"
                }
            }

            test("returns a no-store upstream unavailable 500 and logs the Ereg failure exactly once at the edge") {
                val before = getOutcomeCount("upstream_unavailable")
                val failure = UpstreamFailure(
                    UpstreamName("ereg"),
                    UpstreamFailureStage.RESPONSE,
                    503,
                    IllegalStateException("private upstream body 123456789"),
                )
                organization.result = OrganizationNameResult.Unavailable(failure)

                withTestApplication {
                    val response = client.get(path(id.toString())) { bearerAuth(token()) }

                    response.status shouldBe HttpStatusCode.InternalServerError
                    response.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                    val body = response.bodyAsText()
                    body shouldContain """"type":"UPSTREAM_SERVICE_UNAVAILABLE""""
                    body shouldContain """"message":"An upstream service is unavailable""""
                    body shouldNotContain "123456789"
                    body shouldNotContain "private upstream body"
                }
                getOutcomeCount("upstream_unavailable") shouldBe before + 1
                val record = failureLogs.logs.single()
                record.level shouldBe Level.ERROR
                val fields = record.fields
                fields["event_type"] shouldBe "api_request_failed"
                fields["response_status"] shouldBe 500
                fields["upstream"] shouldBe "ereg"
                fields["failure_stage"] shouldBe "response"
                fields["upstream_status"] shouldBe 503
                record.serialized shouldNotContain "123456789"
                record.serialized shouldNotContain "UpstreamRequestException"
            }

            test("returns a successful relation with null name and no-store when the employee name is incomplete") {
                repository.lookupById = { lookup().copy(employeeFirstName = " ") }

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

private fun getOutcomeCount(outcome: String): Double = METRICS_REGISTRY.find(GET_NARMESTELEDERRELASJON_TOTAL)
    .tag("outcome", outcome).counter()?.count() ?: 0.0
