package no.nav.syfo.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopPreparing
import io.ktor.server.application.ServerReady
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import no.nav.syfo.application.environment.Environment
import no.nav.syfo.application.kafka.consumerProperties
import no.nav.syfo.application.kafka.jacksonMapper
import no.nav.syfo.application.leaderelection.LeaderChangeSSEListener
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
    val leaderChangeSSEListener by inject<LeaderChangeSSEListener>()
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
                groupId = "esyfo-narmesteleder-sendt-sykmelding-consumer-persistence"
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

    monitor.subscribe(ServerReady) {
        val sseListenerJob = launch { leaderChangeSSEListener.listenForLeaderChanges() }
        leesahConsumer.listen()
        sendtSykmeldingConsumer.listen()

        monitor.subscribe(ApplicationStopPreparing) {
            runBlocking {
                sseListenerJob.cancel()
                sendtSykmeldingConsumer.stop()
                leesahConsumer.stop()
            }
        }
    }

    launch(Dispatchers.IO) {
        persistSendtSykmeldingConsumer.use { consumer ->
            leaderChangeSSEListener.isLeader.collect { isLeader ->
                if (isLeader) {
                    logger.info("This instance is now the leader. Starting persistSendtSykmeldingConsumer.")
                    consumer.listen()
                    monitor.subscribe(ApplicationStopPreparing) {
                        launch { consumer.stop() }
                    }
                }
            }
        }
    }
}
