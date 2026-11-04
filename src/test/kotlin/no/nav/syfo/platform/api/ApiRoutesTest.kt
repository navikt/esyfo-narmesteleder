package no.nav.syfo.platform.api

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication

class ApiRoutesTest :
    FunSpec({
        listOf<Pair<String, Route.(Route.() -> Unit) -> Route>>(
            API_V1_PATH to { build -> apiV1(build) },
            INTERNAL_API_V1_PATH to { build -> internalApiV1(build) },
        ).forEach { (prefix, prefixRoute) ->
            test("several modules can add authenticated routes under $prefix") {
                testApplication {
                    application {
                        installTestAuthentication()
                        routing { prefixRoute { authenticate(TEST_PROVIDER) { get("/first") { call.respondText("first") } } } }
                        routing { prefixRoute { authenticate(TEST_PROVIDER) { get("/second") { call.respondText("second") } } } }
                    }

                    client.get("$prefix/first").bodyAsText() shouldBe "first"
                    client.get("$prefix/second").status shouldBe HttpStatusCode.OK
                }
            }

            test("startup fails when a route under $prefix is not authenticated") {
                val exception = shouldThrow<IllegalStateException> {
                    testApplication {
                        application {
                            installTestAuthentication()
                            routing { prefixRoute { authenticate(TEST_PROVIDER) { get("/protected") { call.respondText("ok") } } } }
                            routing { prefixRoute { get("/open") { call.respondText("open") } } }
                        }
                        startApplication()
                    }
                }

                exception.message shouldContain "$prefix/open"
            }

            test("startup fails when public routes are declared under $prefix") {
                val exception = shouldThrow<IllegalStateException> {
                    testApplication {
                        application {
                            installTestAuthentication()
                            routing { prefixRoute { publicRoutes { get("/public") { call.respondText("public") } } } }
                        }
                        startApplication()
                    }
                }

                exception.message shouldContain "/public"
            }

            test("startup fails when a route under $prefix has optional authentication") {
                val exception = shouldThrow<IllegalStateException> {
                    testApplication {
                        application {
                            installTestAuthentication()
                            routing {
                                prefixRoute {
                                    authenticate(TEST_PROVIDER) {
                                        authenticate(TEST_PROVIDER, optional = true) {
                                            get("/optional") { call.respondText("optional") }
                                        }
                                    }
                                }
                            }
                        }
                        startApplication()
                    }
                }

                exception.message shouldContain "/optional"
            }
        }

        test("public routes outside the API prefixes are reachable without authentication") {
            testApplication {
                application {
                    installTestAuthentication()
                    requireAuthenticatedRoutesOnStartup()
                    routing { publicRoutes { get("/internal/is_alive") { call.respondText("alive") } } }
                }

                client.get("/internal/is_alive").bodyAsText() shouldBe "alive"
            }
        }

        test("startup fails when a route outside the API prefixes is neither authenticated nor public") {
            val exception = shouldThrow<IllegalStateException> {
                testApplication {
                    application {
                        installTestAuthentication()
                        requireAuthenticatedRoutesOnStartup()
                        routing {
                            publicRoutes { get("/internal/is_alive") { call.respondText("alive") } }
                            get("/internal/undeclared") { call.respondText("open") }
                        }
                    }
                    startApplication()
                }
            }

            exception.message shouldContain "/internal/undeclared"
        }

        test("authenticated routes outside the API prefixes are allowed") {
            testApplication {
                application {
                    installTestAuthentication()
                    requireAuthenticatedRoutesOnStartup()
                    routing { authenticate(TEST_PROVIDER) { get("/other") { call.respondText("other") } } }
                }

                client.get("/other").status shouldBe HttpStatusCode.OK
            }
        }
    })

private const val TEST_PROVIDER = "test"

private fun Application.installTestAuthentication() {
    install(Authentication) {
        provider(TEST_PROVIDER) {
            authenticate { context -> context.principal(UserIdPrincipal("test")) }
        }
    }
}
