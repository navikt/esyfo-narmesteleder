package no.nav.syfo.platform.observability

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.util.LogbackMDCAdapter
import ch.qos.logback.core.Appender
import ch.qos.logback.core.AppenderBase
import ch.qos.logback.core.OutputStreamAppender
import ch.qos.logback.core.encoder.Encoder
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.listeners.AfterSpecListener
import io.kotest.core.listeners.BeforeSpecListener
import io.kotest.core.spec.Spec
import no.nav.esyfo.observability.testkit.RuntimeLogContract
import org.slf4j.LoggerFactory

internal const val PRODUCTION_JSON_APPENDER = "stdout_json"
private const val PRODUCTION_LOGBACK_CONFIG = "src/main/resources/logback.xml"
private val CONTRACT_CATALOG_FIELDS = listOf("event_type", "error_code", "operation", "exception_type", "rejection_reason")
private val logMapper = jacksonObjectMapper()

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

/** Captures this logger's events encoded by its context's production JSON appender. */
internal fun Logger.captureProductionLogs(): ProductionLogCapture = ProductionLogCapture(listOf(this), loggerContext.productionJsonAppender())

/**
 * Routes the named application loggers through the production Logback encoder, as docs/logging.md requires.
 * [restore] puts back each logger's level, additivity and appenders.
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

    /** Captures events from every installed logger, in emission order, until the capture is closed. */
    fun capture(): ProductionLogCapture = ProductionLogCapture(loggers, checkNotNull(installation) { "Production logs are not installed" }.appender)

    override suspend fun beforeSpec(spec: Spec) = install()

    override suspend fun afterSpec(spec: Spec) = restore()

    private class Installation(
        val context: LoggerContext,
        val appender: Appender<ILoggingEvent>,
        val previousSettings: List<Pair<Level?, Boolean>>,
    )
}

/** Routes [owner]'s logs at [level] through the production Logback encoder for the duration of [block]. */
internal suspend fun <T> withProductionLogs(
    owner: Class<*>,
    level: Level = Level.TRACE,
    block: suspend (ProductionLogCapture) -> T,
): T {
    val productionLogs = ProductionLogs(owner, level)
    productionLogs.install()
    try {
        return productionLogs.capture().use { block(it) }
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

/** Records encoded by the production JSON encoder. They remain readable after [close]. */
internal class ProductionLogCapture(
    private val loggers: List<Logger>,
    productionAppender: Appender<ILoggingEvent>,
) : AutoCloseable {
    private val recorder: EncodingRecorder

    init {
        require(productionAppender is OutputStreamAppender<ILoggingEvent>) { "The production appender must be an OutputStreamAppender" }
        val encoder = requireNotNull(productionAppender.encoder) { "The production appender has no encoder" }
        check(encoder.isStarted) { "The production encoder must already be started" }
        recorder = EncodingRecorder(encoder).apply {
            context = productionAppender.context
            start()
        }
        loggers.forEach { it.addAppender(recorder) }
    }

    /** Serialized JSON lines. */
    val records: List<String> get() = recorder.snapshot()

    /**
     * Parsed records. Every record carrying an `event_type` is first checked against the [RuntimeLogContract] schema,
     * and no record may carry `logging_context_invalid`. The catalog is built from the captured records, so event
     * names are not checked against a closed catalog; validate [records] with an explicit catalog for that.
     */
    val logs: List<ProductionLogRecord>
        get() = records.also(::assertRuntimeLogContract).map(::ProductionLogRecord)

    override fun close() {
        loggers.forEach { it.detachAppender(recorder) }
        recorder.stop()
    }
}

/** One captured record, decoded from the production JSON encoder. */
internal class ProductionLogRecord(val serialized: String) {
    val json: JsonNode = logMapper.readTree(serialized)

    /** Root fields, including structured key-value pairs. JSON integers decode as [Int] when they fit. */
    val fields: Map<String, Any?> = logMapper.convertValue(json)

    val level: Level get() = Level.toLevel(json.path("level").asText(), null)
    val message: String get() = json.path("message").asText()
    val loggerName: String get() = json.path("logger_name").asText()
    val eventType: String? get() = fields["event_type"] as String?

    /** The logged throwable's message, read from the first `stack_trace` line, or null without a throwable. */
    val throwableMessage: String?
        get() = (fields["stack_trace"] as String?)?.lineSequence()?.first()?.substringAfter(": ", "")

    operator fun get(field: String): Any? = fields[field]

    override fun toString(): String = serialized
}

private fun assertRuntimeLogContract(records: List<String>) {
    val parsed = records.map(logMapper::readTree)
    parsed.forEachIndexed { index, record ->
        check(!record.has("logging_context_invalid")) { "Record ${index + 1} has an invalid log context" }
    }
    val events = records.filterIndexed { index, _ -> parsed[index].has("event_type") }
    if (events.isEmpty()) return
    val catalog = CONTRACT_CATALOG_FIELDS.associateWith { field ->
        parsed.mapNotNull { record -> record[field]?.takeIf(JsonNode::isTextual)?.asText() }.toSet()
    }.filterValues { it.isNotEmpty() }
    RuntimeLogContract(catalog).assertValid(events)
}

private class EncodingRecorder(private val encoder: Encoder<ILoggingEvent>) : AppenderBase<ILoggingEvent>() {
    private val encoded = mutableListOf<String>()

    override fun append(event: ILoggingEvent) {
        // Freeze lazy MDC and message fields while the emitting thread still has its context.
        event.prepareForDeferredProcessing()
        synchronized(encoded) {
            encoded += encoder.encode(event).decodeToString().trimEnd('\r', '\n')
        }
    }

    fun snapshot(): List<String> = synchronized(encoded) { encoded.toList() }
}
