package no.nav.syfo.platform.config

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigParseOptions
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.config.HoconApplicationConfig
import kotlin.time.Duration

const val APPLICATION_CONF = "application.conf"

/**
 * Loads the config file for the NAIS cluster over [APPLICATION_CONF] and resolves `${?ENV}` overrides against the process
 * environment. The cluster name is the only setting read here, because it selects the files.
 */
fun loadApplicationConfig(): ApplicationConfig = HoconApplicationConfig(
    parseConfigResources(configResources(System.getenv("NAIS_CLUSTER_NAME"))).resolve(),
)

/** `application-<cluster>.conf` overrides [APPLICATION_CONF]. Without a cluster, only [APPLICATION_CONF] is loaded. */
fun configResources(cluster: String?): List<String> = listOfNotNull(cluster?.takeIf { it.isNotBlank() }?.let { "application-$it.conf" }, APPLICATION_CONF)

/** Parses classpath [resources], where earlier resources override later ones. Every resource must exist. */
fun parseConfigResources(resources: List<String>): Config = resources
    .map { ConfigFactory.parseResources(it, ConfigParseOptions.defaults().setAllowMissing(false)) }
    .reduce(Config::withFallback)

// Errors name the config path, never the value, because values may be sensitive.

fun ApplicationConfig.string(path: String): String = propertyOrNull(path)?.getString() ?: invalid(path, "is missing")

/** Keeps the lenient [String.toBoolean] semantics: only a case-insensitive `true` enables a flag. */
fun ApplicationConfig.flag(path: String): Boolean = string(path).toBoolean()

fun ApplicationConfig.duration(path: String): Duration = parsed(path, "is not a valid duration") { Duration.parseOrNull(it) }

fun ApplicationConfig.long(path: String): Long = parsed(path, "is not a whole number") { it.toLongOrNull() }

fun ApplicationConfig.int(path: String): Int = parsed(path, "is not a whole number") { it.toIntOrNull() }

/** Fails with [problem] when [parse] returns null. */
fun <T : Any> ApplicationConfig.parsed(path: String, problem: String, parse: (String) -> T?): T = parse(string(path)) ?: invalid(path, problem)

private fun invalid(path: String, problem: String): Nothing = throw IllegalStateException("Invalid configuration: $path $problem")
