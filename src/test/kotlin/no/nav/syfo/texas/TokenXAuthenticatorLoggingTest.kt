package no.nav.syfo.texas

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class TokenXAuthenticatorLoggingTest :
    StringSpec({
        "ACR logging only permits the four approved values" {
            listOf("Level3", "Level4", "idporten-loa-substantial", "idporten-loa-high").forEach {
                it.logAcr() shouldBe it
            }
            listOf("Level1", "Level2", "level4", "private-canary", null).forEach {
                it.logAcr() shouldBe "unknown"
            }
        }
    })
