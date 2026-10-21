package no.nav.syfo.platform.observability

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.util.LogbackMDCAdapter
import ch.qos.logback.core.Appender
import io.kotest.core.listeners.AfterSpecListener
import io.kotest.core.listeners.BeforeSpecListener
import io.kotest.core.spec.Spec
import no.nav.esyfo.observability.testkit.LogCapture
import no.nav.esyfo.observability.testkit.captureLogs
import org.slf4j.LoggerFactory

internal const val PRODUCTION_JSON_APPENDER = "stdout_json"
private const val PRODUCTION_LOGBACK_CONFIG = "src/main/resources/logback.xml"

/** A fresh Logback context configured from the production logback.xml. Callers must [LoggerContext.stop] it. */
internal fun productionLoggerContext(): LoggerContext = LoggerContext().apply {
    mdcAdapter = LogbackMDCAdapter()
    putProperty("NAIS_CLUSTER_NAME", "test")
    try {
        JoranConfigurator().also {
            it.context = this
            it.doConfigure(PRODUCTION_LOGBACK_CONFIG)
        }
    } catch (e: Exception) {
        stop()
        throw e
    }
}

internal fun LoggerContext.productionJsonAppender(): Appender<ILoggingEvent> = requireNotNull(getLogger(Logger.ROOT_LOGGER_NAME).getAppender(PRODUCTION_JSON_APPENDER))

internal fun Logger.captureProductionLogs(): LogCapture = captureLogs(this, PRODUCTION_JSON_APPENDER)

/**
 * Routes the named application loggers through the production Logback encoder, as docs/logging.md requires.
 *
 * Register it spec-wide with `val productionLogs = extension(ProductionLogs(Owner::class.java))`, or call
 * [install]/[restore] around a single test.
 */
internal class ProductionLogs(
    loggerNames: List<String>,
    private val level: Level = Level.TRACE,
) : BeforeSpecListener,
    AfterSpecListener {
    constructor(owner: Class<*>, level: Level = Level.TRACE) : this(listOf(owner.name), level)

    val loggers: List<Logger> = loggerNames.map { LoggerFactory.getLogger(it) as Logger }
    val logger: Logger get() = loggers.first()

    private var installation: Installation? = null

    fun install() {
        check(installation == null) { "Production logs are already installed" }
        val context = productionLoggerContext()
        val appender = context.productionJsonAppender()
        installation = Installation(context, appender, loggers.map { it.level to it.isAdditive })
        loggers.forEach {
            it.level = level
            it.isAdditive = false
            it.addAppender(appender)
        }
    }

    fun restore() {
        val current = installation ?: return
        installation = null
        loggers.zip(current.previousSettings).forEach { (logger, previous) ->
            logger.detachAppender(current.appender)
            logger.level = previous.first
            logger.isAdditive = previous.second
        }
        current.context.stop()
    }

    fun capture(): LogCapture = logger.captureProductionLogs()

    override suspend fun beforeSpec(spec: Spec) = install()

    override suspend fun afterSpec(spec: Spec) = restore()

    private class Installation(
        val context: LoggerContext,
        val appender: Appender<ILoggingEvent>,
        val previousSettings: List<Pair<Level?, Boolean>>,
    )
}

/** Routes [owner]'s logs through the production Logback encoder for the duration of [block]. */
internal suspend fun withProductionLogs(owner: Class<*>, block: suspend (LogCapture) -> Unit) {
    val productionLogs = ProductionLogs(owner)
    productionLogs.install()
    try {
        productionLogs.capture().use { block(it) }
    } finally {
        productionLogs.restore()
    }
}

/** Runs [block] with a logger named [name] from a fresh production Logback context. */
internal inline fun <T> withProductionLogger(name: String, block: (Logger) -> T): T {
    val context = productionLoggerContext()
    try {
        return block(context.getLogger(name))
    } finally {
        context.stop()
    }
}
