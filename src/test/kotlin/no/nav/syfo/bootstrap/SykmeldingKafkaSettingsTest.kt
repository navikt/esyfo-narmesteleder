package no.nav.syfo.bootstrap

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SykmeldingKafkaSettingsTest :
    FunSpec({
        test("sykmeldingsregister consumption is disabled locally by default") {
            LocalEnvironment().sykmeldingKafkaSettings.consumeSykmeldingsregister shouldBe false
        }
    })
