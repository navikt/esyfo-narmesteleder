package no.nav.syfo.platform.observability

import no.nav.esyfo.observability.ApplicationLogger
import no.nav.esyfo.observability.createLogger
import org.slf4j.LoggerFactory

fun applicationLogger(owner: Class<*>): ApplicationLogger = createLogger(LoggerFactory.getLogger(owner))

private const val TEAM_LOGS_LOGGER_NAME = "team-logs-logger"

// Team logs may contain sensitive data (e.g. fnr) and are isolated from standard application logs.
fun teamLogsLogger(): ApplicationLogger = createLogger(LoggerFactory.getLogger(TEAM_LOGS_LOGGER_NAME))
