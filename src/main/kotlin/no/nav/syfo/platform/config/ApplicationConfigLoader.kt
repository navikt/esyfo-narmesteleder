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

/**
 * Reads typed values and collects every missing or invalid setting, so that startup reports all of them at once.
 * Error messages name the config path, never the value, because values may be sensitive.
 */
class ConfigReader private constructor(private val config: ApplicationConfig) {
    private val errors = mutableListOf<String>()

    fun string(path: String): String = config.propertyOrNull(path)?.getString() ?: invalid(path, "is missing", "")

    /** Keeps the lenient [String.toBoolean] semantics: only a case-insensitive `true` enables a flag. */
    fun flag(path: String): Boolean = config.propertyOrNull(path)?.getString()?.toBoolean() ?: invalid(path, "is missing", false)

    fun duration(path: String): Duration = parsed(path, "is not a valid duration", Duration.ZERO) { Duration.parseOrNull(it) }

    fun long(path: String): Long = parsed(path, "is not a whole number", 0L) { it.toLongOrNull() }

    private fun <T : Any> parsed(path: String, problem: String, placeholder: T, parse: (String) -> T?): T {
        val value = config.propertyOrNull(path)?.getString() ?: return invalid(path, "is missing", placeholder)
        return parse(value) ?: invalid(path, problem, placeholder)
    }

    private fun <T> invalid(path: String, problem: String, placeholder: T): T {
        errors += "$path $problem"
        return placeholder
    }

    companion object {
        /** Runs [build] with placeholder values for invalid settings, then fails if any setting was invalid. */
        fun <T> read(config: ApplicationConfig, build: ConfigReader.() -> T): T {
            val reader = ConfigReader(config)
            val result = reader.build()
            check(reader.errors.isEmpty()) { "Invalid configuration: ${reader.errors.joinToString(", ")}" }
            return result
        }
    }
}
