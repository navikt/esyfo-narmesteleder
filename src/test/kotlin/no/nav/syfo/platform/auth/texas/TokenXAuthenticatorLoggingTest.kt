package no.nav.syfo.platform.auth.texas

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class TokenXAuthenticatorLoggingTest :
    FunSpec({
        listOf("Level3", "Level4", "idporten-loa-substantial", "idporten-loa-high").forEach { acr ->
            test("ACR logging permits the approved value $acr") {
                acr.logAcr() shouldBe acr
            }
        }

        listOf("Level1", "Level2", "level4", "private-canary", null).forEach { acr ->
            test("ACR logging replaces the unapproved value $acr with unknown") {
                acr.logAcr() shouldBe "unknown"
            }
        }
    })
