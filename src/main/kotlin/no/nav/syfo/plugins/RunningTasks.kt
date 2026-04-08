package no.nav.syfo.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopPreparing
import io.ktor.server.application.ServerReady
import kotlinx.coroutines.runBlocking
import no.nav.syfo.application.environment.Environment
import no.nav.syfo.application.events.LeaderChange
import no.nav.syfo.application.events.LeaderChangeEvent
import no.nav.syfo.application.kafka.consumerProperties
import no.nav.syfo.application.kafka.jacksonMapper
import no.nav.syfo.narmesteleder.kafka.LeesahNLKafkaConsumer
import no.nav.syfo.narmesteleder.kafka.NlBehovLeesahHandler
import no.nav.syfo.sykmelding.kafka.PersistSendtSykmeldingConsumer
import no.nav.syfo.sykmelding.kafka.SendtSykmeldingHandler
import no.nav.syfo.sykmelding.kafka.SendtSykmeldingKafkaConsumer
import no.nav.syfo.util.logger
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.koin.ktor.ext.inject
import kotlin.time.Duration.Companion.minutes

fun Application.configureKafkaConsumers() {
    val nlLeesahHandler by inject<NlBehovLeesahHandler>()
    val sendtSykmeldingHandler by inject<SendtSykmeldingHandler>()
    val environment by inject<Environment>()
    val logger = logger()

    if (!environment.kafka.shouldConsumeTopics) {
        logger.info("Kafka consumers is not enabled, skipping configuration of consumers")
        return
    }

    logger.info("Configuring Kafka consumers")

    val leesahConsumer = LeesahNLKafkaConsumer(
        handler = nlLeesahHandler,
        jacksonMapper = jacksonMapper(),
        kafkaConsumer = KafkaConsumer(
            consumerProperties(
                env = environment.kafka,
                valueDeserializer = StringDeserializer::class,
                groupId = "esyfo-narmesteleder-les-behov"
            ),
            StringDeserializer(),
            StringDeserializer(),
        ),
        scope = this,
    ).apply {
        commitOnAllErrors = environment.kafka.commitOnAllErrors
    }

    val sendtSykmeldingConsumer = SendtSykmeldingKafkaConsumer(
        handler = sendtSykmeldingHandler,
        jacksonMapper = jacksonMapper(),
        kafkaConsumer = KafkaConsumer(
            consumerProperties(
                env = environment.kafka,
                valueDeserializer = StringDeserializer::class,
                groupId = "esyfo-narmesteleder-sendt-sykmelding-consumer"
            ),
            StringDeserializer(),
            StringDeserializer(),
        ),
        scope = this
    )

    val persistSendtSykmeldingConsumer = PersistSendtSykmeldingConsumer(
        handler = sendtSykmeldingHandler,
        jacksonMapper = jacksonMapper(),
        kafkaConsumer = KafkaConsumer(
            consumerProperties(
                env = environment.kafka,
                valueDeserializer = StringDeserializer::class,
                groupId = "esyfo-narmesteleder-persist-sendt-sykmelding-consumer"
            ).apply {
                put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
                put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "500")
                put(
                    ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG,
                    5.minutes
                        .inWholeMilliseconds
                        .toString()
                )
                put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, "1048576")
                put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, "500")
            },
            StringDeserializer(),
            StringDeserializer(),
        ),
        scope = this,
        env = environment.otherProperties,
    )

    // ---- Leader-dependent consumer ----
    val leaderConsumerLock = Any()
    var leaderConsumerStarted = false

    monitor.subscribe(LeaderChangeEvent) { event ->
        when (event) {
            is LeaderChange.ElectedLeader -> {
                synchronized(leaderConsumerLock) {
                    if (leaderConsumerStarted) {
                        runBlocking { persistSendtSykmeldingConsumer.stop() }
                    }
                    logger.info("Elected leader — starting persist sendt sykmelding consumer")
                    persistSendtSykmeldingConsumer.listen()
                    leaderConsumerStarted = true
                }
            }
            is LeaderChange.NotLeader -> {
                synchronized(leaderConsumerLock) {
                    if (leaderConsumerStarted) {
                        logger.info("No longer leader — stopping persist sendt sykmelding consumer")
                        runBlocking { persistSendtSykmeldingConsumer.stop() }
                        leaderConsumerStarted = false
                    }
                }
            }
        }
    }

    // ---- Always-on consumers ----
    monitor.subscribe(ServerReady) {
        leesahConsumer.listen()
        sendtSykmeldingConsumer.listen()
    }

    // ---- Shutdown (top-level, not nested) ----
    monitor.subscribe(ApplicationStopPreparing) {
        synchronized(leaderConsumerLock) {
            if (leaderConsumerStarted) {
                runBlocking { persistSendtSykmeldingConsumer.stop() }
                leaderConsumerStarted = false
            }
        }
        runBlocking {
            sendtSykmeldingConsumer.stop()
            leesahConsumer.stop()
        }
    }
}
