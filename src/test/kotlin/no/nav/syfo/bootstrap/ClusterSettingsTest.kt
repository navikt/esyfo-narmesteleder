package no.nav.syfo.bootstrap

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import no.nav.syfo.platform.config.applicationConfigWithEnvironment
import no.nav.syfo.platform.config.configResources
import org.yaml.snakeyaml.Yaml
import java.io.File

class ClusterSettingsTest :
    FunSpec({
        listOf("dev-gcp" to "nais/nais-dev.yaml", "prod-gcp" to "nais/nais-prod.yaml").forEach { (cluster, manifest) ->
            test("$cluster gets every required setting with valid values from its config file and $manifest") {
                shouldNotThrowAny { clusterSettings(cluster, manifest) }
            }

            test("$manifest sets only JVM runtime variables in env, because application.conf does not read the others") {
                (manifestEnvNames(manifest) - runtimeVariables).shouldBeEmpty()
            }
        }
    })

internal fun clusterSettings(cluster: String, manifest: String): ApplicationSettings = ApplicationSettings.from(
    applicationConfigWithEnvironment(naisEnvironment(cluster, manifest), configResources(cluster)),
)

/**
 * The variables NAIS gives the pod: the cluster name, the manifest's `envFrom` secrets and those injected by
 * `leaderElection: true`, the Texas annotation, `gcp.sqlInstances`, `valkey` and `kafka`. Injected values are synthetic.
 * The manifest's `env` is left out, because application settings belong in the cluster's config file.
 */
@Suppress("UNCHECKED_CAST")
private fun naisEnvironment(cluster: String, path: String): Map<String, String> {
    val manifest = naisManifest(path)
    val metadata = manifest.getValue("metadata") as Map<String, Any?>
    val annotations = metadata["annotations"] as Map<String, Any?>? ?: emptyMap()
    val spec = manifest.getValue("spec") as Map<String, Any?>
    val texas = if (annotations["texas.nais.io/enabled"] == "true") texasVariables else emptyMap()
    val gcp = spec["gcp"] as Map<String, Any?>? ?: emptyMap()
    val databasePrefixes = (gcp["sqlInstances"] as List<Map<String, Any?>>? ?: emptyList())
        .flatMap { it["databases"] as List<Map<String, Any?>> }
        .mapNotNull { it["envVarPrefix"]?.toString() }
    val kafka = if (spec["kafka"] != null) kafkaVariables else emptyMap()
    val valkeyInstances = (spec["valkey"] as List<Map<String, Any?>>? ?: emptyList()).map { it.getValue("instance").toString() }
    val leaderElection = if (spec["leaderElection"] == true) {
        mapOf("ELECTOR_PATH" to "elector-path", "ELECTOR_SSE_URL" to "http://elector.example/sse")
    } else {
        emptyMap()
    }
    val secrets = (spec["envFrom"] as List<Map<String, Any?>>).flatMap { secretVariables.getValue(it.getValue("secret").toString()) }
    return leaderElection + texas + secrets.associateWith { "synthetic-secret" } + ("NAIS_CLUSTER_NAME" to cluster) +
        databasePrefixes.flatMap(::databaseVariables) + valkeyInstances.flatMap(::valkeyVariables) + kafka
}

private val kafkaVariables = listOf(
    "KAFKA_BROKERS",
    "KAFKA_SCHEMA_REGISTRY",
    "KAFKA_SCHEMA_REGISTRY_USER",
    "KAFKA_SCHEMA_REGISTRY_PASSWORD",
    "KAFKA_TRUSTSTORE_PATH",
    "KAFKA_KEYSTORE_PATH",
    "KAFKA_CREDSTORE_PASSWORD",
).associateWith { "synthetic" }

private fun databaseVariables(prefix: String) = listOf("HOST", "DATABASE", "USERNAME", "PASSWORD", "SSLCERT", "SSLKEY_PK8", "SSLROOTCERT", "SSLMODE").map { "${prefix}_$it" to "synthetic" } +
    ("${prefix}_PORT" to "5432")

private fun valkeyVariables(instance: String): List<Pair<String, String>> {
    val suffix = instance.uppercase().replace('-', '_')
    return listOf("HOST", "USERNAME", "PASSWORD").map { "VALKEY_${it}_$suffix" to "synthetic" } + ("VALKEY_PORT_$suffix" to "26379")
}

private val texasVariables = listOf("NAIS_TOKEN_INTROSPECTION_ENDPOINT", "NAIS_TOKEN_ENDPOINT", "NAIS_TOKEN_EXCHANGE_ENDPOINT")
    .associateWith { "http://texas.example" }

private val secretVariables = mapOf("altinn-subscription-key" to listOf("PDP_SUBSCRIPTION_KEY"))

private val runtimeVariables = setOf("JDK_JAVA_OPTIONS")

@Suppress("UNCHECKED_CAST")
private fun manifestEnvNames(path: String): Set<String> {
    val spec = naisManifest(path).getValue("spec") as Map<String, Any?>
    return (spec["env"] as List<Map<String, Any?>>? ?: emptyList()).map { it.getValue("name").toString() }.toSet()
}

// `{{image}}` is a deploy-time template, not YAML.
private fun naisManifest(path: String): Map<String, Any?> = Yaml().load(File(path).readText().replace("{{image}}", "image"))
