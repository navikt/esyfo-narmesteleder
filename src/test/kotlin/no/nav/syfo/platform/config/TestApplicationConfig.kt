package no.nav.syfo.platform.config

import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigResolveOptions
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.config.HoconApplicationConfig

const val LOCAL_APPLICATION_CONF = "application-local.conf"

private val LOCAL_CONFIG_RESOURCES = listOf(LOCAL_APPLICATION_CONF, APPLICATION_CONF)

/**
 * Resolves the config files against [environment] instead of the machine's environment variables.
 * The variables are layered in at the root, because `resolveWith` fails on `${?VAR}` overrides of a default.
 * [overrides] maps config paths to values and takes precedence over every file.
 */
fun applicationConfigWithEnvironment(
    environment: Map<String, String>,
    resources: List<String>,
    overrides: Map<String, String> = emptyMap(),
): ApplicationConfig = HoconApplicationConfig(
    ConfigFactory.parseMap(overrides)
        .withFallback(parseConfigResources(resources))
        .withFallback(ConfigFactory.parseMap(environment))
        .resolve(ConfigResolveOptions.noSystem()),
)

fun localApplicationConfig(): ApplicationConfig = applicationConfigWithEnvironment(emptyMap(), LOCAL_CONFIG_RESOURCES)
