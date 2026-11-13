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
private fun naisEnvironment(cluster: String, path: String): Map<String, String> {
    val manifest = naisManifest(path)
    val spec = manifest.child("spec")
    return mapOf("NAIS_CLUSTER_NAME" to cluster) +
        texasVariables(manifest.child("metadata").child("annotations")) +
        leaderElectionVariables(spec) +
        secretVariables(spec) +
        databaseVariables(spec) +
        valkeyVariables(spec) +
        kafkaVariables(spec)
}

private val secretKeys = mapOf("altinn-subscription-key" to listOf("PDP_SUBSCRIPTION_KEY"))

private val runtimeVariables = setOf("JDK_JAVA_OPTIONS")

private fun texasVariables(annotations: YamlMap): Map<String, String> = if (annotations["texas.nais.io/enabled"] == "true") {
    listOf("NAIS_TOKEN_INTROSPECTION_ENDPOINT", "NAIS_TOKEN_ENDPOINT", "NAIS_TOKEN_EXCHANGE_ENDPOINT").associateWith { "http://texas.example" }
} else {
    emptyMap()
}

private fun leaderElectionVariables(spec: YamlMap): Map<String, String> = if (spec["leaderElection"] == true) {
    mapOf("ELECTOR_PATH" to "elector-path", "ELECTOR_SSE_URL" to "http://elector.example/sse")
} else {
    emptyMap()
}

private fun secretVariables(spec: YamlMap): Map<String, String> = spec.items("envFrom")
    .flatMap { secretKeys.getValue(it.text("secret")) }
    .associateWith { "synthetic-secret" }

private fun databaseVariables(spec: YamlMap): Map<String, String> = spec.child("gcp").items("sqlInstances")
    .flatMap { it.items("databases") }
    .mapNotNull { it["envVarPrefix"]?.toString() }
    .flatMap { prefix ->
        listOf("HOST", "DATABASE", "USERNAME", "PASSWORD", "SSLCERT", "SSLKEY_PK8", "SSLROOTCERT", "SSLMODE").map { "${prefix}_$it" to "synthetic" } +
            ("${prefix}_PORT" to "5432")
    }
    .toMap()

private fun valkeyVariables(spec: YamlMap): Map<String, String> = spec.items("valkey")
    .map { it.text("instance").uppercase().replace('-', '_') }
    .flatMap { suffix -> listOf("HOST", "USERNAME", "PASSWORD").map { "VALKEY_${it}_$suffix" to "synthetic" } + ("VALKEY_PORT_$suffix" to "26379") }
    .toMap()

private fun kafkaVariables(spec: YamlMap): Map<String, String> = if (spec["kafka"] != null) {
    listOf(
        "KAFKA_BROKERS",
        "KAFKA_SCHEMA_REGISTRY",
        "KAFKA_SCHEMA_REGISTRY_USER",
        "KAFKA_SCHEMA_REGISTRY_PASSWORD",
        "KAFKA_TRUSTSTORE_PATH",
        "KAFKA_KEYSTORE_PATH",
        "KAFKA_CREDSTORE_PASSWORD",
    ).associateWith { "synthetic" }
} else {
    emptyMap()
}

private fun manifestEnvNames(path: String): Set<String> = naisManifest(path).child("spec").items("env").map { it.text("name") }.toSet()

// `{{image}}` is a deploy-time template, not YAML.
private fun naisManifest(path: String): YamlMap = Yaml().load(File(path).readText().replace("{{image}}", "image"))

private fun YamlMap.child(key: String): YamlMap = this[key] as? YamlMap ?: emptyMap<Any?, Any?>()

private fun YamlMap.items(key: String): List<YamlMap> = (this[key] as? List<*>).orEmpty().filterIsInstance<YamlMap>()

private fun YamlMap.text(key: String): String = checkNotNull(this[key]) { "$key is missing" }.toString()

private typealias YamlMap = Map<*, *>
