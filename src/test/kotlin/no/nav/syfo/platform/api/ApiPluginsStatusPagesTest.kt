package no.nav.syfo.platform.api

import ch.qos.logback.classic.Level
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.ServerResponseException
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CancellationException
import no.nav.esyfo.observability.testkit.RuntimeLogContract
import no.nav.syfo.platform.observability.ProductionLogCapture
import no.nav.syfo.platform.observability.ProductionLogs
import no.nav.syfo.platform.upstream.UpstreamError
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamName

class ApiPluginsStatusPagesTest :
    FunSpec({
        val productionLogs = extension(ProductionLogs(listOf(STATUS_PAGES_LOGGER_NAME)))
        lateinit var capture: ProductionLogCapture
        beforeTest { capture = productionLogs.capture() }
        afterTest { capture.close() }

        test("logs an upstream failure once with its metadata and no exception message") {
            val syntheticIdent = "00000000000"
            val upstreamResponse = HttpClient(MockEngine { respond("Ukjent ident $syntheticIdent", HttpStatusCode.ServiceUnavailable) }).use {
                it.get("http://aareg/")
            }
            val failure = UpstreamFailure(
                UpstreamName("aareg"),
                UpstreamFailureStage.RESPONSE,
                503,
                ServerResponseException(upstreamResponse, "Ukjent ident $syntheticIdent"),
            )
            testApplication {
                application {
                    installContentNegotiation()
                    installStatusPages()
                    routing {
                        get("/upstream-unavailable") {
                            throw failure.toUpstreamUnavailableException()
                        }
                    }
                }
                val response = client.get("/upstream-unavailable")
                response.status shouldBe HttpStatusCode.InternalServerError
                val body = response.bodyAsText()
                body shouldContain "UPSTREAM_SERVICE_UNAVAILABLE"
                body shouldContain "An upstream service is unavailable"
                body shouldNotContain syntheticIdent
            }
            RuntimeLogContract(
                mapOf(
                    "event_type" to setOf("api_request_failed"),
                    "exception_type" to setOf($$"ApiErrorException$InternalServerErrorException"),
                )
            ).assertValid(capture.records, expectedCount = 1)
            val serialized = capture.records.single()
            serialized shouldNotContain syntheticIdent
            serialized shouldNotContain "Ukjent ident"
            val record = capture.logs.single().json
            record["level"].asText() shouldBe "ERROR"
            record["event_type"].asText() shouldBe "api_request_failed"
            record["upstream"].asText() shouldBe "aareg"
            record["failure_stage"].asText() shouldBe "response"
            record["upstream_status"].asInt() shouldBe 503
            record["response_status"].asInt() shouldBe 500
            record["cause_type"].asText() shouldBe "ServerResponseException"
            record["failure_kind"].asText() shouldBe "http"
        }

        test("logs a GraphQL failure once with upstream_errors and no stack") {
            val failure = UpstreamFailure(
                UpstreamName("pdl"),
                UpstreamFailureStage.RESPONSE,
                200,
                errors = listOf(UpstreamError("server_error", "technical_id", "hentPerson.navn")),
            )
            testApplication {
                application {
                    installContentNegotiation()
                    installStatusPages()
                    routing { get("/graphql-failure") { throw failure.toUpstreamUnavailableException() } }
                }
                val response = client.get("/graphql-failure")
                response.status shouldBe HttpStatusCode.InternalServerError
                response.bodyAsText() shouldContain "UPSTREAM_SERVICE_UNAVAILABLE"
            }
            RuntimeLogContract(mapOf("event_type" to setOf("api_request_failed")))
                .assertValid(capture.records, expectedCount = 1)
            val record = capture.logs.single().json
            record["failure_kind"].asText() shouldBe "error_response"
            record["upstream"].asText() shouldBe "pdl"
            record["failure_stage"].asText() shouldBe "response"
            record["upstream_status"].asInt() shouldBe 200
            record["upstream_errors"][0]["code"].asText() shouldBe "server_error"
            record["upstream_errors"][0]["detail"].asText() shouldBe "technical_id"
            record["upstream_errors"][0]["path"].asText() shouldBe "hentPerson.navn"
            record.has("stack_trace") shouldBe false
            record.has("exception_type") shouldBe false
        }

        test("includes paths in default NotFoundException errors and omits them when requested") {
            testApplication {
                application {
                    installContentNegotiation()
                    installStatusPages()
                    routing {
                        get("/default-not-found") {
                            throw ApiErrorException.NotFoundException()
                        }
                        get("/sanitized-not-found") {
                            throw ApiErrorException.NotFoundException(includePath = false)
                        }
                    }
                }

                val defaultBody = client.get("/default-not-found").bodyAsText()
                val sanitizedBody = client.get("/sanitized-not-found").bodyAsText()

                defaultBody shouldContain """"path":"/default-not-found""""
                sanitizedBody shouldContain """"path":null"""
                sanitizedBody shouldNotContain "/sanitized-not-found"
            }
        }

        test("does not duplicate a terminal error that is already logged") {
            testApplication {
                application {
                    installContentNegotiation()
                    installStatusPages()
                    routing {
                        get("/already-logged") {
                            throw ApiErrorException.InternalServerErrorException(
                                cause = IllegalStateException("Safe failure"),
                                isAlreadyLogged = true,
                            )
                        }
                    }
                }

                client.get("/already-logged").status shouldBe HttpStatusCode.InternalServerError
            }

            capture.logs.shouldBeEmpty()
        }

        test("preserves a hidden relation response without repeating its access rejection") {
            testApplication {
                application {
                    installContentNegotiation()
                    installStatusPages()
                    routing {
                        get("/hidden-relation") {
                            throw ApiErrorException.NotFoundException(isAlreadyLogged = true)
                        }
                    }
                }
                client.get("/hidden-relation").status shouldBe HttpStatusCode.NotFound
            }
            capture.logs.shouldBeEmpty()
        }

        test("logs an application 400 once as a structured rejection") {
            testApplication {
                application {
                    installContentNegotiation()
                    installStatusPages()
                    routing {
                        get("/invalid") {
                            throw ApiErrorException.BadRequestException(type = ErrorType.INVALID_FORMAT)
                        }
                    }
                }

                client.get("/invalid").status shouldBe HttpStatusCode.BadRequest
            }

            val record = capture.logs.single()
            record.level shouldBe Level.WARN
            val fields = record.fields
            fields["event_type"] shouldBe "api_request_invalid"
            fields["response_status"] shouldBe 400
            fields["error_type"] shouldBe "INVALID_FORMAT"
        }

        test("logs Ktor bad requests with their resolved rejection reasons") {
            testApplication {
                application {
                    installContentNegotiation()
                    installStatusPages()
                    routing {
                        get("/malformed") {
                            throw BadRequestException("Malformed JSON")
                        }
                        get("/invalid-field") {
                            throw BadRequestException("Invalid request", IllegalArgumentException("Invalid field"))
                        }
                    }
                }

                client.get("/malformed").status shouldBe HttpStatusCode.BadRequest
                client.get("/invalid-field").status shouldBe HttpStatusCode.BadRequest
            }

            val records = capture.logs
            records shouldHaveSize 2
            records.map { it.level } shouldBe listOf(Level.WARN, Level.WARN)
            val fields = records.map { it.fields }
            fields.map { it["event_type"] } shouldBe listOf("api_request_invalid", "api_request_invalid")
            fields.map { it["error_type"] } shouldBe listOf("BAD_REQUEST", "INVALID_FORMAT")
            fields.map { it["response_status"] } shouldBe listOf(400, 400)
        }

        test("keeps unlogged 404 responses at INFO without a rejection event") {
            testApplication {
                application {
                    installContentNegotiation()
                    installStatusPages()
                    routing {
                        get("/unknown") {
                            throw ApiErrorException.NotFoundException()
                        }
                    }
                }

                client.get("/unknown").status shouldBe HttpStatusCode.NotFound
            }

            val record = capture.logs.single()
            record.level shouldBe Level.INFO
            record.message shouldBe "Request rejected with status 404"
            record.fields.containsKey("event_type") shouldBe false
        }

        test("records one structured error for an unclassified server failure") {
            testApplication {
                application {
                    installContentNegotiation()
                    installStatusPages()
                    routing {
                        get("/unclassified") {
                            throw IllegalStateException("Safe failure")
                        }
                    }
                }

                client.get("/unclassified").status shouldBe HttpStatusCode.InternalServerError
            }

            capture.logs shouldHaveSize 1
            val record = capture.logs.single()
            record.level shouldBe Level.ERROR
            record.message shouldBe "Request failed with an unexpected server error"
            val fields = record.fields
            fields["event_type"] shouldBe "api_request_failed"
            fields["response_status"] shouldBe 500
            fields["cause_type"] shouldBe "IllegalStateException"
        }

        test("does not expose the message of an unclassified failure") {
            testApplication {
                application {
                    installContentNegotiation()
                    installStatusPages()
                    routing {
                        get("/unclassified") {
                            throw IllegalStateException("Internal detail")
                        }
                    }
                }

                val body = client.get("/unclassified").bodyAsText()
                body shouldContain "\"message\":\"Internal server error\""
                body shouldNotContain "Internal detail"
            }
        }

        test("rethrows cancellation without logging it as an error") {
            testApplication {
                application {
                    installStatusPages()
                    routing {
                        get("/cancel") {
                            throw CancellationException("Request cancelled")
                        }
                    }
                }

                client.get("/cancel")
            }

            capture.logs.shouldBeEmpty()
        }
    })
