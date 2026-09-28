package no.nav.syfo.texas

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication

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
    })
