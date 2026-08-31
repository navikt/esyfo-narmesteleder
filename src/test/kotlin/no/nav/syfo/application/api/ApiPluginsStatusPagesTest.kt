package no.nav.syfo.application.api

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
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

            it("keeps a warn-level fallback for unclassified failures") {
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
                logAppender.list.single().level shouldBe Level.WARN
                logAppender.list.single().formattedMessage shouldBe "Unhandled API exception"
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
