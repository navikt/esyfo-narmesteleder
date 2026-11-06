package no.nav.syfo.bootstrap

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldNotContain
import no.nav.syfo.organisasjonstilgang.infrastructure.altinnauthorization.AltinnAuthorizationSettings

class AltinnAuthorizationSettingsTest :
    FunSpec({
        test("subscription key is not exposed by settings or application settings toString") {
            val subscriptionKey = "test-subscription-key"
            val settings = AltinnAuthorizationSettings(subscriptionKey)
            settings.toString() shouldNotContain subscriptionKey
            localApplicationSettings().let { it.copy(organisasjonstilgang = it.organisasjonstilgang.copy(altinnAuthorization = settings)) }.toString() shouldNotContain subscriptionKey
        }
    })
