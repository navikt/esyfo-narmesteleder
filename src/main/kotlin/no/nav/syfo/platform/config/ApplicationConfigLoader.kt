package no.nav.syfo.platform.config

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigParseOptions
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.config.HoconApplicationConfig
import kotlin.time.Duration

const val APPLICATION_CONF = "application.conf"

/** Loads [APPLICATION_CONF] and resolves its `${?ENV}` overrides against the process environment. */
fun loadApplicationConfig(): ApplicationConfig = HoconApplicationConfig(parseConfigResources(APPLICATION_CONF).resolve())

/** Parses classpath [resources], where earlier resources override later ones. Every resource must exist. */
fun parseConfigResources(vararg resources: String): Config = resources
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
