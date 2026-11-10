package no.nav.syfo.platform.auth.texas

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import io.mockk.mockk
import no.nav.syfo.platform.auth.texas.client.TexasHttpClient
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin

class TexasAuthenticationInstallationTest :
    FunSpec({
        test("fails at installation when TexasHttpClient is missing from Koin") {
            val exception = shouldThrow<IllegalStateException> {
                testApplication {
                    application {
                        install(Koin) { modules(module { }) }
                        installTexasAuthentication(setOf("test-caller"))
                    }
                    startApplication()
                }
            }

            exception.message shouldContain "Texas authentication installed without TexasHttpClient"
        }

        test("fails at installation when Azure pre-authorized apps are empty") {
            val exception = shouldThrow<IllegalArgumentException> {
                testApplication {
                    application {
                        installTexasAuthentication(mockk(), emptySet())
                    }
                    startApplication()
                }
            }

            exception.message shouldContain "without pre-authorized apps"
        }

        test("installs providers with TexasHttpClient from Koin") {
            testApplication {
                application {
                    install(Koin) { modules(module { single<TexasHttpClient> { mockk() } }) }
                    installTexasAuthentication(setOf("test-caller"))
                }
                startApplication()
            }
        }
    })
