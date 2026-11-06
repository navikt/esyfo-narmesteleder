package no.nav.syfo.bootstrap

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
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
                texas.azurePreAuthorizedApps.shouldNotBeEmpty()
            }
        }

        test("the prod manifest provides every required setting and valid values") {
            assertSoftly(settingsFromManifest("nais/nais-prod.yaml")) {
                maintenanceTaskSettings.interval shouldBe 1.hours
                texas.azurePreAuthorizedApps.shouldNotBeEmpty()
            }
        }
    })

private fun settingsFromManifest(path: String): ApplicationSettings = ApplicationSettings.from(
    applicationConfigWithEnvironment(manifestEnvironment(path)),
)

/**
 * The variables NAIS gives the pod: the manifest's `env`, its `envFrom` secrets, the cluster name and those injected by
 * `leaderElection: true`, the Texas annotation, `gcp.sqlInstances` and `valkey`. Injected values are synthetic.
 */
@Suppress("UNCHECKED_CAST")
private fun manifestEnvironment(path: String): Map<String, String> {
    // `{{image}}` is a deploy-time template, not YAML.
    val manifest = Yaml().load<Map<String, Any?>>(File(path).readText().replace("{{image}}", "image"))
    val metadata = manifest.getValue("metadata") as Map<String, Any?>
    val annotations = metadata["annotations"] as Map<String, Any?>? ?: emptyMap()
    val spec = manifest.getValue("spec") as Map<String, Any?>
    val texas = if (annotations["texas.nais.io/enabled"] == "true") texasVariables else emptyMap()
    val gcp = spec["gcp"] as Map<String, Any?>? ?: emptyMap()
    val databasePrefixes = (gcp["sqlInstances"] as List<Map<String, Any?>>? ?: emptyList())
        .flatMap { it["databases"] as List<Map<String, Any?>> }
        .mapNotNull { it["envVarPrefix"]?.toString() }
    val valkeyInstances = (spec["valkey"] as List<Map<String, Any?>>? ?: emptyList()).map { it.getValue("instance").toString() }
    val env = (spec["env"] as List<Map<String, Any?>>).associate { it.getValue("name").toString() to it["value"].toString() }
    val leaderElection = if (spec["leaderElection"] == true) {
        mapOf("ELECTOR_PATH" to "elector-path", "ELECTOR_SSE_URL" to "http://elector.example/sse")
    } else {
        emptyMap()
    }
    val secrets = (spec["envFrom"] as List<Map<String, Any?>>).flatMap { secretVariables.getValue(it.getValue("secret").toString()) }
    return env + leaderElection + texas + secrets.associateWith { "synthetic-secret" } + ("NAIS_CLUSTER_NAME" to "test-cluster") +
        databasePrefixes.flatMap(::databaseVariables) + valkeyInstances.flatMap(::valkeyVariables)
}

private fun databaseVariables(prefix: String) = listOf("HOST", "DATABASE", "USERNAME", "PASSWORD", "SSLCERT", "SSLKEY_PK8", "SSLROOTCERT", "SSLMODE").map { "${prefix}_$it" to "synthetic" } +
    ("${prefix}_PORT" to "5432")

private fun valkeyVariables(instance: String): List<Pair<String, String>> {
    val suffix = instance.uppercase().replace('-', '_')
    return listOf("HOST", "USERNAME", "PASSWORD").map { "VALKEY_${it}_$suffix" to "synthetic" } + ("VALKEY_PORT_$suffix" to "26379")
}

private val texasVariables = listOf("NAIS_TOKEN_INTROSPECTION_ENDPOINT", "NAIS_TOKEN_ENDPOINT", "NAIS_TOKEN_EXCHANGE_ENDPOINT")
    .associateWith { "http://texas.example" }

private val secretVariables = mapOf("altinn-subscription-key" to listOf("PDP_SUBSCRIPTION_KEY"))
