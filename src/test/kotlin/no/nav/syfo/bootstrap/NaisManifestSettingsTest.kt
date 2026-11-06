package no.nav.syfo.bootstrap

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.platform.config.applicationConfigWithEnvironment
import org.yaml.snakeyaml.Yaml
import java.io.File
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class NaisManifestSettingsTest :
    FunSpec({
        test("the dev manifest provides every required setting and valid values") {
            assertSoftly(settingsFromManifest("nais/nais-dev.yaml")) {
                narmestelederbehovSettings.dialogUpdatePollingInterval shouldBe 10.minutes
                maintenanceTaskSettings.interval shouldBe 10.minutes
                narmestelederrelasjonEmploymentSettings.employmentCheckRevokeEnabled shouldBe true
            }
        }

        test("the prod manifest provides every required setting and valid values") {
            settingsFromManifest("nais/nais-prod.yaml").maintenanceTaskSettings.interval shouldBe 1.hours
        }
    })

private fun settingsFromManifest(path: String): ApplicationSettings = ApplicationSettings.from(
    applicationConfigWithEnvironment(manifestEnvironment(path)),
)

/** The variables NAIS gives the pod: the manifest's `env` plus those injected by `leaderElection: true`. */
@Suppress("UNCHECKED_CAST")
private fun manifestEnvironment(path: String): Map<String, String> {
    // `{{image}}` is a deploy-time template, not YAML.
    val manifest = Yaml().load<Map<String, Any?>>(File(path).readText().replace("{{image}}", "image"))
    val spec = manifest.getValue("spec") as Map<String, Any?>
    val env = (spec["env"] as List<Map<String, Any?>>).associate { it.getValue("name").toString() to it["value"].toString() }
    val leaderElection = if (spec["leaderElection"] == true) {
        mapOf("ELECTOR_PATH" to "elector-path", "ELECTOR_SSE_URL" to "http://elector.example/sse")
    } else {
        emptyMap()
    }
    return env + leaderElection
}
