package no.nav.syfo.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import no.nav.esyfo.observability.testkit.LogCapture
import no.nav.esyfo.observability.testkit.captureLogs
import org.slf4j.LoggerFactory

internal suspend fun <T> withProductionLogs(
    owner: Class<*>,
    level: Level = Level.TRACE,
    block: suspend (LogCapture) -> T,
): T {
    val context = LoggerContext()
    try {
        context.putProperty("NAIS_CLUSTER_NAME", "test")
        JoranConfigurator().apply {
            this.context = context
            doConfigure("src/main/resources/logback.xml")
        }
        val appender = requireNotNull(context.getLogger(Logger.ROOT_LOGGER_NAME).getAppender("stdout_json"))
        val logger = LoggerFactory.getLogger(owner) as Logger
        val previous = logger.level to logger.isAdditive
        logger.level = level
        logger.isAdditive = false
        logger.addAppender(appender)
        try {
            return captureLogs(logger, "stdout_json").use { block(it) }
        } finally {
            logger.detachAppender(appender)
            logger.level = previous.first
            logger.isAdditive = previous.second
        }
    } finally {
        context.stop()
    }
}
