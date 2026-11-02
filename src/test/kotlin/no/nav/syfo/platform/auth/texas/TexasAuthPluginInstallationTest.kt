package no.nav.syfo.platform.auth.texas

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.ktor.server.application.install
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.mockk
import no.nav.syfo.platform.auth.texas.client.TexasHttpClient
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin

class TexasAuthPluginInstallationTest :
    FunSpec({
        val pluginsWithoutClient: List<Pair<String, Route.() -> Unit>> = listOf(
            "MaskinportenAndTokenXTokenAuthPlugin" to { install(MaskinportenAndTokenXTokenAuthPlugin) },
            "TokenXTokenAuthPlugin" to { install(TokenXTokenAuthPlugin) },
            "AzureAdTokenAuthPlugin" to { install(AzureAdTokenAuthPlugin) { preAuthorizedApps = setOf("app") } },
        )

        pluginsWithoutClient.forEach { (pluginName, installPlugin) ->
            test("$pluginName fails at install when TexasHttpClient is missing") {
                val exception = shouldThrow<IllegalStateException> {
                    testApplication {
                        application {
                            routing { route("/protected") { installPlugin() } }
                        }
                        startApplication()
                    }
                }

                exception.message shouldContain "$pluginName installed without TexasHttpClient"
            }
        }

        test("TokenXTokenAuthPlugin installs with TexasHttpClient from Koin") {
            testApplication {
                application {
                    install(Koin) { modules(module { single<TexasHttpClient> { mockk() } }) }
                    routing { route("/protected") { install(TokenXTokenAuthPlugin) } }
                }
                startApplication()
            }
        }

        test("TokenXTokenAuthPlugin fails at install when Koin has no TexasHttpClient") {
            val exception = shouldThrow<IllegalStateException> {
                testApplication {
                    application {
                        install(Koin) { modules(module { }) }
                        routing { route("/protected") { install(TokenXTokenAuthPlugin) } }
                    }
                    startApplication()
                }
            }

            exception.message shouldContain "TokenXTokenAuthPlugin installed without TexasHttpClient"
        }
    })
