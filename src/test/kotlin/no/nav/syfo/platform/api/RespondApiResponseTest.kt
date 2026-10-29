package no.nav.syfo.platform.api

import arrow.core.left
import arrow.core.right
import ch.qos.logback.classic.Level
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import no.nav.syfo.platform.observability.ProductionLogCapture
import no.nav.syfo.platform.observability.ProductionLogs
import no.nav.syfo.platform.upstream.UpstreamError
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamName
import java.time.Instant

class RespondApiResponseTest :
    FunSpec({
        val productionLogs = extension(ProductionLogs(listOf(STATUS_PAGES_LOGGER_NAME)))
        lateinit var capture: ProductionLogCapture
        beforeTest { capture = productionLogs.capture() }
        afterTest { capture.close() }

        test("maps Left using only the rejection mapper") {
            val rejection = ApiRejection(ApiError(HttpStatusCode.BadRequest, ErrorType.BAD_REQUEST, "Safe error"))
            "rejected".left().toApiResponse(
                HttpStatusCode.Accepted,
                body = { error("Left must not map a success body") },
                toRejection = {
                    it shouldBe "rejected"
                    rejection
                },
            ) shouldBe ApiResponse.Rejected(rejection)
            capture.logs.shouldBeEmpty()
        }

        test("maps Right using only the body mapper") {
            42.right().toApiResponse(
                HttpStatusCode.Created,
                body = { mapOf("value" to it) },
                toRejection = { error("Right must not map a rejection") },
            ) shouldBe ApiResponse.Success(HttpStatusCode.Created, mapOf("value" to 42))
            capture.logs.shouldBeEmpty()
        }

        test("defaults a Right response to no body") {
            42.right().toApiResponse(HttpStatusCode.Accepted) { error("Right must not map a rejection") } shouldBe
                ApiResponse.Success(HttpStatusCode.Accepted)
            capture.logs.shouldBeEmpty()
        }

        listOf(
            UpstreamFailure(UpstreamName("aareg"), UpstreamFailureStage.RESPONSE, 503, IllegalStateException("private-exception-canary")),
            UpstreamFailure(UpstreamName("pdl"), UpstreamFailureStage.RESPONSE, 200, errors = listOf(UpstreamError("server_error"))),
        ).forEach { failure ->
            test("logs Rejected once with the same event and upstream fields as StatusPages for ${failure.upstream}") {
                testApplication {
                    application {
                        installContentNegotiation()
                        installStatusPages()
                        routing {
                            get("/status-pages") { throw failure.toUpstreamUnavailableException() }
                            get("/helper") {
                                val response: ApiResponse = ApiResponse.Rejected(
                                    ApiRejection(
                                        ApiError(
                                            HttpStatusCode.InternalServerError,
                                            ErrorType.UPSTREAM_SERVICE_UNAVAILABLE,
                                            "An upstream service is unavailable",
                                        ),
                                        upstreamFailure = failure,
                                        cause = failure.cause,
                                    ),
                                )
                                call.respond(response)
                            }
                        }
                    }
                    val original = client.get("/status-pages")
                    val direct = client.get("/helper")
                    direct.status shouldBe original.status
                    val body = jacksonObjectMapper().readTree(direct.bodyAsText())
                    body["status"]["value"].asInt() shouldBe 500
                    body["type"].asText() shouldBe "UPSTREAM_SERVICE_UNAVAILABLE"
                    body["message"].asText() shouldBe "An upstream service is unavailable"
                    body["path"].asText() shouldBe "/helper"
                    direct.bodyAsText() shouldNotContain "private-exception-canary"
                }
                capture.logs.size shouldBe 2
                val original = capture.logs[0]
                val direct = capture.logs[1]
                direct.level shouldBe original.level
                direct.message shouldBe original.message
                direct.loggerName shouldBe original.loggerName
                val diagnosticFields = listOf(
                    "event_type", "operation", "response_status", "error_type", "upstream", "failure_stage",
                    "upstream_status", "upstream_errors", "failure_kind", "sql_state",
                )
                diagnosticFields.forEach { field -> direct.fields[field] shouldBe original.fields[field] }
                direct.level shouldBe Level.ERROR
                direct.fields["event_type"] shouldBe "api_request_failed"
                direct.fields["response_status"] shouldBe 500
                if (failure.cause != null) {
                    direct.fields["exception_type"] shouldBe "IllegalStateException"
                    direct.fields["cause_type"] shouldBe "IllegalStateException"
                    direct.fields["cause_types"] shouldBe listOf("IllegalStateException")
                } else {
                    direct.fields.containsKey("exception_type") shouldBe false
                    direct.fields["cause_types"] shouldBe original.fields["cause_types"]
                    direct.throwableMessage shouldBe null
                }
                capture.records.forEach { it shouldNotContain "private-exception-canary" }
            }
        }

        listOf(HttpStatusCode.BadRequest, HttpStatusCode.NotFound, HttpStatusCode.InternalServerError).forEach { status ->
            listOf(false, true).forEach { alreadyLogged ->
                test("responds with $status and preserves logging when alreadyLogged is $alreadyLogged") {
                    testApplication {
                        application {
                            installContentNegotiation()
                            routing {
                                get("/helper") {
                                    val response: ApiResponse = ApiResponse.Rejected(
                                        ApiRejection(
                                            ApiError(status, ErrorType.BAD_REQUEST, "Safe error"),
                                            alreadyLogged = alreadyLogged,
                                        ),
                                    )
                                    call.respond(response)
                                }
                            }
                        }
                        val response = client.get("/helper")
                        response.status shouldBe status
                        val body = jacksonObjectMapper().readTree(response.bodyAsText())
                        body["type"].asText() shouldBe "BAD_REQUEST"
                        body["message"].asText() shouldBe "Safe error"
                        body["path"].asText() shouldBe "/helper"
                    }
                    if (alreadyLogged) {
                        capture.logs.shouldBeEmpty()
                    } else {
                        val record = capture.logs.single()
                        when (status) {
                            HttpStatusCode.BadRequest -> {
                                record.level shouldBe Level.WARN
                                record.fields["event_type"] shouldBe "api_request_invalid"
                                record.fields["error_type"] shouldBe "BAD_REQUEST"
                                record.fields["response_status"] shouldBe 400
                            }
                            HttpStatusCode.NotFound -> {
                                record.level shouldBe Level.INFO
                                record.message shouldBe "Request rejected with status 404"
                                record.fields.containsKey("event_type") shouldBe false
                            }
                            HttpStatusCode.InternalServerError -> {
                                record.level shouldBe Level.ERROR
                                record.fields["event_type"] shouldBe "api_request_failed"
                                record.fields["response_status"] shouldBe 500
                            }
                        }
                    }
                }
            }
        }

        test("preserves the ApiError timestamp when adding the request path") {
            val timestamp = Instant.parse("2026-01-01T00:00:00Z")
            testApplication {
                application {
                    installContentNegotiation()
                    routing {
                        get("/helper") {
                            call.respond(
                                ApiResponse.Rejected(
                                    ApiRejection(
                                        ApiError(HttpStatusCode.BadRequest, ErrorType.BAD_REQUEST, "Safe error", timestamp = timestamp),
                                        alreadyLogged = true,
                                    ),
                                ),
                            )
                        }
                    }
                }
                val body = jacksonObjectMapper().readTree(client.get("/helper").bodyAsText())
                body["timestamp"].asText() shouldBe timestamp.toString()
                body["path"].asText() shouldBe "/helper"
            }
            capture.logs.shouldBeEmpty()
        }

        test("responds to Success without a body or rejection log") {
            testApplication {
                application {
                    routing {
                        get("/helper") {
                            val response: ApiResponse = ApiResponse.Success(HttpStatusCode.Accepted)
                            call.respond(response)
                        }
                    }
                }
                val response = client.get("/helper")
                response.status shouldBe HttpStatusCode.Accepted
                response.bodyAsText() shouldBe ""
            }
            capture.logs.shouldBeEmpty()
        }

        test("responds to Success with its status and body without a rejection log") {
            testApplication {
                application {
                    installContentNegotiation()
                    routing {
                        get("/helper") {
                            val response: ApiResponse = ApiResponse.Success(HttpStatusCode.Created, mapOf("result" to "created"))
                            call.respond(response)
                        }
                    }
                }
                val response = client.get("/helper")
                response.status shouldBe HttpStatusCode.Created
                jacksonObjectMapper().readTree(response.bodyAsText())["result"].asText() shouldBe "created"
            }
            capture.logs.shouldBeEmpty()
        }
    })
