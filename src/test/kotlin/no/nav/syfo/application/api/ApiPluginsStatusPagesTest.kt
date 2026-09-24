package no.nav.syfo.application.api

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CancellationException
import no.nav.syfo.application.exception.ApiErrorException
import org.slf4j.LoggerFactory

class ApiPluginsStatusPagesTest :
    DescribeSpec({
        val logAppender = ListAppender<ILoggingEvent>()
        val logger = LoggerFactory.getLogger(STATUS_PAGES_LOGGER_NAME) as Logger
        val originalLevel = logger.level
        val originalAdditive = logger.isAdditive

        beforeSpec {
            logger.level = Level.TRACE
            logger.isAdditive = false
            logAppender.start()
            logger.addAppender(logAppender)
        }

        afterSpec {
            logger.detachAppender(logAppender)
            logAppender.stop()
            logger.level = originalLevel
            logger.isAdditive = originalAdditive
        }

        beforeTest {
            logAppender.list.clear()
        }

        describe("StatusPages fallback logging") {
            it("includes paths in default NotFoundException errors and omits them when requested") {
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

            it("does not duplicate a terminal error that is already logged") {
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

                logAppender.list.shouldBeEmpty()
            }

            it("preserves a hidden relation response without repeating its access rejection") {
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
                logAppender.list.shouldBeEmpty()
            }

            it("logs an application 400 once as a structured rejection") {
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

                val record = logAppender.list.single()
                record.level shouldBe Level.WARN
                val fields = record.keyValuePairs.associate { it.key to it.value }
                fields["event_type"] shouldBe "api_request_invalid"
                fields["response_status"] shouldBe 400
                fields["error_type"] shouldBe "INVALID_FORMAT"
            }

            it("logs Ktor bad requests with their resolved rejection reasons") {
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

                val records = logAppender.list
                records shouldHaveSize 2
                records.map { it.level } shouldBe listOf(Level.WARN, Level.WARN)
                val fields = records.map { record -> record.keyValuePairs.associate { it.key to it.value } }
                fields.map { it["event_type"] } shouldBe listOf("api_request_invalid", "api_request_invalid")
                fields.map { it["error_type"] } shouldBe listOf("BAD_REQUEST", "INVALID_FORMAT")
                fields.map { it["response_status"] } shouldBe listOf(400, 400)
            }

            it("keeps unlogged 404 responses at INFO without a rejection event") {
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

                val record = logAppender.list.single()
                record.level shouldBe Level.INFO
                record.formattedMessage shouldBe "Request rejected with status 404"
                record.keyValuePairs.orEmpty().none { it.key == "event_type" } shouldBe true
            }

            it("records one structured error for an unclassified server failure") {
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

                logAppender.list shouldHaveSize 1
                val record = logAppender.list.single()
                record.level shouldBe Level.ERROR
                record.formattedMessage shouldBe "Request failed with an unexpected server error"
                val fields = record.keyValuePairs.associate { it.key to it.value }
                fields["event_type"] shouldBe "api_request_failed"
                fields["response_status"] shouldBe 500
                fields["cause_type"] shouldBe "IllegalStateException"
            }

            it("does not expose the message of an unclassified failure") {
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

            it("rethrows cancellation without logging it as an error") {
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

                logAppender.list.shouldBeEmpty()
            }
        }
    })
