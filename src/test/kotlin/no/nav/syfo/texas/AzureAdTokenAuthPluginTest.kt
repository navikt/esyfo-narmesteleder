package no.nav.syfo.texas

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class AzureAdTokenAuthPluginTest :
    DescribeSpec({
        describe("preAuthorizedAppsFromJson") {
            it("extracts client IDs from the NAIS pre-authorized applications payload") {
                val configuredApps = """
                    [
                      {
                        "name": "dev-gcp:team-esyfo:syfo-budstikka",
                        "clientId": "0b26d3d5-8e1e-47a7-8cab-719921fceddf"
                      }
                    ]
                """.trimIndent()

                preAuthorizedAppsFromJson(configuredApps) shouldBe setOf("0b26d3d5-8e1e-47a7-8cab-719921fceddf")
            }
        }
    })
